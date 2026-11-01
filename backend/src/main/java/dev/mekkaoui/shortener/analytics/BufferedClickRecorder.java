package dev.mekkaoui.shortener.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe, non-blocking in-memory click recorder with periodic and threshold-based flushes.
 *
 * <p>Hot-path guarantee:
 * {@link #record(String)} executes in O(1) time in memory, never performs database or I/O operations,
 * and never throws any exceptions.
 *
 * <p>Memory bounding:
 * The distinct pending keys are bounded by {@code maxPendingKeys}. A click on an existing key is
 * always counted. A click that would create a new key beyond the bound is dropped and counted.
 * Note: Under high concurrency, the {@code maxPendingKeys} bound is approximate because the map size
 * check and entry insertion are not executed as a single atomic operation.
 *
 * <p>Swap race and at-most-once delivery:
 * Flushes atomically swap the active map reference using an {@link AtomicReference}.
 * A click racing with the swap can be lost if a concurrent redirect thread obtains the reference to
 * the old map immediately prior to the swap and increments its counter after the flusher has already
 * iterated past that key or discarded the old map. This sub-microsecond window of vulnerability is
 * accepted by design: delivery is strictly at-most-once (clicks can be dropped on crash or mid-swap
 * to avoid double-counting and preserve a lock-free hot path).
 *
 * <p>Flusher resilience:
 * All exceptions during background flushes are caught and logged as a single WARN line. Failed batches
 * are dropped (not retried) to prevent double counting, and the periodic flusher is never suppressed
 * by the {@link ScheduledExecutorService}.
 */
public class BufferedClickRecorder implements ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(BufferedClickRecorder.class);

    private final ClickStatsWriter writer;
    private final Clock clock;
    private final int flushThreshold;
    private final int maxPendingKeys;
    private final ScheduledExecutorService executor;
    private final AtomicReference<ConcurrentHashMap<ClickKey, LongAdder>> mapRef;
    private final LongAdder totalPendingClicks;
    private final LongAdder droppedClicksSinceLastFlush;
    private final AtomicBoolean flushScheduled;
    private final Object flushLock;
    private volatile boolean running;

    public BufferedClickRecorder(ClickStatsWriter writer, AnalyticsProperties properties) {
        this(writer, properties, Clock.systemUTC());
    }

    public BufferedClickRecorder(ClickStatsWriter writer, AnalyticsProperties properties, Clock clock) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        this.flushThreshold = properties.flushThreshold();
        this.maxPendingKeys = properties.maxPendingKeys();
        this.mapRef = new AtomicReference<>(new ConcurrentHashMap<>());
        this.totalPendingClicks = new LongAdder();
        this.droppedClicksSinceLastFlush = new LongAdder();
        this.flushScheduled = new AtomicBoolean(false);
        this.flushLock = new Object();
        this.running = true;

        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "click-stats-flusher");
            thread.setDaemon(true);
            return thread;
        });

        long intervalMillis = properties.flushInterval().toMillis();
        this.executor.scheduleWithFixedDelay(
                this::safePeriodicFlush,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void record(String code) {
        if (!running || code == null) {
            return;
        }

        try {
            LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            ClickKey key = new ClickKey(code, day);

            ConcurrentHashMap<ClickKey, LongAdder> map = mapRef.get();
            LongAdder existing = map.get(key);
            if (existing != null) {
                existing.increment();
                onSuccessfulClick();
                return;
            }

            if (map.size() >= maxPendingKeys) {
                droppedClicksSinceLastFlush.increment();
                return;
            }

            LongAdder adder = map.computeIfAbsent(key, k -> {
                if (map.size() >= maxPendingKeys) {
                    return null;
                }
                return new LongAdder();
            });

            if (adder != null) {
                adder.increment();
                onSuccessfulClick();
            } else {
                droppedClicksSinceLastFlush.increment();
            }
        } catch (Throwable t) {
            // Contract: record() must never throw
            log.warn("Failed to record click: {}", t.getMessage());
        }
    }

    private void onSuccessfulClick() {
        totalPendingClicks.increment();
        if (totalPendingClicks.sum() >= flushThreshold) {
            signalFlusher();
        }
    }

    private void signalFlusher() {
        if (!running) {
            return;
        }

        if (flushScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(() -> {
                    try {
                        flush();
                    } finally {
                        flushScheduled.set(false);
                    }
                });
            } catch (RejectedExecutionException ex) {
                flushScheduled.set(false);
            } catch (Throwable t) {
                flushScheduled.set(false);
            }
        }
    }

    private void safePeriodicFlush() {
        try {
            flush();
        } catch (Throwable t) {
            log.warn("Unexpected error in periodic click stats flusher: {}", t.getMessage());
        }
    }

    /**
     * Flushes currently buffered click counts to PostgreSQL.
     * Package-private test hook allowing deterministic verification without sleep.
     */
    void flush() {
        synchronized (flushLock) {
            long dropped = droppedClicksSinceLastFlush.sumThenReset();
            if (dropped > 0) {
                log.warn("dropped {} clicks since last flush", dropped);
            }

            ConcurrentHashMap<ClickKey, LongAdder> oldMap = mapRef.getAndSet(new ConcurrentHashMap<>());
            if (oldMap.isEmpty()) {
                return;
            }

            List<ClickStat> batch = new ArrayList<>(oldMap.size());
            long batchTotal = 0;
            for (Map.Entry<ClickKey, LongAdder> entry : oldMap.entrySet()) {
                long count = entry.getValue().sum();
                if (count > 0) {
                    batch.add(new ClickStat(entry.getKey().code(), entry.getKey().day(), count));
                    batchTotal += count;
                }
            }

            totalPendingClicks.add(-batchTotal);

            if (batch.isEmpty()) {
                return;
            }

            try {
                writer.write(batch);
            } catch (Exception ex) {
                log.warn("Failed to flush click stats batch: {}", ex.getMessage());
                droppedClicksSinceLastFlush.add(batchTotal);
            }
        }
    }

    public void stop() {
        running = false;
        try {
            flush();
        } catch (Throwable t) {
            log.warn("Failed to complete shutdown click stats flush: {}", t.getMessage());
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    record ClickKey(String code, LocalDate day) {
        ClickKey {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(day, "day must not be null");
        }
    }
}

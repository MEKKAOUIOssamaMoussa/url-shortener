package dev.mekkaoui.shortener.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory fixed-window rate limiter.
 *
 * Memory note:
 * Memory is bounded across windows because old counter maps are dropped and eligible for GC
 * upon window swap. However, within a single active window, the map grows with the number of
 * distinct client keys; a distributed many-IP flood within a single minute window remains a known limit.
 */
public class InMemoryFixedWindowRateLimiter implements RateLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final AtomicReference<WindowState> currentState;

    public InMemoryFixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.limit = limit;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        long initialWindowIndex = Math.floorDiv(clock.instant().toEpochMilli(), window.toMillis());
        this.currentState = new AtomicReference<>(new WindowState(initialWindowIndex));
    }

    @Override
    public RateLimitDecision tryAcquire(String clientKey) {
        Objects.requireNonNull(clientKey, "clientKey must not be null");

        Instant now = clock.instant();
        long nowMillis = now.toEpochMilli();
        long windowMillis = window.toMillis();
        long windowIndex = Math.floorDiv(nowMillis, windowMillis);

        WindowState state = currentState.get();
        while (state.windowIndex != windowIndex) {
            if (windowIndex < state.windowIndex) {
                // Clock read was slightly behind the currently advanced window (delayed thread);
                // re-sample the clock to catch up with the active window.
                now = clock.instant();
                nowMillis = now.toEpochMilli();
                windowIndex = Math.floorDiv(nowMillis, windowMillis);
                state = currentState.get();
                continue;
            }
            WindowState newState = new WindowState(windowIndex);
            if (currentState.compareAndSet(state, newState)) {
                state = newState;
                break;
            }
            state = currentState.get();
        }

        // Boundary imprecision note:
        // A concurrent thread that read the old window state reference just before the swap
        // may still increment the old map; this minor boundary imprecision is acceptable for a fixed window.
        AtomicInteger counter = state.counters.computeIfAbsent(clientKey, k -> new AtomicInteger());
        int currentCount = counter.updateAndGet(c -> c < limit ? c + 1 : limit + 1);

        if (currentCount <= limit) {
            return new RateLimitDecision.Allowed();
        }

        long windowEndMillis = (windowIndex + 1) * windowMillis;
        long remainingMillis = windowEndMillis - nowMillis;
        long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        return new RateLimitDecision.Rejected(Duration.ofSeconds(retryAfterSeconds));
    }

    int currentWindowKeyCount() {
        return currentState.get().counters.size();
    }

    long currentWindowIndex() {
        return currentState.get().windowIndex;
    }

    private record WindowState(long windowIndex, ConcurrentHashMap<String, AtomicInteger> counters) {
        WindowState(long windowIndex) {
            this(windowIndex, new ConcurrentHashMap<>());
        }
    }
}

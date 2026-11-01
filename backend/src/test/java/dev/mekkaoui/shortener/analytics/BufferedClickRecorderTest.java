package dev.mekkaoui.shortener.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BufferedClickRecorderTest {

    private final List<BufferedClickRecorder> recordersToClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (BufferedClickRecorder recorder : recordersToClose) {
            recorder.stop();
        }
    }

    private BufferedClickRecorder createRecorder(ClickStatsWriter writer, AnalyticsProperties properties, Clock clock) {
        BufferedClickRecorder recorder = new BufferedClickRecorder(writer, properties, clock);
        recordersToClose.add(recorder);
        return recorder;
    }

    @Test
    void shouldAggregateClicksPerCodeAndUtcDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
        List<List<ClickStat>> flushedBatches = new CopyOnWriteArrayList<>();

        ClickStatsWriter fakeWriter = createFakeWriter(flushedBatches::add);
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 100, 1000);
        BufferedClickRecorder recorder = createRecorder(fakeWriter, properties, clock);

        recorder.record("abc123");
        recorder.record("abc123");
        recorder.record("xyz789");
        recorder.record("abc123");

        recorder.flush();

        assertThat(flushedBatches).hasSize(1);
        List<ClickStat> batch = flushedBatches.getFirst();
        assertThat(batch).hasSize(2);

        LocalDate expectedDay = LocalDate.of(2026, 11, 1);
        long abcCount = batch.stream()
                .filter(stat -> stat.code().equals("abc123") && stat.day().equals(expectedDay))
                .mapToLong(ClickStat::count)
                .findFirst()
                .orElse(0L);
        long xyzCount = batch.stream()
                .filter(stat -> stat.code().equals("xyz789") && stat.day().equals(expectedDay))
                .mapToLong(ClickStat::count)
                .findFirst()
                .orElse(0L);

        assertThat(abcCount).isEqualTo(3L);
        assertThat(xyzCount).isEqualTo(1L);
    }

    @Test
    void shouldSplitClicksAcrossUtcMidnightBoundaries() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-01T23:59:59Z"));
        List<List<ClickStat>> flushedBatches = new CopyOnWriteArrayList<>();

        ClickStatsWriter fakeWriter = createFakeWriter(flushedBatches::add);
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 100, 1000);
        BufferedClickRecorder recorder = createRecorder(fakeWriter, properties, clock);

        // Before midnight
        recorder.record("splitCode");

        // Advance to 1 second after UTC midnight
        clock.setInstant(Instant.parse("2026-11-02T00:00:01Z"));
        recorder.record("splitCode");
        recorder.record("splitCode");

        recorder.flush();

        assertThat(flushedBatches).hasSize(1);
        List<ClickStat> batch = flushedBatches.getFirst();
        assertThat(batch).hasSize(2);

        LocalDate day1 = LocalDate.of(2026, 11, 1);
        LocalDate day2 = LocalDate.of(2026, 11, 2);

        long countDay1 = batch.stream().filter(s -> s.day().equals(day1)).mapToLong(ClickStat::count).sum();
        long countDay2 = batch.stream().filter(s -> s.day().equals(day2)).mapToLong(ClickStat::count).sum();

        assertThat(countDay1).isEqualTo(1L);
        assertThat(countDay2).isEqualTo(2L);
    }

    @Test
    void shouldTriggerEarlyFlushWhenPendingClicksReachThreshold() throws Exception {
        CountDownLatch flushLatch = new CountDownLatch(1);
        List<List<ClickStat>> flushedBatches = new CopyOnWriteArrayList<>();

        ClickStatsWriter fakeWriter = createFakeWriter(batch -> {
            flushedBatches.add(batch);
            flushLatch.countDown();
        });

        // Threshold = 5, Flush interval = 1 hour (periodic will not fire)
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 5, 1000);
        BufferedClickRecorder recorder = createRecorder(fakeWriter, properties, Clock.systemUTC());

        for (int i = 0; i < 4; i++) {
            recorder.record("codeThreshold");
        }
        assertThat(flushedBatches).isEmpty();

        // 5th click reaches threshold of 5 -> triggers early background flush
        recorder.record("codeThreshold");

        boolean flushed = flushLatch.await(3, TimeUnit.SECONDS);
        assertThat(flushed).isTrue();
        assertThat(flushedBatches).hasSize(1);
        assertThat(flushedBatches.getFirst().getFirst().count()).isEqualTo(5L);
    }

    @Test
    void shouldDropNewKeysWhenMaxPendingKeysReachedWhileStillCountingExistingKeys() {
        List<List<ClickStat>> flushedBatches = new CopyOnWriteArrayList<>();
        ClickStatsWriter fakeWriter = createFakeWriter(flushedBatches::add);

        // maxPendingKeys = 2
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 100, 2);
        BufferedClickRecorder recorder = createRecorder(fakeWriter, properties, Clock.systemUTC());

        recorder.record("key1");
        recorder.record("key2");

        // 3rd distinct key exceeds bound of 2 -> must be dropped
        recorder.record("key3");

        // Existing keys must still be counted
        recorder.record("key1");
        recorder.record("key2");
        recorder.record("key1");

        recorder.flush();

        assertThat(flushedBatches).hasSize(1);
        List<ClickStat> batch = flushedBatches.getFirst();
        assertThat(batch).hasSize(2);

        long key1Count = batch.stream().filter(s -> s.code().equals("key1")).mapToLong(ClickStat::count).sum();
        long key2Count = batch.stream().filter(s -> s.code().equals("key2")).mapToLong(ClickStat::count).sum();
        boolean key3Present = batch.stream().anyMatch(s -> s.code().equals("key3"));

        assertThat(key1Count).isEqualTo(3L);
        assertThat(key2Count).isEqualTo(2L);
        assertThat(key3Present).isFalse();
    }

    @Test
    void recordShouldNeverBlockEvenWhenWriterIsBlocked() throws Exception {
        CountDownLatch writerBlockLatch = new CountDownLatch(1);
        CountDownLatch writerStartedLatch = new CountDownLatch(1);

        ClickStatsWriter blockingWriter = createFakeWriter(batch -> {
            writerStartedLatch.countDown();
            try {
                writerBlockLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Threshold = 10 so flusher starts and blocks in the writer
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 10, 50000);
        BufferedClickRecorder recorder = createRecorder(blockingWriter, properties, Clock.systemUTC());

        // Trigger early flush to block writer
        for (int i = 0; i < 10; i++) {
            recorder.record("warmup");
        }
        assertThat(writerStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // While writer is blocked, record 10,000 clicks: must not block (< 1000ms bound) and never throw
        long startTime = System.nanoTime();
        assertThatCode(() -> {
            for (int i = 0; i < 10_000; i++) {
                recorder.record("hotKey" + (i % 5));
            }
        }).doesNotThrowAnyException();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        assertThat(elapsedMs).isLessThan(1000L);

        // Unblock writer
        writerBlockLatch.countDown();
    }

    @Test
    void shouldAccumulateExactCountsUnderHighConcurrency() throws Exception {
        List<List<ClickStat>> flushedBatches = new CopyOnWriteArrayList<>();
        ClickStatsWriter fakeWriter = createFakeWriter(flushedBatches::add);

        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 100_000, 10_000);
        BufferedClickRecorder recorder = createRecorder(fakeWriter, properties, Clock.systemUTC());

        int threadCount = 10;
        int clicksPerThread = 1_000;
        int totalExpected = threadCount * clicksPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            pool.execute(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < clicksPerThread; i++) {
                        recorder.record("concurrentCode" + (i % 4));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Force final flush after all threads join
        recorder.flush();

        long totalFlushed = flushedBatches.stream()
                .flatMap(List::stream)
                .mapToLong(ClickStat::count)
                .sum();

        assertThat(totalFlushed).isEqualTo((long) totalExpected);
    }

    @Test
    void shouldDropBatchOnWriterFailureWithoutRetryingAndAllowNextFlushToSucceed() {
        AtomicInteger writeAttempts = new AtomicInteger(0);
        List<List<ClickStat>> succeededBatches = new CopyOnWriteArrayList<>();

        ClickStatsWriter failingWriter = createFakeWriter(batch -> {
            int attempt = writeAttempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("Simulated PostgreSQL connection failure");
            }
            succeededBatches.add(batch);
        });

        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 1000, 1000);
        BufferedClickRecorder recorder = createRecorder(failingWriter, properties, Clock.systemUTC());

        // First batch: writer fails
        recorder.record("codeBatch1");
        recorder.flush();

        assertThat(writeAttempts.get()).isEqualTo(1);
        assertThat(succeededBatches).isEmpty();

        // Second batch: writer succeeds; first batch must not be retried
        recorder.record("codeBatch2");
        recorder.flush();

        assertThat(writeAttempts.get()).isEqualTo(2);
        assertThat(succeededBatches).hasSize(1);
        List<ClickStat> secondBatch = succeededBatches.getFirst();
        assertThat(secondBatch).hasSize(1);
        assertThat(secondBatch.getFirst().code()).isEqualTo("codeBatch2");
    }

    @Test
    void periodicFlusherShouldNeverBeSuppressedWhenWriterThrows() throws Exception {
        CountDownLatch firstFlushFailedLatch = new CountDownLatch(1);
        CountDownLatch secondFlushSuccessLatch = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger(0);

        ClickStatsWriter writer = createFakeWriter(batch -> {
            int count = invocationCount.incrementAndGet();
            if (count == 1) {
                firstFlushFailedLatch.countDown();
                throw new RuntimeException("First periodic flush failed");
            }
            secondFlushSuccessLatch.countDown();
        });

        // Fast periodic interval: 50ms
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofMillis(50), 1000, 1000);
        BufferedClickRecorder recorder = createRecorder(writer, properties, Clock.systemUTC());

        recorder.record("codeBatch1");

        // Wait for the first periodic flush to fail
        assertThat(firstFlushFailedLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Record new clicks: periodic flusher must still be running and flush them
        recorder.record("codeBatch2");

        // Wait for the second periodic flush to successfully fire and write
        boolean secondFlushExecuted = secondFlushSuccessLatch.await(3, TimeUnit.SECONDS);
        assertThat(secondFlushExecuted).isTrue();
        assertThat(invocationCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void recordShouldBeSafeNoOpAndNeverThrowAfterStop() {
        AnalyticsProperties properties = new AnalyticsProperties(true, Duration.ofHours(1), 10, 1000);
        BufferedClickRecorder recorder = createRecorder(createFakeWriter(b -> {}), properties, Clock.systemUTC());

        recorder.stop();
        assertThat(recorder.isRunning()).isFalse();

        assertThatCode(() -> {
            for (int i = 0; i < 50; i++) {
                recorder.record("afterStopCode");
            }
        }).doesNotThrowAnyException();
    }

    private ClickStatsWriter createFakeWriter(java.util.function.Consumer<List<ClickStat>> consumer) {
        return new ClickStatsWriter(new org.springframework.jdbc.core.JdbcTemplate(), new FakeTransactionManager()) {
            @Override
            public void write(List<ClickStat> batch) {
                consumer.accept(batch);
            }
        };
    }

    private static class FakeTransactionManager implements org.springframework.transaction.PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {}

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {}
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneOffset zone = ZoneOffset.UTC;

        MutableClock(Instant initialInstant) {
            this.instant = initialInstant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

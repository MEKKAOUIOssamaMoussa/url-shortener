package dev.mekkaoui.shortener.ratelimit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryFixedWindowRateLimiterTest {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    @Test
    void shouldAllowRequestsUpToLimitAndRejectSubsequentWithRemainingDuration() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, clock);

        for (int i = 0; i < 10; i++) {
            RateLimitDecision decision = limiter.tryAcquire("client-1");
            assertThat(decision).isInstanceOf(RateLimitDecision.Allowed.class);
        }

        RateLimitDecision rejectedDecision = limiter.tryAcquire("client-1");
        assertThat(rejectedDecision).isInstanceOf(RateLimitDecision.Rejected.class);

        RateLimitDecision.Rejected rejected = (RateLimitDecision.Rejected) rejectedDecision;
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldMaintainIndependentCountersForDifferentClients() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, clock);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("client-a")).isInstanceOf(RateLimitDecision.Allowed.class);
        }

        assertThat(limiter.tryAcquire("client-a")).isInstanceOf(RateLimitDecision.Rejected.class);
        assertThat(limiter.tryAcquire("client-b")).isInstanceOf(RateLimitDecision.Allowed.class);
    }

    @Test
    void shouldResetCountersWhenWindowAdvances() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, clock);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("client-1")).isInstanceOf(RateLimitDecision.Allowed.class);
        }
        assertThat(limiter.tryAcquire("client-1")).isInstanceOf(RateLimitDecision.Rejected.class);

        // Advance into next window
        clock.advance(Duration.ofMinutes(1));

        RateLimitDecision newWindowDecision = limiter.tryAcquire("client-1");
        assertThat(newWindowDecision).isInstanceOf(RateLimitDecision.Allowed.class);
    }

    @Test
    void shouldReturnAtLeastOneSecondRetryAfterNearWindowEnd() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, clock);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("client-1");
        }

        // Set clock to 50 ms before the 60-second window ends (59.950s)
        clock.set(Instant.ofEpochMilli(59_950));

        RateLimitDecision decision = limiter.tryAcquire("client-1");
        assertThat(decision).isInstanceOf(RateLimitDecision.Rejected.class);

        RateLimitDecision.Rejected rejected = (RateLimitDecision.Rejected) decision;
        assertThat(rejected.retryAfter().getSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void shouldDropOldWindowCountersWhenWindowAdvances() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, clock);

        // Populate 5 distinct client keys in window 0
        for (int i = 1; i <= 5; i++) {
            limiter.tryAcquire("client-" + i);
        }
        assertThat(limiter.currentWindowIndex()).isEqualTo(0L);
        assertThat(limiter.currentWindowKeyCount()).isEqualTo(5);

        // Advance to window 1 and perform 1 request with 1 client
        clock.advance(Duration.ofMinutes(1));
        limiter.tryAcquire("client-1");

        assertThat(limiter.currentWindowIndex()).isEqualTo(1L);
        assertThat(limiter.currentWindowKeyCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidConstructorArguments() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);

        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(0, ONE_MINUTE, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");

        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(-1, ONE_MINUTE, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be positive");

        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(10, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("window must be positive");

        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(10, Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("window must be positive");

        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(10, null, clock))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("window must not be null");

        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock must not be null");
    }

    @Test
    void shouldRejectNullClientKey() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(10, ONE_MINUTE, clock);

        assertThatThrownBy(() -> limiter.tryAcquire(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clientKey must not be null");
    }

    @RepeatedTest(10)
    void shouldAllowExactlyLimitUnderHighConcurrency() throws InterruptedException {
        int limit = 10;
        int threads = 50;
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0), ZoneOffset.UTC);
        InMemoryFixedWindowRateLimiter limiter = new InMemoryFixedWindowRateLimiter(limit, ONE_MINUTE, clock);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        RateLimitDecision decision = limiter.tryAcquire("shared-concurrent-client");
                        if (decision instanceof RateLimitDecision.Allowed) {
                            allowedCount.incrementAndGet();
                        } else if (decision instanceof RateLimitDecision.Rejected) {
                            rejectedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release all threads simultaneously
            startLatch.countDown();
            boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
            assertThat(finished).isTrue();
        }

        assertThat(allowedCount.get()).isEqualTo(limit);
        assertThat(rejectedCount.get()).isEqualTo(threads - limit);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant initialInstant, ZoneId zone) {
            this.instant = initialInstant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(this.instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        void set(Instant instant) {
            this.instant = instant;
        }
    }
}

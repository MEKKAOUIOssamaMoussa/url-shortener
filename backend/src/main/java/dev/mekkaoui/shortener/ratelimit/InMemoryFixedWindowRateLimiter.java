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
        // Advance window only when the sampled window is strictly ahead.
        // If windowIndex < state.windowIndex, the wall clock (e.g. Clock.systemUTC()) moved backwards
        // (such as during an NTP adjustment) or a thread was briefly descheduled. We do not spin
        // or rewind state: we count the request in the current active window.
        while (windowIndex > state.windowIndex) {
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

        // Retry-After computation:
        // Use state.windowIndex rather than sampled windowIndex in case the clock was behind.
        // Clamp retryAfter to [1 s, window length] to ensure a sane wait duration even if
        // nowMillis lags behind due to backward clock shifts.
        long windowEndMillis = (state.windowIndex + 1) * windowMillis;
        long remainingMillis = windowEndMillis - nowMillis;
        long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        long maxRetryAfterSeconds = Math.max(1, window.toSeconds());
        long clampedRetryAfter = Math.clamp(retryAfterSeconds, 1L, maxRetryAfterSeconds);
        return new RateLimitDecision.Rejected(Duration.ofSeconds(clampedRetryAfter));
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

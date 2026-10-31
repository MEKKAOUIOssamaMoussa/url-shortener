package dev.mekkaoui.shortener.ratelimit;

public interface RateLimiter {
    RateLimitDecision tryAcquire(String clientKey);
}

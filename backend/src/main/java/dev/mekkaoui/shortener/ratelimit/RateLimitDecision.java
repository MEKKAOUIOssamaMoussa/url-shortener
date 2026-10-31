package dev.mekkaoui.shortener.ratelimit;

import java.time.Duration;
import java.util.Objects;

public sealed interface RateLimitDecision permits RateLimitDecision.Allowed, RateLimitDecision.Rejected {

    record Allowed() implements RateLimitDecision {}

    record Rejected(Duration retryAfter) implements RateLimitDecision {
        public Rejected {
            Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        }
    }
}

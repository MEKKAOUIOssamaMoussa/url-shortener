package dev.mekkaoui.shortener.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "shortener.analytics")
public record AnalyticsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") Duration flushInterval,
        @DefaultValue("100") int flushThreshold,
        @DefaultValue("10000") int maxPendingKeys
) {

    public AnalyticsProperties {
        if (flushInterval == null || flushInterval.isNegative() || flushInterval.isZero()) {
            throw new IllegalArgumentException("shortener.analytics.flush-interval must be positive");
        }
        if (flushThreshold <= 0) {
            throw new IllegalArgumentException("shortener.analytics.flush-threshold must be positive, got: " + flushThreshold);
        }
        if (maxPendingKeys <= 0) {
            throw new IllegalArgumentException("shortener.analytics.max-pending-keys must be positive, got: " + maxPendingKeys);
        }
    }
}

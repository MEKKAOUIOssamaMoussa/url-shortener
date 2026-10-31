package dev.mekkaoui.shortener.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "shortener.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int limit,
        @DefaultValue("1m") Duration window,
        @DefaultValue("MEMORY") RateLimitStore store
) {
}

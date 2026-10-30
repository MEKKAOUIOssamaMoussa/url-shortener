package dev.mekkaoui.shortener.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "shortener.cache")
public record CacheProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("24h") Duration ttl
) {
}

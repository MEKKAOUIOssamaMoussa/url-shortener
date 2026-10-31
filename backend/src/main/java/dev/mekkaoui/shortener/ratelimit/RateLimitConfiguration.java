package dev.mekkaoui.shortener.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shortener.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RateLimiter rateLimiter(RateLimitProperties properties) {
        return new InMemoryFixedWindowRateLimiter(properties.limit(), properties.window(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "shortener.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        return new RateLimitFilter(rateLimiter, objectMapper);
    }
}

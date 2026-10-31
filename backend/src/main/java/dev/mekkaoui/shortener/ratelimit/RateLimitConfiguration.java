package dev.mekkaoui.shortener.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "shortener.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shortener.rate-limit", name = "store", havingValue = "memory", matchIfMissing = true)
    public RateLimiter inMemoryRateLimiter(RateLimitProperties properties) {
        return new InMemoryFixedWindowRateLimiter(properties.limit(), properties.window(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "shortener.rate-limit", name = "store", havingValue = "redis")
    public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        return new RedisFixedWindowRateLimiter(redisTemplate, properties.limit(), properties.window());
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        return new RateLimitFilter(rateLimiter, objectMapper);
    }
}

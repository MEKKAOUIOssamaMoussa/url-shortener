package dev.mekkaoui.shortener.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class CacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shortener.cache", name = "enabled", havingValue = "true")
    public UrlCache redisUrlCache(StringRedisTemplate redisTemplate, CacheProperties properties) {
        return new RedisUrlCache(redisTemplate, properties.ttl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "shortener.cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    public UrlCache noOpUrlCache() {
        return new NoOpUrlCache();
    }
}

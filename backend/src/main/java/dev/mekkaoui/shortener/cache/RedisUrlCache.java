package dev.mekkaoui.shortener.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

public class RedisUrlCache implements UrlCache {

    private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);
    private static final String KEY_PREFIX = "url:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisUrlCache(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public Optional<String> get(String code) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + code);
            return Optional.ofNullable(value);
        } catch (DataAccessException ex) {
            log.warn("Redis error on get for code '{}': {}", code, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String code, String targetUrl) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + code, targetUrl, ttl);
        } catch (DataAccessException ex) {
            log.warn("Redis error on put for code '{}': {}", code, ex.getMessage());
        }
    }
}

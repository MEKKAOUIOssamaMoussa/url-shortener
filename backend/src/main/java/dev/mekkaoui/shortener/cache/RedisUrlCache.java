package dev.mekkaoui.shortener.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisUrlCache implements UrlCache {

    private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);
    private static final String KEY_PREFIX = "url:";

    // Empty string sentinel represents a known-missing code.
    // The API is the sole writer to the database and rejects blank target URLs
    // (@NotBlank and @ValidUrl on CreateShortUrlRequest), so "" can never be a legitimate target URL.
    private static final String NEGATIVE_SENTINEL = "";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final Duration negativeTtl;

    public RedisUrlCache(StringRedisTemplate redisTemplate, Duration ttl, Duration negativeTtl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
        this.negativeTtl = negativeTtl;
    }

    @Override
    public CacheLookup get(String code) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + code);
            if (value == null) {
                return new CacheLookup.Miss();
            }
            if (NEGATIVE_SENTINEL.equals(value)) {
                return new CacheLookup.KnownMissing();
            }
            return new CacheLookup.Hit(value);
        } catch (DataAccessException ex) {
            log.warn("Redis error on get for code '{}': {}", code, ex.getMessage());
            return new CacheLookup.Miss();
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

    @Override
    public void putMissing(String code) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + code, NEGATIVE_SENTINEL, negativeTtl);
        } catch (DataAccessException ex) {
            log.warn("Redis error on putMissing for code '{}': {}", code, ex.getMessage());
        }
    }
}

package dev.mekkaoui.shortener.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe Redis-backed fixed-window rate limiter for multi-instance deployments.
 *
 * Window semantics note:
 * Unlike the in-memory limiter's clock-aligned windows, time in this limiter is owned by Redis,
 * and each client's window starts at that client's first request (via Redis 7+ PEXPIRE ... NX).
 *
 * Fail-open note:
 * On DataAccessException, the limiter fails open by allowing the request and logging a single
 * warning. Client identifiers (IPs) are omitted from warning logs to avoid logging PII.
 */
public class RedisFixedWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);
    private static final String KEY_PREFIX = "rl:";

    private static final String LUA_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[1], 'NX')
            return { count, redis.call('PTTL', KEYS[1]) }
            """;

    @SuppressWarnings("unchecked")
    private static final RedisScript<List<Long>> SCRIPT = RedisScript.of(
            LUA_SCRIPT,
            (Class<List<Long>>) (Class<?>) List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final int limit;
    private final Duration window;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate, int limit, Duration window) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.limit = limit;
        this.window = window;
    }

    @Override
    public RateLimitDecision tryAcquire(String clientKey) {
        Objects.requireNonNull(clientKey, "clientKey must not be null");
        try {
            String redisKey = KEY_PREFIX + clientKey;
            List<Long> result = redisTemplate.execute(
                    SCRIPT,
                    List.of(redisKey),
                    String.valueOf(window.toMillis())
            );

            if (result == null || result.size() < 2) {
                return new RateLimitDecision.Allowed();
            }

            long count = result.get(0) != null ? result.get(0) : 0L;
            long pttl = result.get(1) != null ? result.get(1) : 0L;

            if (count <= limit) {
                return new RateLimitDecision.Allowed();
            }

            long retryAfterSeconds = Math.max(1, (pttl + 999) / 1000);
            long maxRetryAfterSeconds = Math.max(1, window.toSeconds());
            long clampedRetryAfter = Math.clamp(retryAfterSeconds, 1L, maxRetryAfterSeconds);
            return new RateLimitDecision.Rejected(Duration.ofSeconds(clampedRetryAfter));
        } catch (DataAccessException ex) {
            log.warn("Redis rate limiter error, failing open: {}", ex.getMessage());
            return new RateLimitDecision.Allowed();
        }
    }
}

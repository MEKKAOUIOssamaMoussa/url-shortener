package dev.mekkaoui.shortener.ratelimit;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisFixedWindowRateLimiterTest {

    private static final RedisContainer REDIS = new RedisContainer("redis:8");

    @BeforeAll
    static void beforeAll() {
        REDIS.start();
    }

    @AfterAll
    static void afterAll() {
        REDIS.stop();
    }

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisFixedWindowRateLimiter limiter;
    private final int limit = 10;
    private final Duration window = Duration.ofMinutes(1);

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getFirstMappedPort()
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        limiter = new RedisFixedWindowRateLimiter(redisTemplate, limit, window);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static String uniqueKey() {
        return "client-" + UUID.randomUUID();
    }

    @Test
    void testA_limitThenRejectedWithValidRetryAfterAndTtl() {
        String key = uniqueKey();
        for (int i = 0; i < limit; i++) {
            RateLimitDecision decision = limiter.tryAcquire(key);
            assertThat(decision).isInstanceOf(RateLimitDecision.Allowed.class);
        }

        RateLimitDecision rejectedDecision = limiter.tryAcquire(key);
        assertThat(rejectedDecision).isInstanceOf(RateLimitDecision.Rejected.class);
        RateLimitDecision.Rejected rejected = (RateLimitDecision.Rejected) rejectedDecision;

        assertThat(rejected.retryAfter().getSeconds()).isGreaterThanOrEqualTo(1);
        assertThat(rejected.retryAfter().getSeconds()).isLessThanOrEqualTo(window.toSeconds());

        Long expireMillis = redisTemplate.getExpire("rl:" + key, TimeUnit.MILLISECONDS);
        assertThat(expireMillis).isNotNull();
        assertThat(expireMillis).isGreaterThan(0);
        assertThat(expireMillis).isLessThanOrEqualTo(window.toMillis());
    }

    @RepeatedTest(5)
    void testB_atomicCounterUnderConcurrency() throws InterruptedException, ExecutionException {
        String key = uniqueKey();
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<RateLimitDecision>> tasks = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> limiter.tryAcquire(key));
            }

            List<Future<RateLimitDecision>> futures = executor.invokeAll(tasks);
            int allowed = 0;
            int rejected = 0;
            for (Future<RateLimitDecision> future : futures) {
                if (future.get() instanceof RateLimitDecision.Allowed) {
                    allowed++;
                } else {
                    rejected++;
                }
            }

            assertThat(allowed).isEqualTo(limit);
            assertThat(rejected).isEqualTo(threadCount - limit);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testC_sharedCounterAcrossInstances() {
        RateLimiter instance1 = new RedisFixedWindowRateLimiter(redisTemplate, limit, window);
        RateLimiter instance2 = new RedisFixedWindowRateLimiter(redisTemplate, limit, window);
        String key = uniqueKey();

        int allowedCount = 0;
        int rejectedCount = 0;
        for (int i = 0; i < 6; i++) {
            if (instance1.tryAcquire(key) instanceof RateLimitDecision.Allowed) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }
        for (int i = 0; i < 6; i++) {
            if (instance2.tryAcquire(key) instanceof RateLimitDecision.Allowed) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }

        assertThat(allowedCount).as("Total allowed across two instances on the same key").isEqualTo(10);
        assertThat(rejectedCount).as("Total rejected across two instances on the same key").isEqualTo(2);
    }

    @Test
    void testD_expiryResetsCounter() throws InterruptedException {
        Duration shortWindow = Duration.ofSeconds(1);
        RedisFixedWindowRateLimiter shortLimiter = new RedisFixedWindowRateLimiter(redisTemplate, limit, shortWindow);
        String key = uniqueKey();

        for (int i = 0; i < limit; i++) {
            assertThat(shortLimiter.tryAcquire(key)).isInstanceOf(RateLimitDecision.Allowed.class);
        }
        assertThat(shortLimiter.tryAcquire(key)).isInstanceOf(RateLimitDecision.Rejected.class);

        Thread.sleep(1100);

        assertThat(shortLimiter.tryAcquire(key)).isInstanceOf(RateLimitDecision.Allowed.class);
    }

    @Test
    void testE_failsOpenWhenRedisIsUnreachable() {
        RedisStandaloneConfiguration unreachableConfig = new RedisStandaloneConfiguration("localhost", 65432);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .build();
        LettuceConnectionFactory unreachableFactory = new LettuceConnectionFactory(unreachableConfig, clientConfig);
        unreachableFactory.afterPropertiesSet();

        StringRedisTemplate unreachableTemplate = new StringRedisTemplate(unreachableFactory);
        unreachableTemplate.afterPropertiesSet();

        RedisFixedWindowRateLimiter unreachableLimiter = new RedisFixedWindowRateLimiter(unreachableTemplate, limit, window);

        assertThatCode(() -> {
            RateLimitDecision decision = unreachableLimiter.tryAcquire(uniqueKey());
            assertThat(decision).isInstanceOf(RateLimitDecision.Allowed.class);
        }).doesNotThrowAnyException();

        unreachableFactory.destroy();
    }
}

package dev.mekkaoui.shortener.cache;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisUrlCacheTest {

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
    private RedisUrlCache cache;
    private final Duration ttl = Duration.ofHours(24);
    private final Duration negativeTtl = Duration.ofSeconds(60);

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

        cache = new RedisUrlCache(redisTemplate, ttl, negativeTtl);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldPutAndGetSuccessfully() {
        cache.put("code123", "https://example.com/target");

        CacheLookup result = cache.get("code123");

        assertThat(result).isEqualTo(new CacheLookup.Hit("https://example.com/target"));
    }

    @Test
    void shouldReturnMissForAbsentKey() {
        CacheLookup result = cache.get("absent");

        assertThat(result).isEqualTo(new CacheLookup.Miss());
    }

    @Test
    void shouldSetKeyWithConfiguredTtl() {
        cache.put("codeWithTtl", "https://example.com/ttl");

        Long expireSeconds = redisTemplate.getExpire("url:codeWithTtl", TimeUnit.SECONDS);

        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isGreaterThan(0);
        assertThat(expireSeconds).isLessThanOrEqualTo(ttl.toSeconds());
    }

    @Test
    void shouldPutMissingAndReturnKnownMissing() {
        cache.putMissing("missing123");

        CacheLookup result = cache.get("missing123");

        assertThat(result).isEqualTo(new CacheLookup.KnownMissing());
    }

    @Test
    void shouldSetNegativeKeyWithConfiguredNegativeTtl() {
        cache.putMissing("negTtlCode");

        Long expireSeconds = redisTemplate.getExpire("url:negTtlCode", TimeUnit.SECONDS);

        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isGreaterThan(0);
        assertThat(expireSeconds).isLessThanOrEqualTo(negativeTtl.toSeconds());
    }

    @Test
    void shouldOverwriteNegativeCacheWhenLinkIsPut() {
        cache.putMissing("negCode");

        assertThat(cache.get("negCode")).isEqualTo(new CacheLookup.KnownMissing());

        cache.put("negCode", "https://example.com/created-afterwards");

        assertThat(cache.get("negCode")).isEqualTo(new CacheLookup.Hit("https://example.com/created-afterwards"));
    }

    @Test
    void shouldGracefullyDegradeWhenRedisIsUnreachable() {
        RedisStandaloneConfiguration unreachableConfig = new RedisStandaloneConfiguration("localhost", 65432);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .build();
        LettuceConnectionFactory unreachableFactory = new LettuceConnectionFactory(unreachableConfig, clientConfig);
        unreachableFactory.afterPropertiesSet();

        StringRedisTemplate unreachableTemplate = new StringRedisTemplate(unreachableFactory);
        unreachableTemplate.afterPropertiesSet();

        RedisUrlCache unreachableCache = new RedisUrlCache(unreachableTemplate, ttl, negativeTtl);

        assertThat(unreachableCache.get("anyCode")).isEqualTo(new CacheLookup.Miss());

        assertThatCode(() -> unreachableCache.put("anyCode", "https://example.com"))
                .doesNotThrowAnyException();

        assertThatCode(() -> unreachableCache.putMissing("anyCode"))
                .doesNotThrowAnyException();

        unreachableFactory.destroy();
    }
}

package dev.mekkaoui.shortener.cache;

import com.jayway.jsonpath.JsonPath;
import dev.mekkaoui.shortener.TestcontainersConfiguration;
import dev.mekkaoui.shortener.ratelimit.RateLimiter;
import dev.mekkaoui.shortener.ratelimit.RedisFixedWindowRateLimiter;
import dev.mekkaoui.shortener.analytics.BufferedClickRecorder;
import dev.mekkaoui.shortener.analytics.ClickRecorder;
import dev.mekkaoui.shortener.url.ShortUrl;
import dev.mekkaoui.shortener.url.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shortener.cache.enabled=true",
        "shortener.rate-limit.store=redis",
        "shortener.code.strategy=sequence"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class CacheEnabledEndToEndTest {

    private final MockMvc mockMvc;

    @MockitoSpyBean
    private ShortUrlRepository repository;

    @Autowired
    private UrlCache urlCache;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ClickRecorder clickRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    CacheEnabledEndToEndTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldBufferClicksOnRedirectAndFlushAggregatedCountsToPostgres() throws Exception {
        assertThat(clickRecorder).isInstanceOf(BufferedClickRecorder.class);
        BufferedClickRecorder bufferedRecorder = (BufferedClickRecorder) clickRecorder;

        // 1. Create a link via POST /api/urls
        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/click-stats-e2e\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");

        // 2. Perform 3 redirects (1st hits DB and populates cache if missed, subsequent hit Redis cache)
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());

        // 3. Perform one 404 request
        mockMvc.perform(get("/nonexistentCode")).andExpect(status().isNotFound());

        // 4. Force flush (deterministic verification without sleeping)
        bufferedRecorder.flush();

        // 5. Verify Postgres click_stats has count 3 for the code and nothing for 404
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Long count = jdbcTemplate.queryForObject(
                "select count from click_stats where code = ? and day = ?",
                Long.class,
                code,
                today
        );
        assertThat(count).isEqualTo(3L);

        Integer notFoundRows = jdbcTemplate.queryForObject(
                "select count(*) from click_stats where code = ?",
                Integer.class,
                "nonexistentCode"
        );
        assertThat(notFoundRows).isZero();
    }

    @Test
    void shouldRedirectFromCacheOnSecondRequestWithoutQueryingPostgres() throws Exception {
        assertThat(urlCache).isInstanceOf(RedisUrlCache.class);

        String code = uniqueCode();
        repository.saveAndFlush(new ShortUrl(code, "https://example.com/cached-e2e"));

        // First GET: miss in cache, queries Postgres
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/cached-e2e"));

        verify(repository, times(1)).findByCode(code);

        // Second GET: hit in cache, must not query Postgres
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/cached-e2e"));

        verify(repository, times(1)).findByCode(code);
    }

    @Test
    void shouldPopulateCacheOnCreateSoSubsequentRedirectDoesNotQueryDatabase() throws Exception {
        assertThat(urlCache).isInstanceOf(RedisUrlCache.class);

        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/write-on-create\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");
        assertThat(code).hasSize(7).matches("^[0-9A-Za-z]{7}$");

        // Redirect should immediately hit cache populated during create
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/write-on-create"));

        verify(repository, never()).findByCode(code);
    }

    @Test
    void shouldCreateShortUrlWithSequenceStrategyAndRedirect() throws Exception {
        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/sequence-e2e\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");
        assertThat(code).hasSize(7).matches("^[0-9A-Za-z]{7}$");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/sequence-e2e"));
    }

    @Test
    void shouldCacheNegativeLookupSoSecondMissDoesNotQueryDatabase() throws Exception {
        assertThat(urlCache).isInstanceOf(RedisUrlCache.class);

        String code = uniqueCode();

        // First GET: code not in DB, should query DB, return 404 ProblemDetail, and cache sentinel
        mockMvc.perform(get("/" + code))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Short URL not found for code: " + code));

        verify(repository, times(1)).findByCode(code);

        // Second GET: should be known-missing in cache, return identical 404 ProblemDetail without querying DB
        mockMvc.perform(get("/" + code))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Short URL not found for code: " + code));

        verify(repository, times(1)).findByCode(code);
    }

    @Test
    void shouldRateLimitUsingRedisStoreWhenConfigured() throws Exception {
        assertThat(rateLimiter).isInstanceOf(RedisFixedWindowRateLimiter.class);

        String clientIp = "198.51.100.77";
        String redisKey = "rl:" + clientIp;

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/urls")
                            .with(req -> {
                                req.setRemoteAddr(clientIp);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/redis-limit-" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/urls")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/redis-limit-11\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429));

        assertThat(redisTemplate.hasKey(redisKey)).isTrue();
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0L);
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("11");
    }

    private static String uniqueCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}

package dev.mekkaoui.shortener.cache;

import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import dev.mekkaoui.shortener.TestcontainersConfiguration;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies behavior when Redis becomes unreachable.
 *
 * Test order matters:
 * 1. Verify client options (connect timeout and REJECT_COMMANDS).
 * 2. Connect while Redis is UP and populate cache to establish an active connection.
 * 3. Stop Redis and assert fast failure under disconnected state.
 *
 * Note: The closed-port test without prior connection was dropped because Lettuce never queues
 * commands on an initially refused connection, failing fast in ~39 ms even without the fix.
 * Reproducing the command-queueing timeout bug requires an active connection that subsequently gets severed.
 */
@SpringBootTest(properties = {
        "shortener.cache.enabled=true",
        "spring.data.redis.timeout=5s"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisDownHealthTest {

    private static final RedisContainer REDIS = new RedisContainer("redis:8");

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    private final MockMvc mockMvc;

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    RedisDownHealthTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @Order(1)
    void shouldPreserveConnectTimeoutAndConfigureRejectCommands() {
        ClientOptions clientOptions = connectionFactory.getClientConfiguration()
                .getClientOptions()
                .orElseThrow();
        assertThat(clientOptions.getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(clientOptions.getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @Order(2)
    void shouldConnectAndCacheWhileRedisIsUp() throws Exception {
        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/live-test\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");

        // First redirect: miss, establishes Lettuce connection and puts in Redis
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/live-test"));

        // Second redirect: cache hit from live Redis
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/live-test"));
    }

    @Test
    @Order(3)
    void healthRemainsUpAndRedirectFailsFastWhenRedisStops() throws Exception {
        // Stop the Redis container without removing it (same as docker compose stop)
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).exec();

        // 1. Health assertion: actuator health remains UP
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Create another URL in Postgres
        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/stopped-redis-test\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");

        // Warm-up redirect while Redis is stopped
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/stopped-redis-test"));

        // Timed redirect: must fail fast (< 1s) instead of waiting for 5s get + 5s put timeouts
        long startNanos = System.nanoTime();
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/stopped-redis-test"));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(1000);
    }
}

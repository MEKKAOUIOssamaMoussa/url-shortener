package dev.mekkaoui.shortener.cache;

import com.jayway.jsonpath.JsonPath;
import dev.mekkaoui.shortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shortener.cache.enabled=true",
        "spring.data.redis.port=65432"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedisDownHealthTest {

    private final MockMvc mockMvc;

    RedisDownHealthTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void healthRemainsUpWhenRedisIsUnreachable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void redirectStillWorksWhenRedisIsUnreachable() throws Exception {
        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/fallback-test\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");

        long startNanos = System.nanoTime();
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/fallback-test"));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        System.out.println("Redirect duration with unreachable Redis: " + elapsedMillis + " ms");
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(0);
    }
}

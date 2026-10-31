package dev.mekkaoui.shortener;

import com.jayway.jsonpath.JsonPath;
import dev.mekkaoui.shortener.cache.NoOpUrlCache;
import dev.mekkaoui.shortener.cache.UrlCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShortenerApplicationTests {

    private final MockMvc mockMvc;

    ShortenerApplicationTests(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void contextLoads() {
    }

    @Test
    void shouldWireNoOpUrlCacheByDefault(@Autowired UrlCache urlCache) {
        assertThat(urlCache).isInstanceOf(NoOpUrlCache.class);
    }

    @Test
    void healthEndpointReturnsStatusUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldCreateAndRedirectShortUrlThroughRealDatabase() throws Exception {
        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/integration-test\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/integration-test"))
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/integration-test"));
    }

    @Test
    void shouldReturn404ProblemDetailForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldRateLimitPostApiUrlsAfterTenRequestsPerWindow() throws Exception {
        String clientIp = "198.51.100.42";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/urls")
                            .with(request -> {
                                request.setRemoteAddr(clientIp);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/ratelimit-" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/urls")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/ratelimit-11\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.instance").value("/api/urls"))
                .andExpect(jsonPath("$.detail").value(containsString("Too many requests. Please retry after")));
    }
}

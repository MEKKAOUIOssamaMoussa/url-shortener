package dev.mekkaoui.shortener.cache;

import com.jayway.jsonpath.JsonPath;
import dev.mekkaoui.shortener.TestcontainersConfiguration;
import dev.mekkaoui.shortener.url.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shortener.cache.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class CacheEnabledEndToEndTest {

    private final MockMvc mockMvc;

    @MockitoSpyBean
    private ShortUrlRepository repository;

    @Autowired
    private UrlCache urlCache;

    CacheEnabledEndToEndTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldRedirectFromCacheOnSecondRequestWithoutQueryingPostgres() throws Exception {
        assertThat(urlCache).isInstanceOf(RedisUrlCache.class);

        MvcResult postResult = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/cached-e2e\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String code = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");

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
}

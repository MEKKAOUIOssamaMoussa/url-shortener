package dev.mekkaoui.shortener.url;

import dev.mekkaoui.shortener.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShortUrlController.class)
@Import(GlobalExceptionHandler.class)
@EnableConfigurationProperties(ShortenerProperties.class)
class ShortUrlControllerTest {

    private final MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService shortUrlService;

    ShortUrlControllerTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldCreateShortUrlAndReturn201WithLocationAndBody() throws Exception {
        ShortUrl shortUrl = new ShortUrl("abc1234", "https://example.com/target");
        when(shortUrlService.create("https://example.com/target")).thenReturn(shortUrl);

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/target\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost:8080/abc1234"))
                .andExpect(jsonPath("$.code").value("abc1234"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/abc1234"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/target"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"url\":\"\"}",
            "{\"url\":\"   \"}",
            "{\"url\":\"ftp://example.com\"}",
            "{\"url\":\"javascript:alert(1)\"}",
            "{\"url\":\"data:text/html,test\"}",
            "{\"url\":\"/relative/path\"}"
    })
    void shouldReturn400ProblemDetailForInvalidUrls(String payload) throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldReturn400ProblemDetailForUrlTooLong() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(2048);
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + longUrl + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void shouldReturn400ProblemDetailForMalformedJsonBody() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldRedirect302WithLocationWhenCodeFound() throws Exception {
        when(shortUrlService.findTargetUrl("abc1234")).thenReturn("https://example.com/destination");

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/destination"))
                .andExpect(header().doesNotExist("Cache-Control"));
    }

    @Test
    void shouldReturn404ProblemDetailWhenCodeNotFound() throws Exception {
        when(shortUrlService.findTargetUrl("missing"))
                .thenThrow(new ShortUrlNotFoundException("Short URL not found for code: missing"));

        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Short URL Not Found"))
                .andExpect(jsonPath("$.detail").value("Short URL not found for code: missing"));
    }

    @Test
    void shouldReturn500ProblemDetailWhenCodeGenerationExhausted() throws Exception {
        when(shortUrlService.create(anyString()))
                .thenThrow(new CodeGenerationExhaustedException("Exhausted retries generating unique short URL code"));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Unable to generate unique short URL"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Exhausted retries"))));
    }

    @Test
    void shouldReturn500ProblemDetailWithGenericDetailForUnexpectedException() throws Exception {
        when(shortUrlService.findTargetUrl("abc1234"))
                .thenThrow(new RuntimeException("Sensitive database stacktrace info"));

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Sensitive database"))));
    }
}

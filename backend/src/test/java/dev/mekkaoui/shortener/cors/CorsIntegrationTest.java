package dev.mekkaoui.shortener.cors;

import dev.mekkaoui.shortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsIntegrationTest {

    private final MockMvc mockMvc;

    CorsIntegrationTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldReturnCorsHeadersOnPreflightFromAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/urls")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("OPTIONS")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Content-Type")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
    }

    @Test
    void shouldRejectPreflightFromDisallowedOriginWithForbiddenStatus() throws Exception {
        mockMvc.perform(options("/api/urls")
                        .header(HttpHeaders.ORIGIN, "http://unauthorized-domain.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void shouldReturn201WithCorsAndExposedLocationHeaderOnValidPostFromAllowedOrigin() throws Exception {
        String clientIp = "198.51.100.102";

        mockMvc.perform(post("/api/urls")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/cors-success\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Location")))
                .andExpect(header().exists(HttpHeaders.LOCATION));
    }

    @Test
    void shouldDecorate429TooManyRequestsWithCorsHeadersAndExposeRetryAfter() throws Exception {
        String clientIp = "198.51.100.103";

        // Exhaust the 10 requests allowed within the window
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/urls")
                            .with(req -> {
                                req.setRemoteAddr(clientIp);
                                return req;
                            })
                            .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/cors-rate-limit-" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        // 11th request must receive HTTP 429 AND still carry CORS headers because CorsFilter precedes RateLimitFilter
        mockMvc.perform(post("/api/urls")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/cors-rate-limit-11\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Retry-After")))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"));
    }

    @Test
    void preflightsMustNotConsumeRateLimitTokens() throws Exception {
        String clientIp = "198.51.100.104";

        // Perform 10 preflight OPTIONS requests from this IP
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(options("/api/urls")
                            .with(req -> {
                                req.setRemoteAddr(clientIp);
                                return req;
                            })
                            .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                    .andExpect(status().isOk());
        }

        // Now perform 10 POST requests from the same IP: all 10 must succeed, proving preflights consumed 0 tokens
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/urls")
                            .with(req -> {
                                req.setRemoteAddr(clientIp);
                                return req;
                            })
                            .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/preflight-rate-limit-" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        // The 11th POST must be rejected with 429
        mockMvc.perform(post("/api/urls")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/preflight-rate-limit-11\"}"))
                .andExpect(status().isTooManyRequests());
    }
}

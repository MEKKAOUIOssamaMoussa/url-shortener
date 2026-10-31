package dev.mekkaoui.shortener.ratelimit;

import dev.mekkaoui.shortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shortener.rate-limit.window=1h")
@Import(TestcontainersConfiguration.class)
class ClientIpResolutionEndToEndTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private HttpResponse<String> postUrl(String xForwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/urls"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", xForwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/client-ip-test\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void independence_differentClientIpsHaveIndependentLimits() throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> res = postUrl("198.51.100.30");
            assertThat(res.statusCode()).as("request %d for 198.51.100.30", i).isEqualTo(201);
        }
        HttpResponse<String> resDifferentIp = postUrl("198.51.100.31");
        assertThat(resDifferentIp.statusCode()).isEqualTo(201);
    }

    @Test
    void limit_enforcedPerClientIpBehindProxy() throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> res = postUrl("6.6.6.6, 198.51.100.40");
            assertThat(res.statusCode()).as("request %d for 198.51.100.40", i).isEqualTo(201);
        }
        HttpResponse<String> res11 = postUrl("6.6.6.6, 198.51.100.40");
        assertThat(res11.statusCode()).isEqualTo(429);
    }

    @Test
    void spoof_leftmostClientIpDoesNotBypassRateLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> res = postUrl("198.51.100.50");
            assertThat(res.statusCode()).as("request %d for 198.51.100.50", i).isEqualTo(201);
        }
        HttpResponse<String> resSpoofed = postUrl("203.0.113.99, 198.51.100.50");
        assertThat(resSpoofed.statusCode()).isEqualTo(429);
    }
}

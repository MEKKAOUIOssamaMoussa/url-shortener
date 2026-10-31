package dev.mekkaoui.shortener.ratelimit;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        objectMapper = JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
        filter = new RateLimitFilter(rateLimiter, objectMapper);
    }

    @Test
    void shouldNotFilterGetRequests() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(rateLimiter);
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void shouldNotFilterPostRequestsToOtherPaths() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(rateLimiter);
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    void shouldAllowRequestWhenLimiterReturnsAllowed() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/urls");
        request.setRemoteAddr("192.0.2.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimiter.tryAcquire("192.0.2.1")).thenReturn(new RateLimitDecision.Allowed());

        filter.doFilter(request, response, filterChain);

        verify(rateLimiter).tryAcquire("192.0.2.1");
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void shouldReturn429WhenLimiterReturnsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/urls");
        request.setRemoteAddr("192.0.2.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimiter.tryAcquire("192.0.2.1")).thenReturn(new RateLimitDecision.Rejected(Duration.ofSeconds(45)));

        filter.doFilter(request, response, filterChain);

        verify(rateLimiter).tryAcquire("192.0.2.1");
        assertThat(filterChain.getRequest()).isNull(); // Chain not called
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        String json = response.getContentAsString();
        assertThat(JsonPath.<Integer>read(json, "$.status")).isEqualTo(429);
        assertThat(JsonPath.<String>read(json, "$.title")).isEqualTo("Too Many Requests");
        assertThat(JsonPath.<String>read(json, "$.detail")).isEqualTo("Too many requests. Please retry after 45 seconds.");
        assertThat(JsonPath.<String>read(json, "$.instance")).isEqualTo("/api/urls");
    }

    @Test
    void shouldEnforceMinimumOneSecondRetryAfterWhenRejectedDurationIsSubsecond() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/urls");
        request.setRemoteAddr("192.0.2.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimiter.tryAcquire(anyString())).thenReturn(new RateLimitDecision.Rejected(Duration.ofMillis(200)));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

        String json = response.getContentAsString();
        assertThat(JsonPath.<String>read(json, "$.detail")).isEqualTo("Too many requests. Please retry after 1 seconds.");
    }
}

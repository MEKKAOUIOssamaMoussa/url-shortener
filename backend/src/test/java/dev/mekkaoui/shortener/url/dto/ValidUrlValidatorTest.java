package dev.mekkaoui.shortener.url.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ValidUrlValidatorTest {

    private final ValidUrlValidator validator = new ValidUrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "https://example.com",
            "https://example.com/some/long/path",
            "http://example.com:8080/path?query=1#frag",
            "HTTP://EXAMPLE.COM/UPPERCASE"
    })
    void shouldAcceptValidHttpAndHttpsUrls(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @Test
    void shouldAcceptNullAndBlankDelegatingToNotBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/plain;base64,SGVsbG8=",
            "ftp://example.com/resource",
            "/relative/path",
            "relative/path",
            "http:///path/without/host",
            "https://",
            "http://",
            "http://bad url with spaces",
            "not a url at all"
    })
    void shouldRejectInvalidUrls(String url) {
        assertThat(validator.isValid(url, null)).isFalse();
    }
}

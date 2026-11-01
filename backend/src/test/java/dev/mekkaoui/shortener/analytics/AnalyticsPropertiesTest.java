package dev.mekkaoui.shortener.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsPropertiesTest {

    @Test
    void shouldBindDefaultValues() {
        AnalyticsProperties properties = bind(Map.of());

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.flushInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.flushThreshold()).isEqualTo(100);
        assertThat(properties.maxPendingKeys()).isEqualTo(10000);
    }

    @Test
    void shouldBindCustomValues() {
        AnalyticsProperties properties = bind(Map.of(
                "shortener.analytics.enabled", "false",
                "shortener.analytics.flush-interval", "10s",
                "shortener.analytics.flush-threshold", "50",
                "shortener.analytics.max-pending-keys", "5000"
        ));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.flushInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.flushThreshold()).isEqualTo(50);
        assertThat(properties.maxPendingKeys()).isEqualTo(5000);
    }

    @Test
    void shouldRejectNonPositiveFlushInterval() {
        assertThatThrownBy(() -> new AnalyticsProperties(true, Duration.ZERO, 100, 10000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flush-interval must be positive");

        assertThatThrownBy(() -> new AnalyticsProperties(true, Duration.ofSeconds(-1), 100, 10000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flush-interval must be positive");
    }

    @Test
    void shouldRejectNonPositiveFlushThreshold() {
        assertThatThrownBy(() -> new AnalyticsProperties(true, Duration.ofSeconds(5), 0, 10000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flush-threshold must be positive");

        assertThatThrownBy(() -> new AnalyticsProperties(true, Duration.ofSeconds(5), -5, 10000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flush-threshold must be positive");
    }

    @Test
    void shouldRejectNonPositiveMaxPendingKeys() {
        assertThatThrownBy(() -> new AnalyticsProperties(true, Duration.ofSeconds(5), 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-pending-keys must be positive");

        assertThatThrownBy(() -> new AnalyticsProperties(true, Duration.ofSeconds(5), 100, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-pending-keys must be positive");
    }

    private AnalyticsProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("shortener.analytics", AnalyticsProperties.class);
    }
}

package dev.mekkaoui.shortener.analytics;

import dev.mekkaoui.shortener.ShortenerApplication;
import dev.mekkaoui.shortener.url.ShortUrl;
import dev.mekkaoui.shortener.url.ShortUrlRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that pending clicks buffered in memory are flushed to PostgreSQL
 * when the Spring ApplicationContext closes via graceful SmartLifecycle shutdown.
 */
class ShutdownFlushTest {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void shouldFlushPendingClicksOnApplicationContextShutdown() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(ShortenerApplication.class)
                .properties(
                        "server.port=0",
                        "DB_URL=" + postgres.getJdbcUrl(),
                        "DB_USERNAME=" + postgres.getUsername(),
                        "DB_PASSWORD=" + postgres.getPassword(),
                        "shortener.analytics.enabled=true",
                        "shortener.analytics.flush-interval=1h",
                        "shortener.analytics.flush-threshold=1000",
                        "shortener.cache.enabled=false",
                        "shortener.rate-limit.enabled=false"
                )
                .run();

        try {
            ShortUrlRepository repository = context.getBean(ShortUrlRepository.class);
            ClickRecorder recorder = context.getBean(ClickRecorder.class);

            repository.saveAndFlush(new ShortUrl("shutdownCode", "https://example.com/shutdown-target"));

            // Record 3 clicks (threshold is 1000 and interval is 1h, so clicks remain in memory)
            recorder.record("shutdownCode");
            recorder.record("shutdownCode");
            recorder.record("shutdownCode");

            // Verify click_stats is initially empty
            try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 PreparedStatement ps = conn.prepareStatement("select count(*) from click_stats where code = ?")) {
                ps.setString(1, "shutdownCode");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
            }

            // Close ApplicationContext: SmartLifecycle at phase 2_147_480_575 executes the final flush
            // before the DataSource is closed during bean destruction
            context.close();

            // Verify that the shutdown flush wrote the 3 clicks to PostgreSQL before DataSource closure
            try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 PreparedStatement ps = conn.prepareStatement("select count from click_stats where code = ?")) {
                ps.setString(1, "shutdownCode");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("count")).isEqualTo(3L);
                }
            }
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }
}

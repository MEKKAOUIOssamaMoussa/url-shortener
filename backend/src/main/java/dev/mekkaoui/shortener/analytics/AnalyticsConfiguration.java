package dev.mekkaoui.shortener.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shortener.analytics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ClickStatsWriter clickStatsWriter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new ClickStatsWriter(jdbcTemplate, transactionManager);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shortener.analytics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public BufferedClickRecorder bufferedClickRecorder(ClickStatsWriter writer, AnalyticsProperties properties) {
        return new BufferedClickRecorder(writer, properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "shortener.analytics", name = "enabled", havingValue = "false")
    public ClickRecorder noOpClickRecorder() {
        return new NoOpClickRecorder();
    }
}

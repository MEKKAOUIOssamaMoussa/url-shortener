package dev.mekkaoui.shortener.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindDefaultStoreToMemory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.store()).isEqualTo(RateLimitStore.MEMORY);
        });
    }

    @Test
    void shouldBindExplicitRedisStoreCaseInsensitively() {
        runner.withPropertyValues("shortener.rate-limit.store=redis")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    assertThat(properties.store()).isEqualTo(RateLimitStore.REDIS);
                });

        runner.withPropertyValues("shortener.rate-limit.store=REDIS")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    assertThat(properties.store()).isEqualTo(RateLimitStore.REDIS);
                });
    }

    @Test
    void shouldFailStartupWhenStorePropertyIsInvalid() {
        runner.withPropertyValues("shortener.rate-limit.store=invalid_store")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(failure.getMessage()).contains("Could not bind properties to 'RateLimitProperties'");
                });
    }
}

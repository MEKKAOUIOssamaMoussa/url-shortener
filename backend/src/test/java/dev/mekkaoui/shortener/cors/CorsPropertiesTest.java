package dev.mekkaoui.shortener.cors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindSingleOriginFromProperty() {
        runner.withPropertyValues("shortener.cors.allowed-origins=http://localhost:4200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CorsProperties properties = context.getBean(CorsProperties.class);
                    assertThat(properties.allowedOrigins()).containsExactly("http://localhost:4200");
                });
    }

    @Test
    void shouldBindCommaSeparatedValuesToMultipleOrigins() {
        runner.withPropertyValues("shortener.cors.allowed-origins=https://my-app.azurestaticapps.net,http://localhost:4200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CorsProperties properties = context.getBean(CorsProperties.class);
                    assertThat(properties.allowedOrigins()).containsExactly(
                            "https://my-app.azurestaticapps.net",
                            "http://localhost:4200"
                    );
                });
    }
}

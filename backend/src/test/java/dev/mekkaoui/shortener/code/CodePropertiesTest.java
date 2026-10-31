package dev.mekkaoui.shortener.code;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CodePropertiesTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CodeProperties.class)
    static class TestConfig {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BeansConfig {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindDefaultStrategyToRandomAndDefaultSecret() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CodeProperties properties = context.getBean(CodeProperties.class);
            assertThat(properties.strategy()).isEqualTo(CodeStrategy.RANDOM);
            assertThat(properties.secret()).isEqualTo("dev-secret-key-must-be-changed-in-production-32-chars");
        });
    }

    @Test
    void shouldBindExplicitSequenceStrategyCaseInsensitively() {
        runner.withPropertyValues("shortener.code.strategy=sequence")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CodeProperties properties = context.getBean(CodeProperties.class);
                    assertThat(properties.strategy()).isEqualTo(CodeStrategy.SEQUENCE);
                });

        runner.withPropertyValues("shortener.code.strategy=SEQUENCE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CodeProperties properties = context.getBean(CodeProperties.class);
                    assertThat(properties.strategy()).isEqualTo(CodeStrategy.SEQUENCE);
                });
    }

    @Test
    void shouldFailStartupWhenSecretIsTooShort() {
        runner.withPropertyValues("shortener.code.secret=short")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(failure.getMessage()).contains("Could not bind properties to 'CodeProperties'");

                    Throwable rootCause = failure;
                    while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }
                    assertThat(rootCause).isInstanceOf(IllegalArgumentException.class);
                    assertThat(rootCause.getMessage()).contains("must be at least 16 characters long");
                });
    }

    @Test
    void shouldFailStartupWhenStrategyIsInvalid() {
        runner.withPropertyValues("shortener.code.strategy=invalid_strategy")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(failure.getMessage()).contains("Could not bind properties to 'CodeProperties'");
                });
    }

    @Test
    void shouldConfigureRandomCodeGeneratorByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(CodeConfiguration.class, BeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CodeGenerator.class);
                    assertThat(context.getBean(CodeGenerator.class)).isInstanceOf(RandomCodeGenerator.class);
                });
    }

    @Test
    void shouldConfigureSequenceCodeGeneratorWhenSelected() {
        new ApplicationContextRunner()
                .withUserConfiguration(CodeConfiguration.class, BeansConfig.class)
                .withPropertyValues("shortener.code.strategy=sequence")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CodeGenerator.class);
                    assertThat(context.getBean(CodeGenerator.class)).isInstanceOf(SequenceCodeGenerator.class);
                });
    }
}

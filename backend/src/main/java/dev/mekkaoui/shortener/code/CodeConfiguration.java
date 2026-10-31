package dev.mekkaoui.shortener.code;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodeProperties.class)
public class CodeConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shortener.code", name = "strategy", havingValue = "random", matchIfMissing = true)
    public CodeGenerator randomCodeGenerator() {
        return new RandomCodeGenerator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "shortener.code", name = "strategy", havingValue = "sequence")
    public CodeGenerator sequenceCodeGenerator(JdbcTemplate jdbcTemplate, CodeProperties properties) {
        FeistelCipher cipher = new FeistelCipher(properties.secret());
        return new SequenceCodeGenerator(jdbcTemplate, cipher);
    }
}

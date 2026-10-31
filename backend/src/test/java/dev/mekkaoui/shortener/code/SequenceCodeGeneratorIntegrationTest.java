package dev.mekkaoui.shortener.code;

import dev.mekkaoui.shortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class SequenceCodeGeneratorIntegrationTest {

    private final SequenceCodeGenerator generator;

    SequenceCodeGeneratorIntegrationTest(@Autowired JdbcTemplate jdbcTemplate) {
        FeistelCipher cipher = new FeistelCipher("dev-secret-key-must-be-changed-in-production-32-chars");
        this.generator = new SequenceCodeGenerator(jdbcTemplate, cipher);
    }

    @Test
    void shouldGenerateDistinctSevenCharacterBase62CodesOnConsecutiveCalls() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String code = generator.generate();
            assertThat(code)
                    .hasSize(7)
                    .matches("^[0-9A-Za-z]{7}$");
            codes.add(code);
        }
        assertThat(codes).hasSize(20);
    }
}

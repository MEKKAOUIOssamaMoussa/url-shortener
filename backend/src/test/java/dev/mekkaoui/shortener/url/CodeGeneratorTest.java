package dev.mekkaoui.shortener.url;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void shouldGenerateSevenCharacterBase62Code() {
        for (int i = 0; i < 100; i++) {
            String code = generator.generate();
            assertThat(code)
                    .hasSize(7)
                    .matches("^[0-9A-Za-z]{7}$");
        }
    }
}

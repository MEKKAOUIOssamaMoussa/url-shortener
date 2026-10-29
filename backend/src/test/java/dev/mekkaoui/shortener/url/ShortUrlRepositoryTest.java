package dev.mekkaoui.shortener.url;

import dev.mekkaoui.shortener.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ShortUrlRepositoryTest {

    private final ShortUrlRepository repository;

    ShortUrlRepositoryTest(@Autowired ShortUrlRepository repository) {
        this.repository = repository;
    }

    @Test
    void shouldSaveAndFindByCode() {
        ShortUrl shortUrl = new ShortUrl("abc123", "https://example.com");
        repository.save(shortUrl);

        Optional<ShortUrl> found = repository.findByCode("abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("abc123");
        assertThat(found.get().getTargetUrl()).isEqualTo("https://example.com");
    }

    @Test
    void shouldReturnEmptyWhenCodeNotFound() {
        Optional<ShortUrl> found = repository.findByCode("nonexistent");

        assertThat(found).isEmpty();
    }

    @Test
    void shouldThrowDataIntegrityViolationOnDuplicateCode() {
        repository.saveAndFlush(new ShortUrl("dup123", "https://first.com"));

        assertThatThrownBy(() -> repository.saveAndFlush(new ShortUrl("dup123", "https://second.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldPopulateCreatedAtOnSave() {
        ShortUrl saved = repository.save(new ShortUrl("time123", "https://example.com"));

        assertThat(saved.getCreatedAt()).isNotNull();
    }
}

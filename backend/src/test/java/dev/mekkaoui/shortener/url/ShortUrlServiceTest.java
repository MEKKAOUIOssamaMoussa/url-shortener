package dev.mekkaoui.shortener.url;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortUrlServiceTest {

    private final CodeGenerator codeGenerator = mock(CodeGenerator.class);
    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final ShortUrlService service = new ShortUrlService(codeGenerator, repository);

    @Test
    void shouldCreateSuccessfullyOnFirstAttempt() {
        when(codeGenerator.generate()).thenReturn("code123");
        ShortUrl saved = new ShortUrl("code123", "https://example.com");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenReturn(saved);

        ShortUrl result = service.create("https://example.com");

        assertThat(result.getCode()).isEqualTo("code123");
        verify(codeGenerator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldRetryAndSucceedWhenUniqueConstraintViolated() {
        when(codeGenerator.generate()).thenReturn("dup123", "uniq123");
        DataIntegrityViolationException collision = createConstraintViolationException("uq_short_url_code");
        ShortUrl saved = new ShortUrl("uniq123", "https://example.com");

        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(collision)
                .thenReturn(saved);

        ShortUrl result = service.create("https://example.com");

        assertThat(result.getCode()).isEqualTo("uniq123");
        verify(codeGenerator, times(2)).generate();
        verify(repository, times(2)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldNotRetryOnDifferentConstraintViolation() {
        when(codeGenerator.generate()).thenReturn("code123");
        DataIntegrityViolationException otherViolation = createConstraintViolationException("uq_other_constraint");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(otherViolation);

        assertThatThrownBy(() -> service.create("https://example.com"))
                .isSameAs(otherViolation);

        verify(codeGenerator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldThrowExhaustedExceptionAfterMaxAttempts() {
        when(codeGenerator.generate()).thenReturn("dup1", "dup2", "dup3");
        DataIntegrityViolationException collision = createConstraintViolationException("uq_short_url_code");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(collision);

        assertThatThrownBy(() -> service.create("https://example.com"))
                .isInstanceOf(CodeGenerationExhaustedException.class)
                .hasMessageContaining("Exhausted retries");

        verify(codeGenerator, times(3)).generate();
        verify(repository, times(3)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldFindByCodeWhenExists() {
        ShortUrl shortUrl = new ShortUrl("abc1234", "https://example.com");
        when(repository.findByCode("abc1234")).thenReturn(Optional.of(shortUrl));

        ShortUrl found = service.findByCode("abc1234");

        assertThat(found).isEqualTo(shortUrl);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenCodeDoesNotExist() {
        when(repository.findByCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByCode("missing"))
                .isInstanceOf(ShortUrlNotFoundException.class)
                .hasMessageContaining("missing");
    }

    private static DataIntegrityViolationException createConstraintViolationException(String constraintName) {
        SQLException sqlException = new SQLException("duplicate key", "23505");
        ConstraintViolationException cve = new ConstraintViolationException("duplicate", sqlException, constraintName);
        return new DataIntegrityViolationException("Data integrity violation", cve);
    }
}

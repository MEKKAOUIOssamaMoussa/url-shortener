package dev.mekkaoui.shortener.url;

import dev.mekkaoui.shortener.cache.UrlCache;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortUrlServiceTest {

    private final CodeGenerator codeGenerator = mock(CodeGenerator.class);
    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final UrlCache urlCache = mock(UrlCache.class);
    private final ShortUrlService service = new ShortUrlService(codeGenerator, repository, urlCache);

    @Test
    void shouldCreateSuccessfullyOnFirstAttemptAndPutInCache() {
        when(codeGenerator.generate()).thenReturn("code123");
        ShortUrl saved = new ShortUrl("code123", "https://example.com");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenReturn(saved);

        ShortUrl result = service.create("https://example.com");

        assertThat(result.getCode()).isEqualTo("code123");
        verify(codeGenerator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
        verify(urlCache, times(1)).put("code123", "https://example.com");
    }

    @Test
    void shouldRetryAndSucceedWhenUniqueConstraintViolatedAndPutOnlyFinalCodeInCache() {
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
        verify(urlCache, times(1)).put("uniq123", "https://example.com");
        verify(urlCache, never()).put(eq("dup123"), anyString());
    }

    @Test
    void shouldNotRetryOnDifferentConstraintViolationAndNeverPutInCache() {
        when(codeGenerator.generate()).thenReturn("code123");
        DataIntegrityViolationException otherViolation = createConstraintViolationException("uq_other_constraint");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(otherViolation);

        assertThatThrownBy(() -> service.create("https://example.com"))
                .isSameAs(otherViolation);

        verify(codeGenerator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
        verify(urlCache, never()).put(anyString(), anyString());
    }

    @Test
    void shouldThrowExhaustedExceptionAfterMaxAttemptsAndNeverPutInCache() {
        when(codeGenerator.generate()).thenReturn("dup1", "dup2", "dup3");
        DataIntegrityViolationException collision = createConstraintViolationException("uq_short_url_code");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(collision);

        assertThatThrownBy(() -> service.create("https://example.com"))
                .isInstanceOf(CodeGenerationExhaustedException.class)
                .hasMessageContaining("Exhausted retries");

        verify(codeGenerator, times(3)).generate();
        verify(repository, times(3)).saveAndFlush(any(ShortUrl.class));
        verify(urlCache, never()).put(anyString(), anyString());
    }

    @Test
    void shouldReturnCachedTargetUrlOnCacheHitWithoutTouchingRepository() {
        when(urlCache.get("hit123")).thenReturn(Optional.of("https://example.com/cached"));

        String targetUrl = service.findTargetUrl("hit123");

        assertThat(targetUrl).isEqualTo("https://example.com/cached");
        verify(urlCache).get("hit123");
        verifyNoInteractions(repository);
    }

    @Test
    void shouldReadFromRepositoryAndPutInCacheOnCacheMiss() {
        when(urlCache.get("miss123")).thenReturn(Optional.empty());
        ShortUrl saved = new ShortUrl("miss123", "https://example.com/from-db");
        when(repository.findByCode("miss123")).thenReturn(Optional.of(saved));

        String targetUrl = service.findTargetUrl("miss123");

        assertThat(targetUrl).isEqualTo("https://example.com/from-db");
        verify(urlCache).get("miss123");
        verify(repository, times(1)).findByCode("miss123");
        verify(urlCache).put("miss123", "https://example.com/from-db");
    }

    @Test
    void shouldThrowNotFoundExceptionAndNeverPutWhenCodeNotFound() {
        when(urlCache.get("unknown")).thenReturn(Optional.empty());
        when(repository.findByCode("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findTargetUrl("unknown"))
                .isInstanceOf(ShortUrlNotFoundException.class)
                .hasMessageContaining("unknown");

        verify(urlCache).get("unknown");
        verify(repository, times(1)).findByCode("unknown");
        verify(urlCache, never()).put(anyString(), anyString());
    }

    private static DataIntegrityViolationException createConstraintViolationException(String constraintName) {
        SQLException sqlException = new SQLException("duplicate key", "23505");
        ConstraintViolationException cve = new ConstraintViolationException("duplicate", sqlException, constraintName);
        return new DataIntegrityViolationException("Data integrity violation", cve);
    }
}

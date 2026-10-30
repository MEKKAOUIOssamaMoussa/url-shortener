package dev.mekkaoui.shortener.url;

import dev.mekkaoui.shortener.cache.CacheLookup;
import dev.mekkaoui.shortener.cache.UrlCache;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ShortUrlService {

    static final int MAX_ATTEMPTS = 3;
    static final String UNIQUE_CODE_CONSTRAINT = "uq_short_url_code";

    private final CodeGenerator codeGenerator;
    private final ShortUrlRepository repository;
    private final UrlCache urlCache;

    public ShortUrlService(CodeGenerator codeGenerator, ShortUrlRepository repository, UrlCache urlCache) {
        this.codeGenerator = codeGenerator;
        this.repository = repository;
        this.urlCache = urlCache;
    }

    public ShortUrl create(String targetUrl) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            try {
                ShortUrl saved = repository.saveAndFlush(new ShortUrl(code, targetUrl));
                urlCache.put(saved.getCode(), saved.getTargetUrl());
                return saved;
            } catch (DataIntegrityViolationException ex) {
                if (isUniqueCodeViolation(ex)) {
                    if (attempt < MAX_ATTEMPTS) {
                        continue;
                    }
                    throw new CodeGenerationExhaustedException("Exhausted retries generating unique short URL code", ex);
                }
                throw ex;
            }
        }
        throw new CodeGenerationExhaustedException("Exhausted retries generating unique short URL code");
    }

    public String findTargetUrl(String code) {
        CacheLookup lookup = urlCache.get(code);
        return switch (lookup) {
            case CacheLookup.Hit(String targetUrl) -> targetUrl;
            case CacheLookup.KnownMissing() -> throw new ShortUrlNotFoundException("Short URL not found for code: " + code);
            case CacheLookup.Miss() -> {
                Optional<ShortUrl> entity = repository.findByCode(code);
                if (entity.isEmpty()) {
                    urlCache.putMissing(code);
                    throw new ShortUrlNotFoundException("Short URL not found for code: " + code);
                }
                String targetUrl = entity.get().getTargetUrl();
                urlCache.put(code, targetUrl);
                yield targetUrl;
            }
        };
    }

    static boolean isUniqueCodeViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve) {
                return UNIQUE_CODE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        return false;
    }
}

package dev.mekkaoui.shortener.cache;

/**
 * Cache for short URL code to target URL mappings.
 * <p>
 * Implementations of this interface must NEVER throw exceptions
 * for cache failures (e.g. timeout, connection error, serialization failure).
 * Cache operations are strictly an optimization and degrade gracefully to a miss or no-op.
 */
public interface UrlCache {

    CacheLookup get(String code);

    void put(String code, String targetUrl);

    void putMissing(String code);
}

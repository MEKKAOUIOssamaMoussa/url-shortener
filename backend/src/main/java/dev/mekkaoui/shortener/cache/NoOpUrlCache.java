package dev.mekkaoui.shortener.cache;

public class NoOpUrlCache implements UrlCache {

    @Override
    public CacheLookup get(String code) {
        return new CacheLookup.Miss();
    }

    @Override
    public void put(String code, String targetUrl) {
    }

    @Override
    public void putMissing(String code) {
    }
}

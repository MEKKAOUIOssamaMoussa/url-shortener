package dev.mekkaoui.shortener.cache;

import java.util.Optional;

public class NoOpUrlCache implements UrlCache {

    @Override
    public Optional<String> get(String code) {
        return Optional.empty();
    }

    @Override
    public void put(String code, String targetUrl) {
    }
}

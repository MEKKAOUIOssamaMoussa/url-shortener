package dev.mekkaoui.shortener.cache;

import java.util.Objects;

public sealed interface CacheLookup permits CacheLookup.Hit, CacheLookup.KnownMissing, CacheLookup.Miss {

    record Hit(String targetUrl) implements CacheLookup {
        public Hit {
            Objects.requireNonNull(targetUrl, "targetUrl must not be null");
        }
    }

    record KnownMissing() implements CacheLookup {}

    record Miss() implements CacheLookup {}
}

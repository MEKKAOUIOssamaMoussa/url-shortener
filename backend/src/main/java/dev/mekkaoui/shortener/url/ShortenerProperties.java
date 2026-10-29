package dev.mekkaoui.shortener.url;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") String baseUrl
) {
    public ShortenerProperties {
        if (baseUrl != null) {
            baseUrl = baseUrl.replaceAll("/+$", "");
        }
    }
}

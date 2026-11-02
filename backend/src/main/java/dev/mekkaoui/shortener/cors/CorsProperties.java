package dev.mekkaoui.shortener.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "shortener.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
}

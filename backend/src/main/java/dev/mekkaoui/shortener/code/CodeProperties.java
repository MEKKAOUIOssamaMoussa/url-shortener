package dev.mekkaoui.shortener.code;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shortener.code")
public record CodeProperties(
        @DefaultValue("RANDOM") CodeStrategy strategy,
        @DefaultValue("dev-secret-key-must-be-changed-in-production-32-chars") String secret
) {

    public static final int MIN_SECRET_LENGTH = 16;

    public CodeProperties {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "shortener.code.secret must be at least " + MIN_SECRET_LENGTH + " characters long, got: "
                            + (secret == null ? "null" : secret.length() + " characters")
            );
        }
    }
}

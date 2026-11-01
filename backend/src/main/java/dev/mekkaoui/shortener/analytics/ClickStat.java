package dev.mekkaoui.shortener.analytics;

import java.time.LocalDate;
import java.util.Objects;

public record ClickStat(String code, LocalDate day, long count) {

    public ClickStat {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(day, "day must not be null");
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
    }
}

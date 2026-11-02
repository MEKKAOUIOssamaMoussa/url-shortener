package dev.mekkaoui.shortener.analytics.dto;

import java.time.LocalDate;

public record DailyClickStat(
        LocalDate day,
        long count
) {
}

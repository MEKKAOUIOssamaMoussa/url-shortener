package dev.mekkaoui.shortener.analytics.dto;

import java.util.List;

public record ClickStatsResponse(
        String code,
        long totalClicks,
        List<DailyClickStat> daily
) {
}

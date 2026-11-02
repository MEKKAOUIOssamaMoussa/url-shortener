package dev.mekkaoui.shortener.analytics;

import dev.mekkaoui.shortener.analytics.dto.ClickStatsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST controller providing aggregated click statistics for short URL codes.
 *
 * Exposes GET /api/urls/{code}/stats. Requests to this endpoint are not rate-limited
 * (unlike POST /api/urls) and are not recorded as clicks.
 */
@RestController
public class ClickStatsController {

    private final ClickStatsReader clickStatsReader;

    public ClickStatsController(ClickStatsReader clickStatsReader) {
        this.clickStatsReader = Objects.requireNonNull(clickStatsReader, "clickStatsReader must not be null");
    }

    @GetMapping("/api/urls/{code:[0-9A-Za-z]{1,16}}/stats")
    public ResponseEntity<ClickStatsResponse> getStats(@PathVariable String code) {
        ClickStatsResponse stats = clickStatsReader.getStats(code);
        return ResponseEntity.ok(stats);
    }
}

package dev.mekkaoui.shortener.analytics;

import dev.mekkaoui.shortener.analytics.dto.ClickStatsResponse;
import dev.mekkaoui.shortener.analytics.dto.DailyClickStat;
import dev.mekkaoui.shortener.url.ShortUrlNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Direct JDBC reader for aggregated click statistics from PostgreSQL.
 *
 * Bypasses JPA/Hibernate because click_stats is an append-and-aggregate table
 * written via atomic batch upserts. Read requirements are pure aggregations (SUM) and
 * time-series queries (ORDER BY day DESC LIMIT 30) that do not require entity
 * dirty-checking, first-level caching, or identity management.
 *
 * Click counts are eventually consistent, lagging behind memory buffer flushes
 * by up to the flush interval (default 5 seconds) and lossy by design (at-most-once delivery).
 */
public class ClickStatsReader {

    private static final String CHECK_CODE_EXISTS_SQL =
            "select exists(select 1 from short_url where code = ?)";

    private static final String QUERY_TOTAL_CLICKS_SQL =
            "select coalesce(sum(count), 0) from click_stats where code = ?";

    private static final String QUERY_DAILY_CLICKS_SQL =
            "select day, count from click_stats where code = ? order by day desc limit 30";

    private final JdbcTemplate jdbcTemplate;

    public ClickStatsReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Retrieves aggregated click statistics for the specified short URL code.
     *
     * Executed inside a single transaction with {@code @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)}.
     * Under PostgreSQL's default {@code READ COMMITTED} isolation, every SQL statement sees a new snapshot taken at the start
     * of that statement. If a background flusher commits a new batch of clicks between the {@code sum(count)} query and the
     * {@code select day, count} query, {@code totalClicks} would diverge from the sum of the {@code daily} rows returned.
     * With {@code REPEATABLE READ}, PostgreSQL freezes an MVCC snapshot at the start of the first query in the transaction,
     * guaranteeing that the total count and the daily rows are computed from an identical, self-consistent database state.
     *
     * Callers (e.g., {@link ClickStatsController}) invoke this method through the injected Spring bean, ensuring the call
     * traverses the Spring AOP proxy so transaction interceptors are properly applied without self-invocation bypass.
     *
     * @param code the short URL code
     * @return the aggregated click statistics including total clicks and the 30 most recent active days
     * @throws ShortUrlNotFoundException if the code does not exist in the short_url table
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ClickStatsResponse getStats(String code) {
        Boolean exists = jdbcTemplate.queryForObject(CHECK_CODE_EXISTS_SQL, Boolean.class, code);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ShortUrlNotFoundException("Short URL not found for code: " + code);
        }

        Long total = jdbcTemplate.queryForObject(QUERY_TOTAL_CLICKS_SQL, Long.class, code);
        long totalClicks = total != null ? total : 0L;

        List<DailyClickStat> daily = jdbcTemplate.query(
                QUERY_DAILY_CLICKS_SQL,
                (rs, rowNum) -> new DailyClickStat(
                        rs.getDate("day").toLocalDate(),
                        rs.getLong("count")
                ),
                code
        );

        return new ClickStatsResponse(code, totalClicks, daily);
    }
}

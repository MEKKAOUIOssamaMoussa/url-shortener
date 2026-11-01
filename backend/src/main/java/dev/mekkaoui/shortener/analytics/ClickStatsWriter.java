package dev.mekkaoui.shortener.analytics;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Executes batched upserts of aggregated click stats to PostgreSQL.
 *
 * Each batch executes inside a single transaction via {@link TransactionTemplate}
 * to ensure all-or-nothing atomicity (avoiding partial writes on mid-batch failure).
 */
public class ClickStatsWriter {

    private static final String BATCH_UPSERT_SQL = """
            insert into click_stats(code, day, count) values (?, ?, ?)
            on conflict (code, day) do update set count = click_stats.count + excluded.count
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ClickStatsWriter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void write(List<ClickStat> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(
                BATCH_UPSERT_SQL,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ClickStat stat = batch.get(i);
                        ps.setString(1, stat.code());
                        ps.setDate(2, Date.valueOf(stat.day()));
                        ps.setLong(3, stat.count());
                    }

                    @Override
                    public int getBatchSize() {
                        return batch.size();
                    }
                }
        ));
    }
}

package dev.mekkaoui.shortener.analytics;

import dev.mekkaoui.shortener.TestcontainersConfiguration;
import dev.mekkaoui.shortener.url.ShortUrl;
import dev.mekkaoui.shortener.url.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClickStatsWriterTest {

    private final JdbcTemplate jdbcTemplate;
    private final ShortUrlRepository shortUrlRepository;
    private final ClickStatsWriter writer;

    ClickStatsWriterTest(@Autowired JdbcTemplate jdbcTemplate,
                         @Autowired PlatformTransactionManager transactionManager,
                         @Autowired ShortUrlRepository shortUrlRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.shortUrlRepository = shortUrlRepository;
        this.writer = new ClickStatsWriter(jdbcTemplate, transactionManager);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("delete from click_stats");
        shortUrlRepository.deleteAll();
    }

    @Test
    void shouldAccumulateCountsOnDuplicateKeyUpserts() {
        shortUrlRepository.saveAndFlush(new ShortUrl("code1", "https://example.com/1"));
        LocalDate day = LocalDate.of(2026, 11, 1);

        writer.write(List.of(new ClickStat("code1", day, 3L)));
        writer.write(List.of(new ClickStat("code1", day, 5L)));

        Long count = jdbcTemplate.queryForObject(
                "select count from click_stats where code = ? and day = ?",
                Long.class,
                "code1",
                day
        );
        assertThat(count).isEqualTo(8L);
    }

    @Test
    void shouldUpsertMultipleCodesAndDaysInSingleBatch() {
        shortUrlRepository.saveAndFlush(new ShortUrl("codeA", "https://example.com/A"));
        shortUrlRepository.saveAndFlush(new ShortUrl("codeB", "https://example.com/B"));

        LocalDate day1 = LocalDate.of(2026, 11, 1);
        LocalDate day2 = LocalDate.of(2026, 11, 2);

        writer.write(List.of(
                new ClickStat("codeA", day1, 2L),
                new ClickStat("codeA", day2, 4L),
                new ClickStat("codeB", day1, 7L)
        ));

        assertThat(queryCount("codeA", day1)).isEqualTo(2L);
        assertThat(queryCount("codeA", day2)).isEqualTo(4L);
        assertThat(queryCount("codeB", day1)).isEqualTo(7L);
    }

    @Test
    void shouldRejectUnknownCodeViolatingForeignKey() {
        LocalDate day = LocalDate.of(2026, 11, 1);

        assertThatThrownBy(() -> writer.write(List.of(new ClickStat("nonexistent", day, 1L))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRollbackEntireBatchOnForeignKeyFailureEnsuringAllOrNothingAtomicity() {
        shortUrlRepository.saveAndFlush(new ShortUrl("validCode", "https://example.com/valid"));
        LocalDate day = LocalDate.of(2026, 11, 1);

        List<ClickStat> batch = List.of(
                new ClickStat("validCode", day, 10L),
                new ClickStat("invalidCode", day, 20L)
        );

        assertThatThrownBy(() -> writer.write(batch))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Entire batch must be rolled back: validCode was NOT partially committed
        Integer rowCount = jdbcTemplate.queryForObject(
                "select count(*) from click_stats where code = ?",
                Integer.class,
                "validCode"
        );
        assertThat(rowCount).isZero();
    }

    @Test
    void shouldNoOpOnNullOrEmptyBatch() {
        writer.write(null);
        writer.write(List.of());

        Integer totalRows = jdbcTemplate.queryForObject("select count(*) from click_stats", Integer.class);
        assertThat(totalRows).isZero();
    }

    private Long queryCount(String code, LocalDate day) {
        return jdbcTemplate.queryForObject(
                "select count from click_stats where code = ? and day = ?",
                Long.class,
                code,
                day
        );
    }
}

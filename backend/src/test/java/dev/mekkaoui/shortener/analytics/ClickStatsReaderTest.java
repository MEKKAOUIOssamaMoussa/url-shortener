package dev.mekkaoui.shortener.analytics;

import dev.mekkaoui.shortener.TestcontainersConfiguration;
import dev.mekkaoui.shortener.analytics.dto.ClickStatsResponse;
import dev.mekkaoui.shortener.analytics.dto.DailyClickStat;
import dev.mekkaoui.shortener.url.ShortUrl;
import dev.mekkaoui.shortener.url.ShortUrlNotFoundException;
import dev.mekkaoui.shortener.url.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.sql.Date;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest
@Import({TestcontainersConfiguration.class, AnalyticsConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClickStatsReaderTest {

    private final ClickStatsReader clickStatsReader;
    private final ShortUrlRepository shortUrlRepository;

    @MockitoSpyBean
    private JdbcTemplate jdbcTemplate;

    ClickStatsReaderTest(@Autowired ClickStatsReader clickStatsReader,
                         @Autowired ShortUrlRepository shortUrlRepository) {
        this.clickStatsReader = clickStatsReader;
        this.shortUrlRepository = shortUrlRepository;
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("delete from click_stats");
        shortUrlRepository.deleteAll();
    }

    @Test
    void shouldSumTotalClicksAcrossAllDaysAndOrderDailyNewestFirst() {
        shortUrlRepository.saveAndFlush(new ShortUrl("codeA", "https://example.com/a"));

        LocalDate day1 = LocalDate.of(2026, 10, 30);
        LocalDate day2 = LocalDate.of(2026, 10, 31);
        LocalDate day3 = LocalDate.of(2026, 11, 1);

        insertClickStat("codeA", day1, 10L);
        insertClickStat("codeA", day2, 20L);
        insertClickStat("codeA", day3, 5L);

        ClickStatsResponse stats = clickStatsReader.getStats("codeA");

        assertThat(stats.code()).isEqualTo("codeA");
        assertThat(stats.totalClicks()).isEqualTo(35L);
        assertThat(stats.daily()).containsExactly(
                new DailyClickStat(day3, 5L),
                new DailyClickStat(day2, 20L),
                new DailyClickStat(day1, 10L)
        );
    }

    @Test
    void shouldLimitDailyRowsToAtMost30NewestDaysWhileSummingAllDaysInTotal() {
        shortUrlRepository.saveAndFlush(new ShortUrl("codeB", "https://example.com/b"));

        LocalDate baseDate = LocalDate.of(2026, 11, 1);
        // Insert 35 consecutive days, 2 clicks each -> total 70 clicks
        for (int i = 0; i < 35; i++) {
            insertClickStat("codeB", baseDate.minusDays(i), 2L);
        }

        ClickStatsResponse stats = clickStatsReader.getStats("codeB");

        assertThat(stats.totalClicks()).isEqualTo(70L);
        assertThat(stats.daily()).hasSize(30);

        // Daily rows must be newest first: index 0 is baseDate (day 0), index 29 is baseDate - 29 days
        assertThat(stats.daily().getFirst().day()).isEqualTo(baseDate);
        assertThat(stats.daily().getLast().day()).isEqualTo(baseDate.minusDays(29));
    }

    @Test
    void shouldReturnZeroTotalAndEmptyDailyForKnownCodeWithNoClicks() {
        shortUrlRepository.saveAndFlush(new ShortUrl("codeZero", "https://example.com/zero"));

        ClickStatsResponse stats = clickStatsReader.getStats("codeZero");

        assertThat(stats.code()).isEqualTo("codeZero");
        assertThat(stats.totalClicks()).isZero();
        assertThat(stats.daily()).isEmpty();
    }

    @Test
    void shouldThrowShortUrlNotFoundExceptionForUnknownCode() {
        assertThatThrownBy(() -> clickStatsReader.getStats("nonexistent"))
                .isInstanceOf(ShortUrlNotFoundException.class)
                .hasMessage("Short URL not found for code: nonexistent");
    }

    @Test
    void shouldIsolateStatsBetweenDifferentCodes() {
        shortUrlRepository.saveAndFlush(new ShortUrl("codeOne", "https://example.com/1"));
        shortUrlRepository.saveAndFlush(new ShortUrl("codeTwo", "https://example.com/2"));

        LocalDate today = LocalDate.of(2026, 11, 1);
        insertClickStat("codeOne", today, 100L);
        insertClickStat("codeTwo", today, 200L);

        ClickStatsResponse statsOne = clickStatsReader.getStats("codeOne");
        assertThat(statsOne.totalClicks()).isEqualTo(100L);
        assertThat(statsOne.daily()).containsExactly(new DailyClickStat(today, 100L));

        ClickStatsResponse statsTwo = clickStatsReader.getStats("codeTwo");
        assertThat(statsTwo.totalClicks()).isEqualTo(200L);
        assertThat(statsTwo.daily()).containsExactly(new DailyClickStat(today, 200L));
    }

    @Test
    void shouldExecuteThroughSpringProxyWithRepeatableReadAndReadOnlyTransaction() throws Exception {
        shortUrlRepository.saveAndFlush(new ShortUrl("txCheck", "https://example.com/tx"));

        // 1. Confirm the injected bean is a Spring AOP proxy (ensuring no self-invocation bypass)
        assertThat(AopUtils.isAopProxy(clickStatsReader))
                .as("ClickStatsReader must be wrapped in a Spring proxy")
                .isTrue();

        // 2. Verify transaction attribute configuration via Spring's TransactionAttributeSource
        Method getStatsMethod = ClickStatsReader.class.getMethod("getStats", String.class);
        AnnotationTransactionAttributeSource tas = new AnnotationTransactionAttributeSource();
        TransactionAttribute attr = tas.getTransactionAttribute(getStatsMethod, ClickStatsReader.class);

        assertThat(attr).isNotNull();
        assertThat(attr.isReadOnly()).isTrue();
        assertThat(attr.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        // 3. Verify that during execution through the proxy, an actual transaction is active
        // with readOnly=true and isolation=REPEATABLE_READ (4) via TransactionSynchronizationManager
        AtomicBoolean activeTx = new AtomicBoolean(false);
        AtomicBoolean readOnlyTx = new AtomicBoolean(false);
        AtomicInteger isolationLevel = new AtomicInteger(-1);

        doAnswer(invocation -> {
            activeTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            readOnlyTx.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Integer iso = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (iso != null) {
                isolationLevel.set(iso);
            }
            return invocation.callRealMethod();
        }).when(jdbcTemplate).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));

        clickStatsReader.getStats("txCheck");

        assertThat(activeTx.get()).as("Transaction must be active during getStats").isTrue();
        assertThat(readOnlyTx.get()).as("Transaction must be read-only").isTrue();
        assertThat(isolationLevel.get()).as("Transaction isolation must be REPEATABLE_READ")
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private void insertClickStat(String code, LocalDate day, long count) {
        jdbcTemplate.update(
                "insert into click_stats(code, day, count) values (?, ?, ?)",
                code,
                Date.valueOf(day),
                count
        );
    }
}

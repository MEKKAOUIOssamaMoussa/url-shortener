package dev.mekkaoui.shortener.analytics;

import dev.mekkaoui.shortener.analytics.dto.ClickStatsResponse;
import dev.mekkaoui.shortener.analytics.dto.DailyClickStat;
import dev.mekkaoui.shortener.error.GlobalExceptionHandler;
import dev.mekkaoui.shortener.url.ShortUrlNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClickStatsController.class)
@Import(GlobalExceptionHandler.class)
class ClickStatsControllerTest {

    private final MockMvc mockMvc;

    @MockitoBean
    private ClickStatsReader clickStatsReader;

    ClickStatsControllerTest(@Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldReturn200WithStatsWhenClicksExist() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 11, 2);
        LocalDate day2 = LocalDate.of(2026, 11, 1);
        ClickStatsResponse response = new ClickStatsResponse(
                "abc1234",
                42L,
                List.of(
                        new DailyClickStat(day1, 30L),
                        new DailyClickStat(day2, 12L)
                )
        );

        when(clickStatsReader.getStats("abc1234")).thenReturn(response);

        mockMvc.perform(get("/api/urls/abc1234/stats"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.code").value("abc1234"))
                .andExpect(jsonPath("$.totalClicks").value(42))
                .andExpect(jsonPath("$.daily", hasSize(2)))
                .andExpect(jsonPath("$.daily[0].day").value("2026-11-02"))
                .andExpect(jsonPath("$.daily[0].count").value(30))
                .andExpect(jsonPath("$.daily[1].day").value("2026-11-01"))
                .andExpect(jsonPath("$.daily[1].count").value(12));
    }

    @Test
    void shouldReturn200WithZeroClicksAndEmptyDailyWhenKnownCodeHasNoClicks() throws Exception {
        ClickStatsResponse response = new ClickStatsResponse("zeroClicks", 0L, List.of());
        when(clickStatsReader.getStats("zeroClicks")).thenReturn(response);

        mockMvc.perform(get("/api/urls/zeroClicks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("zeroClicks"))
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.daily", hasSize(0)));
    }

    @Test
    void shouldReturn404ProblemDetailWhenCodeNotFound() throws Exception {
        when(clickStatsReader.getStats("missingCode"))
                .thenThrow(new ShortUrlNotFoundException("Short URL not found for code: missingCode"));

        mockMvc.perform(get("/api/urls/missingCode/stats"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Short URL Not Found"))
                .andExpect(jsonPath("$.detail").value("Short URL not found for code: missingCode"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid@code",
            "bad-dash",
            "code_underscore",
            "with space",
            "wayTooLongCodeExceedingSixteenChars"
    })
    void shouldReturn404WhenCodePatternDoesNotMatchRoute(String invalidCode) throws Exception {
        // The regex {code:[0-9A-Za-z]{1,16}} does not match non-alphanumeric or >16 char codes;
        // Spring MVC returns 404 because no handler mapping matches the request path.
        mockMvc.perform(get("/api/urls/" + invalidCode + "/stats"))
                .andExpect(status().isNotFound());
    }
}

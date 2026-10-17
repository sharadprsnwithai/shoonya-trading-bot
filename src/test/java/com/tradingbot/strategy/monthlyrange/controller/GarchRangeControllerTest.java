package com.tradingbot.strategy.monthlyrange.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.strategy.monthlyrange.model.GarchRangeBand;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeReport;
import com.tradingbot.strategy.monthlyrange.model.ModelComparison;
import com.tradingbot.strategy.monthlyrange.model.WingZoneForecast;
import com.tradingbot.strategy.monthlyrange.service.GarchRangeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GarchRangeControllerTest {

    private GarchRangeService service;
    private GarchRangeController controller;

    @BeforeEach
    void setUp() {
        service = mock(GarchRangeService.class);
        controller = new GarchRangeController(service);
    }

    private GarchRangeForecast sampleForecast(String symbol) {
        return new GarchRangeForecast(
                symbol,
                new BigDecimal("24000.00"),
                5,
                new BigDecimal("1.79"),
                new BigDecimal("12.66"),
                new BigDecimal("0.79"),
                0.95,
                LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 13),
                List.of(
                        new GarchRangeBand(
                                "1.0 sigma",
                                1.0,
                                new BigDecimal("23571.00"),
                                new BigDecimal("24432.00"),
                                68.27,
                                41.20,
                                42)),
                new BigDecimal("1.85"),
                List.of(
                        new GarchRangeBand(
                                "1.0 sigma",
                                1.0,
                                new BigDecimal("23564.00"),
                                new BigDecimal("24440.00"),
                                68.27,
                                44.05,
                                42)),
                new WingZoneForecast(
                        "150/200 wing structure",
                        new BigDecimal("24000.00"),
                        new BigDecimal("23730.00"),
                        new BigDecimal("24270.00"),
                        1.13,
                        62.4,
                        48.8,
                        42,
                        5,
                        150.0,
                        200.0,
                        20.0,
                        "measured vs gaussian"),
                new ModelComparison(
                        "GJR_GARCH",
                        -610.5,
                        -601.2,
                        1230.0,
                        1216.4,
                        1244.0,
                        1234.1,
                        0.031,
                        1.52,
                        12.4,
                        9.8,
                        48.8,
                        51.2,
                        205,
                        true,
                        "GJR wins on both lenses."),
                42,
                179,
                "CONVERGED",
                Instant.now());
    }

    @Test
    @DisplayName("GET /weekly/forecast returns the aggregated multi-symbol report")
    void testGetWeeklyRangeReport() {
        GarchRangeReport report =
                new GarchRangeReport(
                        Instant.now(),
                        LocalDate.of(2026, 10, 6),
                        LocalDate.of(2026, 10, 13),
                        5,
                        List.of(sampleForecast("NIFTY50")),
                        "done");
        when(service.generateWeeklyReport()).thenReturn(report);

        ResponseEntity<GarchRangeReport> resp = controller.getWeeklyRangeReport();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().forecasts()).hasSize(1);
        assertThat(resp.getBody().horizonSessions()).isEqualTo(5);
    }

    @Test
    @DisplayName("POST /weekly/run triggers the routine and returns the report")
    void testRunWeeklyRange() {
        GarchRangeReport report =
                new GarchRangeReport(
                        Instant.now(),
                        LocalDate.of(2026, 10, 6),
                        LocalDate.of(2026, 10, 13),
                        5,
                        List.of(sampleForecast("NIFTY50")),
                        "done");
        when(service.generateWeeklyReport()).thenReturn(report);

        ResponseEntity<GarchRangeReport> resp = controller.runWeeklyRange();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service, times(1)).generateWeeklyReport();
    }

    @Test
    @DisplayName("GET /weekly/forecast/{symbol} resolves the symbol path variable")
    void testGetForecastForSymbol() {
        when(service.generateRangeForSymbol(anyString())).thenReturn(sampleForecast("RELIANCE"));

        ResponseEntity<GarchRangeForecast> resp = controller.getForecastForSymbol("RELIANCE");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().symbol()).isEqualTo("RELIANCE");
        verify(service, times(1)).generateRangeForSymbol("RELIANCE");
    }
}

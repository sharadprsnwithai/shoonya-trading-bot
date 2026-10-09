package com.tradingbot.strategy.monthlyrange.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeReport;
import com.tradingbot.strategy.monthlyrange.service.MonthlyRangeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MonthlyRangeControllerTest {

    private MonthlyRangeService service;
    private MonthlyRangeController controller;

    @BeforeEach
    void setUp() {
        service = mock(MonthlyRangeService.class);
        controller = new MonthlyRangeController(service);
    }

    private MonthlyRangeForecast createSampleForecast(String symbol) {
        return new MonthlyRangeForecast(
                symbol,
                new BigDecimal("2800.00"),
                5.5,
                18.5,
                new BigDecimal("2646.00"),
                new BigDecimal("2954.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("3100.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("3100.00"),
                new BigDecimal("20.00"),
                18.0,
                new BigDecimal("42.00"),
                22,
                false,
                "Normal",
                2.00,
                new BigDecimal("150.00"),
                new BigDecimal("3100.00"),
                new BigDecimal("2500.00"),
                Instant.now());
    }

    @Test
    @DisplayName("GET /api/v1/monthly-range/forecast should return aggregated forecast report")
    void testGetForecastReport() {
        MonthlyRangeReport mockReport =
                new MonthlyRangeReport(
                        Instant.now(),
                        "2026-10",
                        List.of(createSampleForecast("RELIANCE")),
                        "Success");
        when(service.generateMonthlyReport()).thenReturn(mockReport);

        ResponseEntity<MonthlyRangeReport> resp = controller.getMonthlyRangeReport();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().forecasts()).hasSize(1);
        assertThat(resp.getBody().forecasts().get(0).symbol()).isEqualTo("RELIANCE");
    }

    @Test
    @DisplayName("POST /api/v1/monthly-range/run should trigger manual execution and return report")
    void testRunMonthlyRange() {
        MonthlyRangeReport mockReport =
                new MonthlyRangeReport(
                        Instant.now(), "2026-10", List.of(createSampleForecast("TCS")), "Success");
        when(service.generateMonthlyReport()).thenReturn(mockReport);

        ResponseEntity<MonthlyRangeReport> resp = controller.runMonthlyRange();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        verify(service, times(1)).generateMonthlyReport();
    }

    @Test
    @DisplayName(
            "GET /api/v1/monthly-range/forecast/{symbol} should return forecast for single stock")
    void testGetSingleForecast() {
        MonthlyRangeForecast mockForecast = createSampleForecast("HDFCBANK");
        when(service.generateForecastForSymbol(eq("HDFCBANK"), anyInt())).thenReturn(mockForecast);

        ResponseEntity<MonthlyRangeForecast> resp = controller.getForecastForSymbol("HDFCBANK", 22);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().symbol()).isEqualTo("HDFCBANK");
    }
}

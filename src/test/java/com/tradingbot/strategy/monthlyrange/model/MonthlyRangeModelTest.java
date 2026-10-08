package com.tradingbot.strategy.monthlyrange.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyRangeModelTest {

    @Test
    @DisplayName("MonthlyRangeProperties should provide default values and support mutation")
    void testMonthlyRangePropertiesDefaults() {
        MonthlyRangeProperties props = new MonthlyRangeProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getCron()).isEqualTo("0 0 10 ? * MON-FRI");
        assertThat(props.getSymbols())
                .containsExactly(
                        "RELIANCE",
                        "TCS",
                        "HDFCBANK",
                        "INFY",
                        "ICICIBANK",
                        "SBIN",
                        "TATAMOTORS",
                        "NIFTY50");
        assertThat(props.getForecastHorizonDays()).isEqualTo(22);
        assertThat(props.getHistoryLookbackDays()).isEqualTo(504);
        assertThat(props.isTelegramAlertsEnabled()).isTrue();
        assertThat(props.getNormalPeMultiplier()).isEqualTo(2.10);
        assertThat(props.getNormalCeMultiplier()).isEqualTo(1.95);
        assertThat(props.getEventPeMultiplier()).isEqualTo(2.45);
        assertThat(props.getEventCeMultiplier()).isEqualTo(2.25);
        assertThat(props.getEarningsMonths()).containsExactly(1, 4, 7, 10);
        assertThat(props.getIvHvSpikeThreshold()).isEqualTo(1.25);
        assertThat(props.getOptionChainStrikeCount()).isEqualTo(25);

        props.setEnabled(false);
        props.setForecastHorizonDays(20);
        props.setEventPeMultiplier(2.55);
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getForecastHorizonDays()).isEqualTo(20);
        assertThat(props.getEventPeMultiplier()).isEqualTo(2.55);
    }

    @Test
    @DisplayName("GarchModelParams record holds model coefficients and persistence")
    void testGarchModelParams() {
        GarchModelParams params =
                new GarchModelParams(0.000005, 0.08, 0.90, 0.00005, -1250.5, true, "CONVERGED");

        assertThat(params.omega()).isEqualTo(0.000005);
        assertThat(params.alpha()).isEqualTo(0.08);
        assertThat(params.beta()).isEqualTo(0.90);
        assertThat(params.persistence()).isEqualTo(0.98);
        assertThat(params.longRunVariance()).isEqualTo(0.00005);
        assertThat(params.converged()).isTrue();
    }

    @Test
    @DisplayName("MonthlyRangeForecast correctly models fused event, IV, and OI boundaries")
    void testMonthlyRangeForecast() {
        MonthlyRangeForecast forecast =
                new MonthlyRangeForecast(
                        "RELIANCE",
                        new BigDecimal("2800.00"),
                        5.50,
                        18.60,
                        new BigDecimal("2646.00"),
                        new BigDecimal("2954.00"),
                        new BigDecimal("2500.00"),
                        new BigDecimal("3100.00"),
                        new BigDecimal("2500.00"),
                        new BigDecimal("3100.00"),
                        new BigDecimal("20.00"),
                        18.20,
                        new BigDecimal("42.50"),
                        22,
                        true,
                        "Q3 Earnings Cycle",
                        2.45,
                        new BigDecimal("160.00"),
                        new BigDecimal("3100.00"),
                        new BigDecimal("2500.00"),
                        Instant.now());

        assertThat(forecast.symbol()).isEqualTo("RELIANCE");
        assertThat(forecast.spotPrice()).isEqualByComparingTo("2800.00");
        assertThat(forecast.monthlyVolPct()).isEqualTo(5.50);
        assertThat(forecast.safePeStrike()).isEqualByComparingTo("2500.00");
        assertThat(forecast.safeCeStrike()).isEqualByComparingTo("3100.00");
        assertThat(forecast.isEventMonth()).isTrue();
        assertThat(forecast.eventReason()).isEqualTo("Q3 Earnings Cycle");
        assertThat(forecast.confidenceMultiplier()).isEqualTo(2.45);
        assertThat(forecast.atmStraddleMove()).isEqualByComparingTo("160.00");
        assertThat(forecast.maxCallOiStrike()).isEqualByComparingTo("3100.00");
        assertThat(forecast.maxPutOiStrike()).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("MonthlyRangeReport aggregates multiple forecasts and cycle metadata")
    void testMonthlyRangeReport() {
        MonthlyRangeForecast forecast =
                new MonthlyRangeForecast(
                        "TCS",
                        new BigDecimal("3900.00"),
                        4.80,
                        16.20,
                        new BigDecimal("3712.00"),
                        new BigDecimal("4087.00"),
                        new BigDecimal("3533.00"),
                        new BigDecimal("4266.00"),
                        new BigDecimal("3520.00"),
                        new BigDecimal("4280.00"),
                        new BigDecimal("20.00"),
                        15.80,
                        new BigDecimal("55.00"),
                        22,
                        false,
                        "Normal Month",
                        2.10,
                        new BigDecimal("180.00"),
                        new BigDecimal("4200.00"),
                        new BigDecimal("3600.00"),
                        Instant.now());

        MonthlyRangeReport report =
                new MonthlyRangeReport(
                        Instant.now(),
                        "2026-10",
                        List.of(forecast),
                        "All 1 symbols computed successfully.");

        assertThat(report.forecasts()).hasSize(1);
        assertThat(report.cycle()).isEqualTo("2026-10");
        assertThat(report.forecasts().getFirst().symbol()).isEqualTo("TCS");
    }
}

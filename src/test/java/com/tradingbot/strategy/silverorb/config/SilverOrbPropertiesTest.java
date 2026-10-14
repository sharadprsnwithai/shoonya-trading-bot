package com.tradingbot.strategy.silverorb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Default-configuration tests pinning {@link SilverOrbProperties} to the validated spec. */
class SilverOrbPropertiesTest {

    @Test
    @DisplayName("Defaults match the validated paper-trade spec exactly")
    void defaultsMatchSpec() {
        SilverOrbProperties props = new SilverOrbProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isSchedulerEnabled()).isTrue();
        assertThat(props.isTelegramAlertsEnabled()).isTrue();
        assertThat(props.getCron()).isEqualTo("20 */5 18-23 ? * MON-FRI");
        assertThat(props.getExch()).isEqualTo("MCX");
        assertThat(props.getToken()).isEqualTo("495214"); // SILVER04DEC26 main future
        assertThat(props.getSymbol()).isEqualTo("SILVER04DEC26");
        assertThat(props.getTimeframe()).isEqualTo("5");
        assertThat(props.getKgPerLot()).isEqualTo(30.0);
        assertThat(props.getLots()).isEqualTo(1);
        assertThat(props.getMarginPct()).isEqualTo(10.0);
        assertThat(props.getRiskPct()).isEqualTo(7.0);
        assertThat(props.getTargetPct()).isEqualTo(25.0);
        assertThat(props.getMinOrbBars()).isEqualTo(4);
        assertThat(props.getMinOrbRangePct()).isEqualTo(0.05);
        assertThat(props.getOrbStart()).isEqualTo(LocalTime.of(18, 0));
        assertThat(props.getOrbEnd()).isEqualTo(LocalTime.of(18, 30));
        assertThat(props.getEntryCutoff()).isEqualTo(LocalTime.of(21, 0));
        assertThat(props.getSquareOff()).isEqualTo(LocalTime.of(23, 0));
        assertThat(props.getRunUntil()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(props.getStateFile()).isEqualTo("./data/silver-orb-state.json");
    }

    @Test
    @DisplayName("barMinutes parses the configured timeframe")
    void barMinutesParsesTimeframe() {
        SilverOrbProperties props = new SilverOrbProperties();
        props.setTimeframe("15");

        assertThat(props.barMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("barMinutes falls back to 5 on unparseable timeframe")
    void barMinutesFallsBackOnBadInput() {
        SilverOrbProperties props = new SilverOrbProperties();
        props.setTimeframe("five");

        assertThat(props.barMinutes()).isEqualTo(5);
    }
}

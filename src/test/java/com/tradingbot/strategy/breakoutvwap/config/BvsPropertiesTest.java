package com.tradingbot.strategy.breakoutvwap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Defaults contract for BVS — later tasks read these names as API. */
class BvsPropertiesTest {

    @Test
    @DisplayName("defaults match the BVS spec values")
    void defaultsMatchSpec() {
        BvsProperties p = new BvsProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.isSchedulerEnabled()).isTrue();
        assertThat(p.getBucketSize()).isEqualTo(3);
        assertThat(p.getRrPartial()).isEqualTo(2.5);
        assertThat(p.getEmaPeriod()).isEqualTo(10);
        assertThat(p.getTriggerWindowMinutes()).isEqualTo(5);
        assertThat(p.getEntryCutoff()).isEqualTo(LocalTime.of(14, 30));
        assertThat(p.getHardExit()).isEqualTo(LocalTime.of(15, 0));
        assertThat(p.getSessionRangeStart()).isEqualTo(LocalTime.of(9, 30));
        assertThat(p.getSessionRangeEnd()).isEqualTo(LocalTime.of(10, 0));
        assertThat(p.getScanCron()).isEqualTo("0 25 9 ? * MON-FRI");
        assertThat(p.getOiSpurtCron()).isEqualTo("5 25 9 ? * MON-FRI");
        assertThat(p.getCycleCron()).isEqualTo("20 */5 9-15 ? * MON-FRI");
        assertThat(p.getTickRateMs()).isEqualTo(30000L);
        assertThat(p.isTelegramAlerts()).isTrue();
        // class default OFF (direct instantiation must not touch disk); Spring config block = true
        assertThat(p.isStatePersistenceEnabled()).isFalse();
        assertThat(p.getStateFile()).isEqualTo("./data/bvs-state.json");
        assertThat(p.getRiskPerTradePercent()).isEqualTo(1.0);
        assertThat(p.getPaperCapital()).isEqualTo(500000.0);
        assertThat(p.getLots()).isEqualTo(1);
        assertThat(p.isDynamicPositionSizing()).isTrue();
    }
}

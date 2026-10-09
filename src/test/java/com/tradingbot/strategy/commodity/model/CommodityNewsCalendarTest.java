package com.tradingbot.strategy.commodity.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommodityNewsCalendarTest {

    @Test
    @DisplayName("08:30 ET maps to 18:00 IST during EDT and 19:00 IST during EST")
    void testMacroReleaseMapping() {
        LocalDateTime edt = CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 7, 1), 8, 30);
        assertThat(edt).isEqualTo(LocalDateTime.of(2026, 7, 1, 18, 0));

        LocalDateTime est = CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 1, 15), 8, 30);
        assertThat(est).isEqualTo(LocalDateTime.of(2026, 1, 15, 19, 0));
    }

    @Test
    @DisplayName("10:30 ET EIA maps to 20:00 IST during EDT and 21:00 IST during EST")
    void testEiaReleaseMapping() {
        LocalDateTime edt = CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 7, 1), 10, 30);
        assertThat(edt).isEqualTo(LocalDateTime.of(2026, 7, 1, 20, 0));

        LocalDateTime est = CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 1, 14), 10, 30);
        assertThat(est).isEqualTo(LocalDateTime.of(2026, 1, 14, 21, 0));
    }

    @Test
    @DisplayName("DST boundaries 2026: EST before 03-08, EDT from 03-08, EST again from 11-01")
    void testDstBoundaries2026() {
        assertThat(CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 3, 7), 8, 30).getHour())
                .isEqualTo(19);
        assertThat(CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 3, 9), 8, 30).getHour())
                .isEqualTo(18);
        assertThat(
                        CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 10, 31), 8, 30)
                                .getHour())
                .isEqualTo(18);
        assertThat(CommodityNewsCalendar.usReleaseInIst(LocalDate.of(2026, 11, 2), 8, 30).getHour())
                .isEqualTo(19);
    }

    @Test
    @DisplayName("Blackout window is inclusive of release +/- half-width minutes")
    void testBlackoutWindowBounds() {
        LocalDateTime release = LocalDateTime.of(2026, 7, 1, 18, 0);
        assertThat(CommodityNewsCalendar.withinBlackoutWindow(LocalTime.of(17, 30), release, 30))
                .isTrue();
        assertThat(CommodityNewsCalendar.withinBlackoutWindow(LocalTime.of(18, 30), release, 30))
                .isTrue();
        assertThat(CommodityNewsCalendar.withinBlackoutWindow(LocalTime.of(17, 29), release, 30))
                .isFalse();
        assertThat(CommodityNewsCalendar.withinBlackoutWindow(LocalTime.of(18, 31), release, 30))
                .isFalse();
    }
}

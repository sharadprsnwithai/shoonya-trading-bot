package com.tradingbot.strategy.commodity.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * DST-aware mapping of US market release times to IST for the commodity VWAP strategy's news
 * blackout windows.
 *
 * <p>US macro releases (NFP, CPI, PPI, Retail Sales) land at 08:30 ET and EIA crude inventories at
 * 10:30 ET. Because IST = ET + 9.5h during EDT but ET + 10.5h during EST, the same release moves
 * between 18:00/19:00 IST (macro) and 20:00/21:00 IST (EIA) across the year. Hardcoded IST windows
 * therefore miss the release for roughly half the year. Mapping through the IANA {@code
 * America/New_York} zone keeps the conversion correct through current and future US DST rule
 * changes.
 */
public final class CommodityNewsCalendar {

    private static final ZoneId US_EASTERN = ZoneId.of("America/New_York");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private CommodityNewsCalendar() {}

    /**
     * Maps a US Eastern release time on the given calendar date to its IST wall-clock time
     * (DST-aware). The returned LocalDateTime always falls on the same calendar day in IST for
     * morning US releases.
     */
    public static LocalDateTime usReleaseInIst(LocalDate date, int hourEt, int minuteEt) {
        return LocalDateTime.of(date, LocalTime.of(hourEt, minuteEt))
                .atZone(US_EASTERN)
                .withZoneSameInstant(IST)
                .toLocalDateTime();
    }

    /** True when {@code time} falls inside [release - halfWidth, release + halfWidth] IST. */
    public static boolean withinBlackoutWindow(
            LocalTime time, LocalDateTime istRelease, int halfWidthMinutes) {
        if (time == null || istRelease == null) return false;
        int half = Math.max(0, halfWidthMinutes);
        LocalTime start = istRelease.toLocalTime().minusMinutes(half);
        LocalTime end = istRelease.toLocalTime().plusMinutes(half);
        return !time.isBefore(start) && !time.isAfter(end);
    }
}

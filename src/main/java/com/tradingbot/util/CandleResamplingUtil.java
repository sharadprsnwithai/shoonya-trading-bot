package com.tradingbot.util;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Utility to resample intraday or daily candles into higher timeframe bars (e.g. Weekly, 15m). */
public final class CandleResamplingUtil {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final WeekFields WEEK_FIELDS = WeekFields.of(Locale.getDefault());

    private CandleResamplingUtil() {}

    /**
     * Resamples a chronological list of Daily candles into Weekly candles. Each week groups Monday
     * through Friday bars into a single Weekly bar.
     *
     * @param dailyCandles chronological list of daily candles
     * @return chronological list of weekly candles
     */
    public static List<Candle> resampleDailyToWeekly(List<Candle> dailyCandles) {
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return List.of();
        }

        // Group by (year * 100 + weekNumber)
        Map<Integer, List<Candle>> groupedByWeek = new LinkedHashMap<>();

        for (Candle c : dailyCandles) {
            if (c == null || c.timestamp() == null) continue;
            var localDate = c.timestamp().atZone(IST).toLocalDate();
            int year = localDate.get(WEEK_FIELDS.weekBasedYear());
            int week = localDate.get(WEEK_FIELDS.weekOfWeekBasedYear());
            int weekKey = year * 100 + week;

            groupedByWeek.computeIfAbsent(weekKey, k -> new ArrayList<>()).add(c);
        }

        List<Candle> weeklyCandles = new ArrayList<>();

        for (List<Candle> weekBars : groupedByWeek.values()) {
            if (weekBars.isEmpty()) continue;

            weekBars.sort(Comparator.comparing(Candle::timestamp));
            Candle first = weekBars.get(0);
            Candle last = weekBars.get(weekBars.size() - 1);

            BigDecimal open = first.open();
            BigDecimal close = last.close();
            BigDecimal high = first.high();
            BigDecimal low = first.low();
            long totalVolume = 0;

            for (Candle b : weekBars) {
                if (b.high().compareTo(high) > 0) high = b.high();
                if (b.low().compareTo(low) < 0) low = b.low();
                totalVolume += b.volume();
            }

            weeklyCandles.add(
                    new Candle(
                            first.symbol(),
                            "1W",
                            last.timestamp(),
                            open,
                            high,
                            low,
                            close,
                            totalVolume));
        }

        weeklyCandles.sort(Comparator.comparing(Candle::timestamp));
        return weeklyCandles;
    }

    /**
     * Resamples a chronological list of 5-minute candles into 15-minute candles.
     *
     * @param fiveMinCandles chronological list of 5m candles
     * @return chronological list of 15m candles
     */
    public static List<Candle> resample5MinTo15Min(List<Candle> fiveMinCandles) {
        if (fiveMinCandles == null || fiveMinCandles.isEmpty()) {
            return List.of();
        }

        Map<Long, List<Candle>> groupedBy15Min = new LinkedHashMap<>();

        for (Candle c : fiveMinCandles) {
            if (c == null || c.timestamp() == null) continue;
            // Group by 15-minute slot: epochMinute / 15
            long epochMinutes = c.timestamp().getEpochSecond() / 60;
            long intervalKey = (epochMinutes / 15) * 15;
            groupedBy15Min.computeIfAbsent(intervalKey, k -> new ArrayList<>()).add(c);
        }

        List<Candle> resampled = new ArrayList<>();
        for (List<Candle> bucket : groupedBy15Min.values()) {
            if (bucket.isEmpty()) continue;
            bucket.sort(Comparator.comparing(Candle::timestamp));
            Candle first = bucket.get(0);
            Candle last = bucket.get(bucket.size() - 1);

            BigDecimal open = first.open();
            BigDecimal close = last.close();
            BigDecimal high = first.high();
            BigDecimal low = first.low();
            long totalVolume = 0;

            for (Candle b : bucket) {
                if (b.high().compareTo(high) > 0) high = b.high();
                if (b.low().compareTo(low) < 0) low = b.low();
                totalVolume += b.volume();
            }

            resampled.add(
                    new Candle(
                            first.symbol(),
                            "15",
                            last.timestamp(),
                            open,
                            high,
                            low,
                            close,
                            totalVolume));
        }

        resampled.sort(Comparator.comparing(Candle::timestamp));
        return resampled;
    }
}

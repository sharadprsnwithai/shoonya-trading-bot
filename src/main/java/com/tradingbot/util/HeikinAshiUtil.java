package com.tradingbot.util;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility that transforms standard OHLC candles into Heikin-Ashi candles.
 *
 * <p>Heikin-Ashi ("average bar") prices smooth noise so trend direction reads more clearly:
 *
 * <ul>
 *   <li>{@code HA Close = (Open + High + Low + Close) / 4}
 *   <li>{@code HA Open = (prev HA Open + prev HA Close) / 2} (first bar: {@code (Open + Close) /
 *       2})
 *   <li>{@code HA High = max(High, HA Open, HA Close)}
 *   <li>{@code HA Low = min(Low, HA Open, HA Close)}
 * </ul>
 *
 * <p>A Heikin-Ashi bar is <b>green</b> when its HA close is above its HA open, <b>red</b> when
 * below, and flat when equal. The recursion carries full precision; only the emitted candles are
 * rounded to two decimals.
 */
public final class HeikinAshiUtil {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);
    private static final int SCALE = 6;

    private HeikinAshiUtil() {}

    /**
     * Converts chronological standard candles into chronological Heikin-Ashi candles. Timestamps,
     * symbols, timeframes and volume are preserved; only OHLC are transformed.
     *
     * @param daily chronological (oldest-to-newest) standard candles
     * @return chronological Heikin-Ashi candles; empty when input is null/empty
     */
    public static List<Candle> toHeikinAshi(List<Candle> daily) {
        if (daily == null || daily.isEmpty()) {
            return List.of();
        }
        List<Candle> ha = new ArrayList<>(daily.size());
        BigDecimal prevHaOpen = null;
        BigDecimal prevHaClose = null;

        for (Candle c : daily) {
            if (c == null
                    || c.timestamp() == null
                    || c.open() == null
                    || c.high() == null
                    || c.low() == null
                    || c.close() == null) {
                continue;
            }
            BigDecimal o = c.open();
            BigDecimal h = c.high();
            BigDecimal l = c.low();
            BigDecimal cl = c.close();

            BigDecimal haClose = o.add(h).add(l).add(cl).divide(FOUR, SCALE, RoundingMode.HALF_UP);
            BigDecimal haOpen =
                    prevHaOpen == null
                            ? o.add(cl).divide(TWO, SCALE, RoundingMode.HALF_UP)
                            : prevHaOpen.add(prevHaClose).divide(TWO, SCALE, RoundingMode.HALF_UP);

            BigDecimal haHigh = max3(h, haOpen, haClose);
            BigDecimal haLow = min3(l, haOpen, haClose);

            ha.add(
                    new Candle(
                            c.symbol(),
                            c.timeframe(),
                            c.timestamp(),
                            haOpen.setScale(2, RoundingMode.HALF_UP),
                            haHigh.setScale(2, RoundingMode.HALF_UP),
                            haLow.setScale(2, RoundingMode.HALF_UP),
                            haClose.setScale(2, RoundingMode.HALF_UP),
                            c.volume()));

            prevHaOpen = haOpen;
            prevHaClose = haClose;
        }
        return ha;
    }

    /** True when the Heikin-Ashi bar is bullish (HA close above HA open). */
    public static boolean isGreen(Candle haCandle) {
        if (haCandle == null || haCandle.open() == null || haCandle.close() == null) {
            return false;
        }
        return haCandle.close().compareTo(haCandle.open()) > 0;
    }

    /** True when the Heikin-Ashi bar is bearish (HA close below HA open). */
    public static boolean isRed(Candle haCandle) {
        if (haCandle == null || haCandle.open() == null || haCandle.close() == null) {
            return false;
        }
        return haCandle.close().compareTo(haCandle.open()) < 0;
    }

    /**
     * Counts the run of consecutive same-direction Heikin-Ashi bars ending at the last bar (a
     * trailing flat bar breaks the run and yields 0).
     *
     * @param haSeries chronological Heikin-Ashi candles
     * @return length of the final green or red streak; 0 when the last bar is flat or input is
     *     empty
     */
    public static int trendStreak(List<Candle> haSeries) {
        if (haSeries == null || haSeries.isEmpty()) {
            return 0;
        }
        Candle last = haSeries.get(haSeries.size() - 1);
        boolean green = isGreen(last);
        boolean red = isRed(last);
        if (!green && !red) {
            return 0;
        }
        int streak = 0;
        for (int i = haSeries.size() - 1; i >= 0; i--) {
            Candle x = haSeries.get(i);
            if (green && isGreen(x)) {
                streak++;
            } else if (red && isRed(x)) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    private static BigDecimal max3(BigDecimal a, BigDecimal b, BigDecimal c) {
        BigDecimal m = a.max(b);
        return m.max(c);
    }

    private static BigDecimal min3(BigDecimal a, BigDecimal b, BigDecimal c) {
        BigDecimal m = a.min(b);
        return m.min(c);
    }
}

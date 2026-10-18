package com.tradingbot.strategy.fiftytwohigh;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.fiftytwohigh.config.FiftyTwoHighProperties;
import com.tradingbot.strategy.fiftytwohigh.model.FiftyTwoHighBreakout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure mathematical engine that detects a fresh 52-week-high breakout on a symbol's daily candles.
 *
 * <p>The logic mirrors how a trader reads a "52-week high breakout": take the most recent completed
 * daily candle, then compare its high against the highest high of the preceding 52 weeks (a
 * calendar-day window ending the day before that candle). If the latest high is strictly above that
 * prior high — and clears the configured minimum percentage — the candle is a breakout.
 *
 * <p>This class is stateless and side-effect free so it can be unit-tested against synthetic
 * series.
 */
@Component
public class FiftyTwoHighCalculator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int SCALE = 4;

    private final int lookbackDays;
    private final int minPriorSessions;
    private final double minBreakoutPct;
    private final boolean requireCloseConfirmation;

    @Autowired
    public FiftyTwoHighCalculator(FiftyTwoHighProperties properties) {
        this(
                properties != null ? properties.getLookbackDays() : 364,
                properties != null ? properties.getMinPriorSessions() : 60,
                properties != null ? properties.getMinBreakoutPct() : 0.0,
                properties != null && properties.isRequireCloseConfirmation());
    }

    /** Deterministic constructor for tests and explicit tuning. */
    public FiftyTwoHighCalculator(
            int lookbackDays,
            int minPriorSessions,
            double minBreakoutPct,
            boolean requireCloseConfirmation) {
        this.lookbackDays = lookbackDays > 0 ? lookbackDays : 364;
        this.minPriorSessions = Math.max(2, minPriorSessions);
        this.minBreakoutPct = minBreakoutPct;
        this.requireCloseConfirmation = requireCloseConfirmation;
    }

    /** Outcome classification for a single symbol evaluation. */
    public enum Status {
        /** The latest daily high crossed above the prior 52-week high. */
        BREAKOUT,
        /** Enough history existed, but the latest high did not cross the prior 52-week high. */
        NO_BREAKOUT,
        /** Too few prior sessions for a meaningful 52-week-high comparison. */
        INSUFFICIENT_HISTORY,
        /** No usable daily candles were available. */
        NO_DATA
    }

    /**
     * Result of evaluating one symbol: its status, the session that was evaluated, and — for a
     * {@link Status#BREAKOUT} — the populated breakout detail.
     *
     * @param status classification
     * @param sessionDate trading session of the latest candle evaluated (nullable)
     * @param breakout breakout detail, non-null only when status is BREAKOUT
     */
    public record Outcome(Status status, LocalDate sessionDate, FiftyTwoHighBreakout breakout) {}

    /**
     * Evaluates a symbol's daily candles for a fresh 52-week-high breakout.
     *
     * @param symbol NSE symbol
     * @param dailyCandles chronological (oldest-to-newest) daily candles
     * @param sector sector label for context (may be null)
     * @param lotSize F&amp;O lot size for context
     * @return an {@link Outcome}; never null
     */
    public Outcome evaluate(String symbol, List<Candle> dailyCandles, String sector, int lotSize) {
        if (dailyCandles == null || dailyCandles.size() < 2) {
            return new Outcome(Status.NO_DATA, null, null);
        }

        // Locate the most recent candle with a usable high (the "crossing" candle).
        int latestIdx = -1;
        for (int i = dailyCandles.size() - 1; i >= 0; i--) {
            Candle c = dailyCandles.get(i);
            if (c != null && c.timestamp() != null && c.high() != null) {
                latestIdx = i;
                break;
            }
        }
        if (latestIdx <= 0) {
            // Need at least one prior candle before the latest to form a comparison.
            return new Outcome(Status.NO_DATA, null, null);
        }

        Candle latest = dailyCandles.get(latestIdx);
        LocalDate latestDate = LocalDate.ofInstant(latest.timestamp(), IST);
        LocalDate windowStart = latestDate.minusDays(lookbackDays);

        // Prior 52-week high = highest high over candles strictly before the latest, inside the
        // window.
        BigDecimal priorHigh = BigDecimal.ZERO;
        LocalDate priorHighDate = null;
        int priorHighIdx = -1;
        int priorSessions = 0;
        for (int i = 0; i < latestIdx; i++) {
            Candle c = dailyCandles.get(i);
            if (c == null || c.timestamp() == null || c.high() == null) {
                continue;
            }
            LocalDate d = LocalDate.ofInstant(c.timestamp(), IST);
            if (d.isAfter(latestDate) || d.isBefore(windowStart)) {
                continue;
            }
            priorSessions++;
            if (c.high().compareTo(priorHigh) > 0) {
                priorHigh = c.high();
                priorHighDate = d;
                priorHighIdx = i;
            }
        }

        if (priorSessions < minPriorSessions || priorHigh.compareTo(BigDecimal.ZERO) <= 0) {
            return new Outcome(Status.INSUFFICIENT_HISTORY, latestDate, null);
        }

        BigDecimal high = latest.high();
        BigDecimal breakoutPct = pctChange(high, priorHigh).setScale(2, RoundingMode.HALF_UP);
        if (breakoutPct.doubleValue() <= minBreakoutPct) {
            return new Outcome(Status.NO_BREAKOUT, latestDate, null);
        }

        BigDecimal close = nz(latest.close());
        boolean closeConfirmed = close.compareTo(priorHigh) > 0;
        if (requireCloseConfirmation && !closeConfirmed) {
            return new Outcome(Status.NO_BREAKOUT, latestDate, null);
        }

        BigDecimal closeVsPriorPct = pctChange(close, priorHigh).setScale(2, RoundingMode.HALF_UP);
        int sessionsSincePriorHigh = priorHighIdx >= 0 ? (latestIdx - priorHighIdx) : 0;

        FiftyTwoHighBreakout breakout =
                new FiftyTwoHighBreakout(
                        symbol,
                        sector,
                        latestDate,
                        close.setScale(2, RoundingMode.HALF_UP),
                        high.setScale(2, RoundingMode.HALF_UP),
                        nz(latest.low()).setScale(2, RoundingMode.HALF_UP),
                        nz(latest.open()).setScale(2, RoundingMode.HALF_UP),
                        latest.volume(),
                        priorHigh.setScale(2, RoundingMode.HALF_UP),
                        priorHighDate,
                        breakoutPct,
                        closeVsPriorPct,
                        closeConfirmed,
                        sessionsSincePriorHigh,
                        priorSessions,
                        lotSize);

        return new Outcome(Status.BREAKOUT, latestDate, breakout);
    }

    /** (new - base) / base * 100. Returns ZERO when base is non-positive. */
    private static BigDecimal pctChange(BigDecimal newV, BigDecimal base) {
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0 || newV == null) {
            return BigDecimal.ZERO;
        }
        return newV.subtract(base)
                .multiply(BigDecimal.valueOf(100))
                .divide(base, SCALE, RoundingMode.HALF_UP);
    }

    /** Null-safe coalescing to ZERO. */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

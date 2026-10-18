package com.tradingbot.strategy.fiftytwohigh;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.fiftytwohigh.config.FiftyTwoHighProperties;
import com.tradingbot.strategy.fiftytwohigh.model.BreakoutTradeSetup;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure engine that decides whether a close-confirmed 52-week-high breakout is a "trade-ready" setup
 * and computes the recommended OTM bull-call-spread strikes.
 *
 * <p>A bare 52-week-high cross carries no reliable edge. This component layers five filters that,
 * in a two-year study of NSE F&amp;O equities, were the ones that separated the breakouts which ran
 * from the ones which faked out:
 *
 * <ol>
 *   <li><b>Volume participation</b> — breakout volume &gt; {@code minVolumeRatio}× its 20-day
 *       average.
 *   <li><b>Long-term trend</b> — close above the 200-day simple moving average.
 *   <li><b>Momentum</b> — 20-session return positive.
 *   <li><b>Relative strength</b> — the stock's 20d momentum beats NIFTY's.
 *   <li><b>Market regime</b> — NIFTY 50 itself above its 200-day moving average.
 * </ol>
 *
 * <p>All five must hold for {@code tradeReady=true}. The strikes place a defined-risk debit spread:
 * buy ATM+{@code longOffset}, sell ATM+{@code shortOffset}, struck off the breakout close.
 *
 * <p>Stateless and side-effect free so it can be unit-tested against synthetic series.
 */
@Component
public class BreakoutTradeSetupCalculator {

    private static final int SCALE = 4;
    private static final int SMA_PERIOD = 200;
    private static final int MOMENTUM_PERIOD = 20;
    private static final int VOLUME_PERIOD = 20;

    private final double minVolumeRatio;
    private final int longStrikeOffset;
    private final int shortStrikeOffset;

    @Autowired
    public BreakoutTradeSetupCalculator(FiftyTwoHighProperties properties) {
        this(
                properties != null ? properties.getTradeSetupMinVolumeRatio() : 1.3,
                properties != null ? properties.getTradeSetupLongStrikeOffset() : 4,
                properties != null ? properties.getTradeSetupShortStrikeOffset() : 8);
    }

    /** Deterministic constructor for tests and explicit tuning. */
    public BreakoutTradeSetupCalculator(
            double minVolumeRatio, int longStrikeOffset, int shortStrikeOffset) {
        this.minVolumeRatio = minVolumeRatio;
        this.longStrikeOffset = Math.max(1, longStrikeOffset);
        this.shortStrikeOffset = Math.max(this.longStrikeOffset + 1, shortStrikeOffset);
    }

    /**
     * Evaluates a symbol's daily candles (already known to be a close-confirmed breakout) against
     * the five filters and derives the spread strikes.
     *
     * @param symbol NSE symbol
     * @param stockDaily chronological (oldest-to-newest) daily candles for the symbol
     * @param niftyDaily chronological daily candles for the NIFTY 50 index (nullable)
     * @param lotSize F&amp;O lot size for context
     * @return a {@link BreakoutTradeSetup}; never null (all-fail when history is too thin)
     */
    public BreakoutTradeSetup evaluate(
            String symbol, List<Candle> stockDaily, List<Candle> niftyDaily, int lotSize) {
        int li = latestIndex(stockDaily);
        if (li < 0) {
            return notReady(symbol, lotSize);
        }
        Candle latest = stockDaily.get(li);
        BigDecimal spot = latest.close();

        // 1. Volume participation vs the 20-day average.
        BigDecimal volumeRatio = volumeRatio(stockDaily, li);
        boolean volumeConfirmed = volumeRatio != null && volumeRatio.doubleValue() > minVolumeRatio;

        // 2. Long-term trend: close above the 200-day SMA.
        boolean above200 = false;
        if (li >= SMA_PERIOD - 1) {
            BigDecimal sma = sma(stockDaily, li, SMA_PERIOD);
            above200 = sma != null && spot.compareTo(sma) > 0;
        }

        // 3. Momentum: 20-session return.
        BigDecimal momentum = pctChange(spot, closeAt(stockDaily, li - MOMENTUM_PERIOD));
        boolean momentumPositive = momentum != null && momentum.signum() > 0;

        // 4/5. Relative strength vs NIFTY, and NIFTY's own regime.
        int nli = latestIndex(niftyDaily);
        BigDecimal niftyMomentum =
                nli >= MOMENTUM_PERIOD
                        ? pctChange(
                                closeAt(niftyDaily, nli),
                                closeAt(niftyDaily, nli - MOMENTUM_PERIOD))
                        : null;
        boolean marketUptrend = false;
        if (nli >= SMA_PERIOD - 1) {
            BigDecimal niftySma = sma(niftyDaily, nli, SMA_PERIOD);
            marketUptrend = niftySma != null && closeAt(niftyDaily, nli).compareTo(niftySma) > 0;
        }
        BigDecimal relativeStrength =
                (momentum != null && niftyMomentum != null)
                        ? momentum.subtract(niftyMomentum).setScale(2, RoundingMode.HALF_UP)
                        : null;
        boolean relativeStrengthPositive =
                relativeStrength != null && relativeStrength.signum() > 0;

        boolean tradeReady =
                volumeConfirmed
                        && above200
                        && momentumPositive
                        && relativeStrengthPositive
                        && marketUptrend;

        return new BreakoutTradeSetup(
                symbol,
                tradeReady,
                scale(volumeRatio),
                volumeConfirmed,
                above200,
                scale(momentum),
                momentumPositive,
                relativeStrength,
                relativeStrengthPositive,
                marketUptrend,
                scale(spot),
                scale(strikes(spot)[0]),
                scale(strikes(spot)[1]),
                scale(strikes(spot)[2]),
                lotSize);
    }

    /** All-fail placeholder used when there is no usable candle to evaluate. */
    private static BreakoutTradeSetup notReady(String symbol, int lotSize) {
        return new BreakoutTradeSetup(
                symbol, false, null, false, false, null, false, null, false, false, null, null,
                null, null, lotSize);
    }

    /**
     * Derives ATM, long-leg and short-leg strikes from a reference price. Returns {@code {null,
     * null, null}} when the price is unusable.
     */
    private BigDecimal[] strikes(BigDecimal spot) {
        if (spot == null || spot.signum() <= 0) {
            return new BigDecimal[] {null, null, null};
        }
        BigDecimal step = strikeStep(spot);
        BigDecimal atm = spot.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        BigDecimal buy = atm.add(step.multiply(BigDecimal.valueOf(longStrikeOffset)));
        BigDecimal sell = atm.add(step.multiply(BigDecimal.valueOf(shortStrikeOffset)));
        return new BigDecimal[] {atm, buy, sell};
    }

    /** Index of the most recent candle with a usable close (timestamp + close), or -1. */
    private static int latestIndex(List<Candle> candles) {
        if (candles == null) {
            return -1;
        }
        for (int i = candles.size() - 1; i >= 0; i--) {
            Candle c = candles.get(i);
            if (c != null && c.timestamp() != null && c.close() != null) {
                return i;
            }
        }
        return -1;
    }

    /** Close at an index, or null when absent. */
    private static BigDecimal closeAt(List<Candle> candles, int idx) {
        if (candles == null || idx < 0 || idx >= candles.size()) {
            return null;
        }
        Candle c = candles.get(idx);
        return c != null ? c.close() : null;
    }

    /** Simple moving average of {@code period} closes ending at {@code endIdx} (inclusive). */
    private static BigDecimal sma(List<Candle> candles, int endIdx, int period) {
        if (candles == null || endIdx < period - 1) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endIdx - period + 1; i <= endIdx; i++) {
            Candle c = candles.get(i);
            if (c == null || c.close() == null) {
                return null;
            }
            sum = sum.add(c.close());
        }
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    /** Latest volume divided by the mean of the last {@link #VOLUME_PERIOD} volumes. */
    private static BigDecimal volumeRatio(List<Candle> candles, int latestIdx) {
        if (candles == null || latestIdx < VOLUME_PERIOD - 1) {
            return null;
        }
        double sum = 0;
        for (int i = latestIdx - VOLUME_PERIOD + 1; i <= latestIdx; i++) {
            Candle c = candles.get(i);
            if (c == null) {
                return null;
            }
            sum += c.volume();
        }
        double avg = sum / VOLUME_PERIOD;
        if (avg <= 0) {
            return null;
        }
        return BigDecimal.valueOf(candles.get(latestIdx).volume() / avg);
    }

    /** (new - base) / base * 100, or null when the base is missing/non-positive. */
    private static BigDecimal pctChange(BigDecimal newV, BigDecimal base) {
        if (base == null || base.signum() <= 0 || newV == null) {
            return null;
        }
        return newV.subtract(base)
                .multiply(BigDecimal.valueOf(100))
                .divide(base, SCALE, RoundingMode.HALF_UP);
    }

    /** NSE strike step for a price tier (100 / 50 / 20 / 10 / 5 / 2.5). */
    private static BigDecimal strikeStep(BigDecimal price) {
        double p = price.doubleValue();
        if (p >= 5000) {
            return BigDecimal.valueOf(100);
        }
        if (p >= 2000) {
            return BigDecimal.valueOf(50);
        }
        if (p >= 1000) {
            return BigDecimal.valueOf(20);
        }
        if (p >= 500) {
            return BigDecimal.valueOf(10);
        }
        if (p >= 200) {
            return BigDecimal.valueOf(5);
        }
        return new BigDecimal("2.5");
    }

    /** Scale helper that leaves nulls as null. */
    private static BigDecimal scale(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP) : null;
    }
}

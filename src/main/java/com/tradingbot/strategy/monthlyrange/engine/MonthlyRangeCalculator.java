package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calculates monthly volatility forecasts, 1-SD and 2-SD price channels, and safe option strike
 * selection for NSE stocks and indices.
 */
@Component
public class MonthlyRangeCalculator {

    private static final Logger log = LoggerFactory.getLogger(MonthlyRangeCalculator.class);

    private final Garch11Optimizer garchOptimizer;
    private final GarchVolForecaster volForecaster;

    public MonthlyRangeCalculator(
            Garch11Optimizer garchOptimizer, GarchVolForecaster volForecaster) {
        this.garchOptimizer = garchOptimizer;
        this.volForecaster = volForecaster;
    }

    /**
     * Calculates the monthly range forecast for the specified symbol and daily candle history.
     *
     * @param rawSymbol clean or prefixed symbol (e.g. "RELIANCE", "NSE:TCS", "NIFTY50")
     * @param dailyCandles historical daily candles (at least 30-500 bars recommended)
     * @param horizonDays forward trading days horizon (typically 22)
     * @return populated MonthlyRangeForecast
     */
    public MonthlyRangeForecast calculate(
            String rawSymbol, List<Candle> dailyCandles, int horizonDays) {
        String cleanSymbol = cleanSymbol(rawSymbol);
        int horizon = horizonDays > 0 ? horizonDays : 22;

        if (dailyCandles == null || dailyCandles.isEmpty()) {
            log.warn("[MONTHLY-RANGE] No candle data available for symbol {}", cleanSymbol);
            BigDecimal defaultStep = resolveStrikeStep(cleanSymbol);
            return new MonthlyRangeForecast(
                    cleanSymbol,
                    BigDecimal.ZERO,
                    0.0,
                    0.0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    defaultStep,
                    0.0,
                    BigDecimal.ZERO,
                    horizon,
                    false,
                    "NO_DATA",
                    2.0,
                    BigDecimal.ZERO,
                    null,
                    null,
                    Instant.now());
        }

        Candle latestCandle = dailyCandles.getLast();
        BigDecimal spotPrice = latestCandle.close();
        double spot = spotPrice.doubleValue();

        // 1. Calculate Daily Log Returns
        int candleCount = dailyCandles.size();
        double[] returns = new double[Math.max(0, candleCount - 1)];
        for (int i = 1; i < candleCount; i++) {
            double prevClose = dailyCandles.get(i - 1).close().doubleValue();
            double curClose = dailyCandles.get(i).close().doubleValue();
            returns[i - 1] = Math.log(curClose / prevClose);
        }

        // 2. Fit GARCH(1,1) Model & Project Multi-Step Volatility
        GarchModelParams garchParams = garchOptimizer.fit(returns);
        GarchVolForecaster.VolForecastResult volResult =
                volForecaster.forecastMonthlyVol(garchParams, returns, horizon);

        double monthlyVolDec = volResult.cumulativeMonthlyVolDecimal();
        double monthlyVolPct = volResult.cumulativeMonthlyVolPct();
        double annualizedVolPct = volResult.annualizedVolPct();

        // 3. Compute 1-SD (~68.3%) and 2-SD (~95.4%) Price Channels
        double lower1SdVal = spot * Math.exp(-1.0 * monthlyVolDec);
        double upper1SdVal = spot * Math.exp(+1.0 * monthlyVolDec);
        double lower2SdVal = spot * Math.exp(-2.0 * monthlyVolDec);
        double upper2SdVal = spot * Math.exp(+2.0 * monthlyVolDec);

        BigDecimal lower1Sd = BigDecimal.valueOf(lower1SdVal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper1Sd = BigDecimal.valueOf(upper1SdVal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower2Sd = BigDecimal.valueOf(lower2SdVal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper2Sd = BigDecimal.valueOf(upper2SdVal).setScale(2, RoundingMode.HALF_UP);

        // 4. Resolve NSE Strike Step & Snap Safe PE/CE Strikes
        BigDecimal strikeStep = resolveStrikeStep(cleanSymbol);
        BigDecimal safePeStrike = snapStrikeDown(lower2Sd, strikeStep);
        BigDecimal safeCeStrike = snapStrikeUp(upper2Sd, strikeStep);

        // 5. Compute Historical Volatility (HV-30 annualized) and ATR-22
        double hv30 = calculateHv30(returns);
        BigDecimal atr22 = calculateAtr(dailyCandles, 22);

        return new MonthlyRangeForecast(
                cleanSymbol,
                spotPrice.setScale(2, RoundingMode.HALF_UP),
                round2(monthlyVolPct),
                round2(annualizedVolPct),
                lower1Sd,
                upper1Sd,
                lower2Sd,
                upper2Sd,
                safePeStrike,
                safeCeStrike,
                strikeStep,
                round2(hv30),
                atr22,
                horizon,
                false,
                "Normal",
                2.0,
                BigDecimal.ZERO,
                null,
                null,
                Instant.now());
    }

    private String cleanSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "NIFTY50";
        }
        String clean = symbol.trim().toUpperCase();
        if (clean.startsWith("NSE:")) {
            clean = clean.substring(4).trim();
        }
        if ("NIFTY 50".equals(clean) || "NIFTY".equals(clean)) {
            return "NIFTY50";
        }
        return clean;
    }

    public BigDecimal resolveStrikeStep(String cleanSymbol) {
        StockFnoRegistry.InstrumentInfo info = StockFnoRegistry.get(cleanSymbol);
        if (info != null
                && info.strikeStep() != null
                && info.strikeStep().compareTo(BigDecimal.ZERO) > 0) {
            return info.strikeStep();
        }

        // Fallbacks if not present in registry
        if (StockFnoRegistry.isIndex(cleanSymbol)) {
            return BigDecimal.valueOf(50.0);
        }
        return BigDecimal.valueOf(10.0);
    }

    /**
     * Floors a boundary price down to the nearest integer multiple of step: floor(price / step) *
     * step
     */
    public BigDecimal snapStrikeDown(BigDecimal price, BigDecimal step) {
        if (price == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        BigDecimal div = price.divide(step, 0, RoundingMode.FLOOR);
        return div.multiply(step).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Ceils a boundary price up to the nearest integer multiple of step: ceil(price / step) * step
     */
    public BigDecimal snapStrikeUp(BigDecimal price, BigDecimal step) {
        if (price == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        BigDecimal div = price.divide(step, 0, RoundingMode.CEILING);
        return div.multiply(step).setScale(2, RoundingMode.HALF_UP);
    }

    private double calculateHv30(double[] returns) {
        if (returns == null || returns.length < 2) {
            return 0.0;
        }
        int lookback = Math.min(returns.length, 30);
        int start = returns.length - lookback;

        double sum = 0.0;
        for (int i = start; i < returns.length; i++) {
            sum += returns[i];
        }
        double mean = sum / lookback;

        double sumSq = 0.0;
        for (int i = start; i < returns.length; i++) {
            double diff = returns[i] - mean;
            sumSq += diff * diff;
        }
        double sampleStd = Math.sqrt(sumSq / (lookback - 1));
        return sampleStd * Math.sqrt(252.0) * 100.0;
    }

    private BigDecimal calculateAtr(List<Candle> candles, int period) {
        if (candles == null || candles.size() < 2) {
            return BigDecimal.ZERO;
        }
        int count = candles.size();
        int lookback = Math.min(count - 1, period);
        int start = count - lookback;

        double trSum = 0.0;
        for (int i = start; i < count; i++) {
            Candle cur = candles.get(i);
            Candle prev = candles.get(i - 1);

            double high = cur.high().doubleValue();
            double low = cur.low().doubleValue();
            double prevClose = prev.close().doubleValue();

            double tr =
                    Math.max(
                            high - low,
                            Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trSum += tr;
        }

        double atr = trSum / lookback;
        return BigDecimal.valueOf(atr).setScale(2, RoundingMode.HALF_UP);
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}

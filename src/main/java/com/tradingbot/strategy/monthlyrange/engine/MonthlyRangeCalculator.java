package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.util.NseTradingCalendarUtil;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Calculates monthly volatility forecasts, 1-SD and dynamic asymmetric 2-SD price channels
 * (incorporating negative return skew), ATM straddle implied moves, and institutional Open Interest
 * (OI) support/resistance strike boundaries.
 */
@Component
public class MonthlyRangeCalculator {

    private static final Logger log = LoggerFactory.getLogger(MonthlyRangeCalculator.class);

    private final Garch11Optimizer garchOptimizer;
    private final GarchVolForecaster volForecaster;
    private final MonthlyRangeProperties properties;

    public MonthlyRangeCalculator(
            Garch11Optimizer garchOptimizer, GarchVolForecaster volForecaster) {
        this(garchOptimizer, volForecaster, new MonthlyRangeProperties());
    }

    @Autowired
    public MonthlyRangeCalculator(
            Garch11Optimizer garchOptimizer,
            GarchVolForecaster volForecaster,
            MonthlyRangeProperties properties) {
        this.garchOptimizer = garchOptimizer;
        this.volForecaster = volForecaster;
        this.properties = properties != null ? properties : new MonthlyRangeProperties();
    }

    /**
     * Calculates the monthly range forecast using default current IST date and without live option
     * chain.
     */
    public MonthlyRangeForecast calculate(
            String rawSymbol, List<Candle> dailyCandles, int horizonDays) {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        return calculate(rawSymbol, dailyCandles, horizonDays, today, null);
    }

    /**
     * Calculates the fused monthly range forecast with asymmetric event detection and Option Chain
     * OI integration.
     */
    public MonthlyRangeForecast calculate(
            String rawSymbol,
            List<Candle> dailyCandles,
            int horizonDays,
            LocalDate asOfDate,
            OptionChainResponse optionChain) {
        String cleanSymbol = cleanSymbol(rawSymbol);
        int horizon = horizonDays > 0 ? horizonDays : properties.getForecastHorizonDays();
        LocalDate date =
                asOfDate != null ? asOfDate : LocalDate.now(NseTradingCalendarUtil.IST_ZONE);

        BigDecimal strikeStep = resolveStrikeStep(cleanSymbol);

        if (dailyCandles == null || dailyCandles.isEmpty()) {
            log.warn("[MONTHLY-RANGE] No candle data available for symbol {}", cleanSymbol);
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
                    strikeStep,
                    0.0,
                    BigDecimal.ZERO,
                    horizon,
                    false,
                    "NO_DATA",
                    properties.getNormalPeMultiplier(),
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

        // 3. Event / Earnings Detection & Asymmetric Confidence Multipliers (kPE, kCE)
        boolean isEventMonth = isEarningsMonth(date);
        String eventReason =
                isEventMonth ? resolveQuarterName(date) + " Earnings Cycle" : "Normal Month";
        double kPe =
                isEventMonth
                        ? properties.getEventPeMultiplier()
                        : properties.getNormalPeMultiplier();
        double kCe =
                isEventMonth
                        ? properties.getEventCeMultiplier()
                        : properties.getNormalCeMultiplier();

        // 4. Compute 1-SD (~68.3%) and Asymmetric Dynamic k-SD Price Channels
        double lower1SdVal = spot * Math.exp(-1.0 * monthlyVolDec);
        double upper1SdVal = spot * Math.exp(+1.0 * monthlyVolDec);
        double lower2SdVal = spot * Math.exp(-kPe * monthlyVolDec);
        double upper2SdVal = spot * Math.exp(+kCe * monthlyVolDec);

        BigDecimal lower1Sd = BigDecimal.valueOf(lower1SdVal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper1Sd = BigDecimal.valueOf(upper1SdVal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower2Sd = BigDecimal.valueOf(lower2SdVal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper2Sd = BigDecimal.valueOf(upper2SdVal).setScale(2, RoundingMode.HALF_UP);

        // 5. GARCH Base Snapped Strikes
        BigDecimal garchPeStrike = snapStrikeDown(lower2Sd, strikeStep);
        BigDecimal garchCeStrike = snapStrikeUp(upper2Sd, strikeStep);

        // 6. Option Chain Straddle Move & Institutional OI Walls
        BigDecimal maxCallOiStrike = null;
        BigDecimal maxPutOiStrike = null;
        BigDecimal atmStraddleMove = BigDecimal.ZERO;

        if (optionChain != null) {
            maxCallOiStrike = optionChain.maxCallOiStrike();
            maxPutOiStrike = optionChain.maxPutOiStrike();
            atmStraddleMove = extractAtmStraddleMove(optionChain, spotPrice);
        } else {
            // Theoretical ATM Straddle approximation: ~ 0.8 * Spot * monthlyVol
            double approxStraddle = 0.80 * spot * monthlyVolDec;
            atmStraddleMove = BigDecimal.valueOf(approxStraddle).setScale(2, RoundingMode.HALF_UP);
        }

        // 7. Fused Conservative Strike Selection (Take outer protective strike)
        BigDecimal safePeStrike = garchPeStrike;
        if (maxPutOiStrike != null && maxPutOiStrike.compareTo(BigDecimal.ZERO) > 0) {
            if (maxPutOiStrike.compareTo(safePeStrike) < 0) {
                safePeStrike = snapStrikeDown(maxPutOiStrike, strikeStep);
            }
        }

        BigDecimal safeCeStrike = garchCeStrike;
        if (maxCallOiStrike != null && maxCallOiStrike.compareTo(BigDecimal.ZERO) > 0) {
            if (maxCallOiStrike.compareTo(safeCeStrike) > 0) {
                safeCeStrike = snapStrikeUp(maxCallOiStrike, strikeStep);
            }
        }

        // 8. Compute Historical Volatility (HV-30 annualized) and ATR-22
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
                isEventMonth,
                eventReason,
                kPe,
                atmStraddleMove,
                maxCallOiStrike,
                maxPutOiStrike,
                Instant.now());
    }

    private boolean isEarningsMonth(LocalDate date) {
        if (date == null) return false;
        int month = date.getMonthValue();
        return properties.getEarningsMonths().contains(month);
    }

    private String resolveQuarterName(LocalDate date) {
        if (date == null) return "Quarterly";
        int month = date.getMonthValue();
        return switch (month) {
            case 1, 2 -> "Q3";
            case 4, 5 -> "Q4 (Annual)";
            case 7, 8 -> "Q1";
            case 10, 11 -> "Q2";
            default -> "Quarterly";
        };
    }

    /**
     * Resolves the nearest strike to current spot price and calculates total ATM Straddle premium.
     */
    private BigDecimal extractAtmStraddleMove(OptionChainResponse chain, BigDecimal spot) {
        if (chain == null || chain.strikes() == null || chain.strikes().isEmpty() || spot == null) {
            return BigDecimal.ZERO;
        }

        double spotVal = spot.doubleValue();
        OptionStrike nearestStrike =
                chain.strikes().stream()
                        .filter(s -> s.strikePrice() != null)
                        .min(
                                Comparator.comparingDouble(
                                        s -> Math.abs(s.strikePrice().doubleValue() - spotVal)))
                        .orElse(null);

        if (nearestStrike != null) {
            BigDecimal callLtp =
                    (nearestStrike.call() != null && nearestStrike.call().ltp() != null)
                            ? nearestStrike.call().ltp()
                            : BigDecimal.ZERO;
            BigDecimal putLtp =
                    (nearestStrike.put() != null && nearestStrike.put().ltp() != null)
                            ? nearestStrike.put().ltp()
                            : BigDecimal.ZERO;
            BigDecimal sum = callLtp.add(putLtp);
            if (sum.compareTo(BigDecimal.ZERO) > 0) {
                return sum.setScale(2, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
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

        if (StockFnoRegistry.isIndex(cleanSymbol)) {
            return BigDecimal.valueOf(50.0);
        }
        return BigDecimal.valueOf(10.0);
    }

    public BigDecimal snapStrikeDown(BigDecimal price, BigDecimal step) {
        if (price == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        BigDecimal div = price.divide(step, 0, RoundingMode.FLOOR);
        return div.multiply(step).setScale(2, RoundingMode.HALF_UP);
    }

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

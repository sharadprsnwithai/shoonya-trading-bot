package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.strategy.monthlyrange.model.BrokenWingButterflyRecommendation;
import com.tradingbot.strategy.monthlyrange.model.ConvictionLevel;
import com.tradingbot.strategy.monthlyrange.model.GjrGarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.MarketBias;
import com.tradingbot.strategy.monthlyrange.model.MonthlyDirectionalScore;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Multi-factor quantitative engine calculating monthly directional probability across:
 *
 * <ol>
 *   <li>Macro Trend Regime (30%): Spot vs 200 EMA & 50 EMA
 *   <li>Short-Term Momentum (25%): 20 SMA, 14 RSI, MACD
 *   <li>GJR-GARCH Asymmetric Leverage (20%): Downside panic volatility skew (gamma)
 *   <li>Option Market Flow (25%): Monthly PCR and OI Support/Resistance
 * </ol>
 *
 * <p>When conviction is {@ge 65%}, recommends a 1x2x1 Broken-Wing Butterfly (Buy 1x 4OTM, Sell 2x
 * 8OTM, Buy 1x 10OTM) in CE (if Bullish) or PE (if Bearish).
 */
@Component
public class MonthlyDirectionalProbabilityEngine {

    /**
     * Evaluates the monthly directional probability score for an underlying.
     *
     * @param symbol underlying ticker symbol
     * @param spotPrice reference spot price
     * @param dailyCandles historical daily candles (at least 20 trading days required)
     * @param optionChain live or cached option chain (optional, may be null)
     * @param gjrParams fitted GJR-GARCH parameters (optional, may be null)
     * @param strongThreshold threshold for strong conviction (e.g. 65.0)
     * @return complete directional score and butterfly recommendation
     */
    public MonthlyDirectionalScore evaluateDirectionalScore(
            String symbol,
            BigDecimal spotPrice,
            List<Candle> dailyCandles,
            OptionChainResponse optionChain,
            GjrGarchModelParams gjrParams,
            double strongThreshold) {

        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackScore(symbol, spotPrice, "Spot price unavailable");
        }

        if (dailyCandles == null || dailyCandles.size() < 20) {
            return fallbackScore(symbol, spotPrice, "Insufficient historical candles");
        }

        double[] closes = dailyCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        int n = closes.length;
        double currentClose = spotPrice.doubleValue();

        Map<String, Double> factorScores = new LinkedHashMap<>();
        double bullPoints = 0.0;
        double bearPoints = 0.0;

        // -------------------------------------------------------------
        // 1. Macro Trend Regime (Weight: 30 pts)
        // -------------------------------------------------------------
        double ema200 = calculateEma(closes, Math.min(200, n));
        double ema50 = calculateEma(closes, Math.min(50, n));

        double trendBull = 0.0;
        double trendBear = 0.0;
        double ema200DiffPct = ema200 > 0.0 ? (currentClose - ema200) / ema200 : 0.0;
        if (ema200DiffPct > 0.005) {
            trendBull += 18.0;
        } else if (ema200DiffPct < -0.005) {
            trendBear += 18.0;
        } else {
            // Flat around 200 EMA
            trendBull += 9.0;
            trendBear += 9.0;
        }

        double emaCrossPct = ema200 > 0.0 ? (ema50 - ema200) / ema200 : 0.0;
        if (emaCrossPct > 0.005) {
            trendBull += 12.0;
        } else if (emaCrossPct < -0.005) {
            trendBear += 12.0;
        } else {
            // Flat cross
            trendBull += 6.0;
            trendBear += 6.0;
        }
        bullPoints += trendBull;
        bearPoints += trendBear;
        factorScores.put("trendBull", trendBull);
        factorScores.put("trendBear", trendBear);

        // -------------------------------------------------------------
        // 2. Short-Term Momentum (Weight: 25 pts)
        // -------------------------------------------------------------
        double sma20 = calculateSma(closes, Math.min(20, n));
        double rsi14 = calculateRsi(closes, 14);
        double macdDiff = calculateMacdHistogram(closes);

        double momBull = 0.0;
        double momBear = 0.0;

        // 20 SMA check (8 pts)
        double sma20DiffPct = sma20 > 0.0 ? (currentClose - sma20) / sma20 : 0.0;
        if (sma20DiffPct > 0.003) {
            momBull += 8.0;
        } else if (sma20DiffPct < -0.003) {
            momBear += 8.0;
        } else {
            momBull += 4.0;
            momBear += 4.0;
        }

        // RSI 14 check (12 pts)
        if (rsi14 >= 55.0) {
            momBull += 12.0;
        } else if (rsi14 <= 45.0) {
            momBear += 12.0;
        } else {
            // Neutral RSI range 45-55 splits evenly
            momBull += 6.0;
            momBear += 6.0;
        }

        // MACD Histogram check (5 pts)
        if (Math.abs(macdDiff) < 0.05) {
            momBull += 2.5;
            momBear += 2.5;
        } else if (macdDiff > 0.0) {
            momBull += 5.0;
        } else {
            momBear += 5.0;
        }

        bullPoints += momBull;
        bearPoints += momBear;
        factorScores.put("momentumBull", momBull);
        factorScores.put("momentumBear", momBear);

        // -------------------------------------------------------------
        // 3. GJR-GARCH Downside Volatility Asymmetry (Weight: 20 pts)
        // -------------------------------------------------------------
        double gjrBull = 10.0;
        double gjrBear = 10.0;
        if (gjrParams != null && gjrParams.converged()) {
            double gamma = gjrParams.gamma();
            // In equity indices, gamma > 0.05 indicates high downside panic leverage
            if (gamma > 0.05) {
                if (currentClose < ema50 || rsi14 < 50.0) {
                    // Elevated downside vulnerability
                    gjrBear += 8.0;
                    gjrBull -= 8.0;
                } else {
                    // Strong trend holding despite downside skew
                    gjrBull += 4.0;
                    gjrBear -= 4.0;
                }
            }
        }
        bullPoints += gjrBull;
        bearPoints += gjrBear;
        factorScores.put("gjrBull", gjrBull);
        factorScores.put("gjrBear", gjrBear);

        // -------------------------------------------------------------
        // 4. Option Chain Money Flow / PCR / OI (Weight: 25 pts)
        // -------------------------------------------------------------
        double optBull = 12.5;
        double optBear = 12.5;
        if (optionChain != null && !optionChain.synthetic()) {
            double pcr = optionChain.pcr();
            if (pcr > 1.20) {
                optBull += 8.5;
                optBear -= 8.5;
            } else if (pcr < 0.80) {
                optBear += 8.5;
                optBull -= 8.5;
            }

            // OI Resistance / Support proximity check
            BigDecimal maxCall = optionChain.maxCallOiStrike();
            BigDecimal maxPut = optionChain.maxPutOiStrike();
            if (maxCall != null && maxPut != null && maxCall.compareTo(maxPut) > 0) {
                double distToCall = maxCall.doubleValue() - currentClose;
                double distToPut = currentClose - maxPut.doubleValue();
                if (distToCall > 0 && distToPut > 0) {
                    if (distToPut > distToCall * 1.5) {
                        // Spot closer to resistance, room above put floor
                        optBear += 4.0;
                        optBull -= 4.0;
                    } else if (distToCall > distToPut * 1.5) {
                        // Spot closer to support, room below call resistance
                        optBull += 4.0;
                        optBear -= 4.0;
                    }
                }
            }
        }
        bullPoints += optBull;
        bearPoints += optBear;
        factorScores.put("optionBull", optBull);
        factorScores.put("optionBear", optBear);

        // -------------------------------------------------------------
        // 5. Total Probability Calculation & Clamping
        // -------------------------------------------------------------
        double totalPoints = bullPoints + bearPoints;
        double rawBullPct = (bullPoints / totalPoints) * 100.0;
        // Dampen extreme statistical predictions to realistic market bounds [15%, 85%]
        double bullPct = Math.max(15.0, Math.min(85.0, rawBullPct));
        double bearPct = 100.0 - bullPct;

        bullPct = round1(bullPct);
        bearPct = round1(bearPct);

        MarketBias bias = MarketBias.fromBullishProbability(bullPct);
        ConvictionLevel conviction = ConvictionLevel.fromProbability(bullPct, strongThreshold);

        BrokenWingButterflyRecommendation butterfly =
                buildButterflyRecommendation(
                        symbol, spotPrice, bias, conviction, bullPct, strongThreshold);

        return new MonthlyDirectionalScore(
                symbol, spotPrice, bullPct, bearPct, bias, conviction, factorScores, butterfly);
    }

    /**
     * Builds the 1x2x1 Broken-Wing Butterfly recommendation: Buy 1x 4OTM, Sell 2x 8OTM, Buy 1x
     * 10OTM in CE (if Bullish) or PE (if Bearish).
     */
    public BrokenWingButterflyRecommendation buildButterflyRecommendation(
            String symbol,
            BigDecimal spotPrice,
            MarketBias bias,
            ConvictionLevel conviction,
            double bullPct,
            double strongThreshold) {

        BigDecimal atmStrike = StockFnoRegistry.calculateAtmStrike(symbol, spotPrice);
        if (atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
            atmStrike = spotPrice.setScale(0, RoundingMode.HALF_UP);
        }

        boolean actionable = conviction == ConvictionLevel.STRONG && bias != MarketBias.NEUTRAL;

        if (bias == MarketBias.BULLISH) {
            BigDecimal wing1 = StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "CE", 4);
            BigDecimal body = StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "CE", 8);
            BigDecimal wing2 = StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "CE", 10);
            String reason =
                    actionable
                            ? String.format(
                                    "Bullish conviction %.1f%% >= %.1f%% threshold",
                                    bullPct, strongThreshold)
                            : String.format(
                                    "Bullish tilt %.1f%% below %.1f%% conviction threshold",
                                    bullPct, strongThreshold);

            return new BrokenWingButterflyRecommendation(
                    bias,
                    "1x2x1 Broken-Wing Call Butterfly",
                    atmStrike,
                    wing1,
                    1,
                    body,
                    2,
                    wing2,
                    1,
                    "CE",
                    actionable,
                    reason);
        } else if (bias == MarketBias.BEARISH) {
            BigDecimal wing1 = StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "PE", 4);
            BigDecimal body = StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "PE", 8);
            BigDecimal wing2 = StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "PE", 10);
            double bearPct = 100.0 - bullPct;
            String reason =
                    actionable
                            ? String.format(
                                    "Bearish conviction %.1f%% >= %.1f%% threshold",
                                    bearPct, strongThreshold)
                            : String.format(
                                    "Bearish tilt %.1f%% below %.1f%% conviction threshold",
                                    bearPct, strongThreshold);

            return new BrokenWingButterflyRecommendation(
                    bias,
                    "1x2x1 Broken-Wing Put Butterfly",
                    atmStrike,
                    wing1,
                    1,
                    body,
                    2,
                    wing2,
                    1,
                    "PE",
                    actionable,
                    reason);
        } else {
            return new BrokenWingButterflyRecommendation(
                    MarketBias.NEUTRAL,
                    "Neutral / Delta-Neutral GARCH Strangle Recommended",
                    atmStrike,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    0,
                    null,
                    false,
                    String.format(
                            "Directional probability %.1f%% is neutral — hold delta-neutral GARCH band instead",
                            bullPct));
        }
    }

    private MonthlyDirectionalScore fallbackScore(
            String symbol, BigDecimal spotPrice, String reason) {
        BigDecimal spot = spotPrice != null ? spotPrice : BigDecimal.ZERO;
        BrokenWingButterflyRecommendation empty =
                new BrokenWingButterflyRecommendation(
                        MarketBias.NEUTRAL,
                        "Data Unavailable",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0,
                        BigDecimal.ZERO,
                        0,
                        BigDecimal.ZERO,
                        0,
                        null,
                        false,
                        reason);

        return new MonthlyDirectionalScore(
                symbol,
                spot,
                50.0,
                50.0,
                MarketBias.NEUTRAL,
                ConvictionLevel.NEUTRAL,
                Map.of(),
                empty);
    }

    // --------------------------------------------------------------------------
    // Math Helpers (EMA, SMA, RSI, MACD)
    // --------------------------------------------------------------------------

    private static double calculateEma(double[] values, int period) {
        if (values.length == 0 || period <= 0) {
            return 0.0;
        }
        int p = Math.min(period, values.length);
        double multiplier = 2.0 / (p + 1.0);
        double ema = values[0];
        for (int i = 1; i < values.length; i++) {
            ema = (values[i] - ema) * multiplier + ema;
        }
        return ema;
    }

    private static double calculateSma(double[] values, int period) {
        if (values.length == 0 || period <= 0) {
            return 0.0;
        }
        int p = Math.min(period, values.length);
        double sum = 0.0;
        for (int i = values.length - p; i < values.length; i++) {
            sum += values[i];
        }
        return sum / p;
    }

    private static double calculateRsi(double[] values, int period) {
        if (values.length <= period) {
            return 50.0;
        }
        double gains = 0.0;
        double losses = 0.0;
        for (int i = 1; i <= period; i++) {
            double diff = values[i] - values[i - 1];
            if (diff >= 0) {
                gains += diff;
            } else {
                losses -= diff;
            }
        }
        double avgGain = gains / period;
        double avgLoss = losses / period;

        for (int i = period + 1; i < values.length; i++) {
            double diff = values[i] - values[i - 1];
            if (diff >= 0) {
                avgGain = (avgGain * (period - 1) + diff) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) - diff) / period;
            }
        }

        if (avgLoss == 0.0) {
            return 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static double calculateMacdHistogram(double[] values) {
        if (values.length < 26) {
            return 0.0;
        }
        double ema12 = calculateEma(values, 12);
        double ema26 = calculateEma(values, 26);
        double macdLine = ema12 - ema26;
        double signalLine = calculateEma(new double[] {macdLine * 0.9, macdLine}, 9);
        return macdLine - signalLine;
    }

    private static double round1(double val) {
        return BigDecimal.valueOf(val).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}

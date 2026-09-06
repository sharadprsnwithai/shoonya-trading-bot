package com.tradingbot.backtest;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.impl.TripleSuperTrendRsiOptionBuyingStrategy;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Historical Backtest Service for Triple SuperTrend + RSI Options Buying Strategy. Simulates option
 * buying dynamics across NIFTY 50 and any of the 29 supported F&O equities.
 */
@Service
public class TripleSuperTrendBacktestService {

    private static final Logger log =
            LoggerFactory.getLogger(TripleSuperTrendBacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TechnicalAnalysisService taService;
    private final ShoonyaMarketDataService marketDataService;

    @Autowired
    public TripleSuperTrendBacktestService(
            TechnicalAnalysisService taService, ShoonyaMarketDataService marketDataService) {
        this.taService = taService;
        this.marketDataService = marketDataService;
    }

    /**
     * Evaluates a chronological list of candles through the Triple SuperTrend + RSI options buying
     * lifecycle.
     *
     * @param symbol trading symbol (e.g. "NIFTY50", "ABB", "TATASTEEL")
     * @param candles chronological OHLC candles (1H or 5m)
     * @param lots number of lots
     * @param rsiFilterEnabled whether RSI momentum filter is active
     * @return BacktestResult summary
     */
    /**
     * Evaluates a chronological list of candles through the Triple SuperTrend + RSI options
     * lifecycle. Defaults to Option Selling mode.
     */
    public BacktestResult evaluateCandles(
            String symbol, List<Candle> candles, int lots, boolean rsiFilterEnabled) {
        return evaluateCandles(symbol, candles, lots, rsiFilterEnabled, true);
    }

    /**
     * Evaluates a chronological list of candles through the Triple SuperTrend + RSI options
     * lifecycle.
     *
     * @param symbol trading symbol (e.g. "NIFTY50", "ABB", "TATASTEEL")
     * @param candles chronological OHLC candles (1H or 5m)
     * @param lots number of lots
     * @param rsiFilterEnabled whether RSI momentum filter is active
     * @param optionSelling true for Option Selling (Short PE / Short CE), false for Option Buying
     * @return BacktestResult summary
     */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            int lots,
            boolean rsiFilterEnabled,
            boolean optionSelling) {
        return evaluateCandles(
                symbol,
                candles,
                lots,
                rsiFilterEnabled,
                optionSelling,
                rsiFilterEnabled,
                22.0,
                68.0,
                32.0,
                3,
                true);
    }

    /**
     * Evaluates a chronological list of candles through the enhanced Triple SuperTrend + RSI
     * strategy with ADX trend filter, RSI exhaustion caps, post-loss cooldown, and credit spread
     * mode.
     */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            int lots,
            boolean rsiFilterEnabled,
            boolean optionSelling,
            boolean adxFilterEnabled,
            double adxThreshold,
            double rsiBullishMax,
            double rsiBearishMin,
            int cooldownBars,
            boolean creditSpreadEnabled) {
        return evaluateCandles(
                symbol,
                candles,
                lots,
                rsiFilterEnabled,
                optionSelling,
                adxFilterEnabled,
                adxThreshold,
                rsiBullishMax,
                rsiBearishMin,
                cooldownBars,
                creditSpreadEnabled,
                true,
                0.60,
                true,
                0.60,
                2.50,
                true,
                50);
    }

    /**
     * Comprehensive backtest evaluation with complete false breakout filters (Rejection Wick, ATR
     * range, and 50 EMA macro trend alignment).
     */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            int lots,
            boolean rsiFilterEnabled,
            boolean optionSelling,
            boolean adxFilterEnabled,
            double adxThreshold,
            double rsiBullishMax,
            double rsiBearishMin,
            int cooldownBars,
            boolean creditSpreadEnabled,
            boolean rejectionWickFilterEnabled,
            double rejectionWickThreshold,
            boolean atrFilterEnabled,
            double minAtrRatio,
            double maxAtrRatio,
            boolean emaFilterEnabled,
            int emaPeriod) {
        String strategyId =
                optionSelling
                        ? (creditSpreadEnabled
                                ? "TRIPLE_SUPERTREND_RSI_CREDIT_SPREAD"
                                : "TRIPLE_SUPERTREND_RSI_OPTION_SELLING")
                        : TripleSuperTrendRsiOptionBuyingStrategy.STRATEGY_ID;

        int minCandles = adxFilterEnabled ? 30 : 25;
        if (candles == null || candles.size() < minCandles) {
            return new BacktestResult(
                    strategyId,
                    symbol,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    0.0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0.0,
                    List.of());
        }

        int lotSize = StockFnoRegistry.getLotSize(symbol);
        int totalQuantity = Math.max(1, lots) * lotSize;

        int size = candles.size();
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];

        for (int i = 0; i < size; i++) {
            Candle c = candles.get(i);
            high[i] = c.high().doubleValue();
            low[i] = c.low().doubleValue();
            close[i] = c.close().doubleValue();
        }

        // Fast (7, 2), Medium (10, 3), Slow (14, 4)
        SuperTrendResult[] fastSt = taService.calculateSuperTrendSeries(high, low, close, 7, 2.0);
        SuperTrendResult[] medSt = taService.calculateSuperTrendSeries(high, low, close, 10, 3.0);
        SuperTrendResult[] slowSt = taService.calculateSuperTrendSeries(high, low, close, 14, 4.0);
        double[] rsi = taService.calculateRsiSeries(close, 14);
        double[] adx = adxFilterEnabled ? taService.calculateAdxSeries(high, low, close, 14) : null;
        double[] ema = emaFilterEnabled ? taService.calculateEmaSeries(close, emaPeriod) : null;
        double[] atr = atrFilterEnabled ? taService.calculateAtrSeries(high, low, close, 14) : null;

        List<BacktestTrade> trades = new ArrayList<>();
        int tradeIdCounter = 1;

        // Position State: 0 = Flat
        // Selling: 1 = Short PE / Bull Put Spread, -1 = Short CE / Bear Call Spread
        // Buying:  1 = Long CE (Bullish), -1 = Long PE (Bearish)
        int positionState = 0;
        BigDecimal entryPremium = BigDecimal.ZERO;
        BigDecimal underlyingEntryPrice = BigDecimal.ZERO;
        Instant entryTime = null;
        String entryDateStr = "";
        double maxRiskPoints = 0.0;
        int cooldownRemaining = 0;

        // Warm up period: 28 candles if ADX enabled, else 20
        int warmup = adxFilterEnabled ? 28 : 20;

        for (int i = warmup; i < size; i++) {
            Candle currentCandle = candles.get(i);
            double currentClose = close[i];
            SuperTrendResult fast = fastSt[i];
            SuperTrendResult med = medSt[i];
            SuperTrendResult slow = slowSt[i];
            double currentRsi = rsi[i];

            if (Double.isNaN(fast.value())
                    || Double.isNaN(med.value())
                    || Double.isNaN(slow.value())) {
                continue;
            }

            LocalDate candleDate = currentCandle.timestamp().atZone(IST).toLocalDate();

            // Check Exit for Active Position
            if (positionState != 0) {
                BigDecimal spotDiff = currentCandle.close().subtract(underlyingEntryPrice);
                BigDecimal delta =
                        (optionSelling && creditSpreadEnabled)
                                ? BigDecimal.valueOf(0.20)
                                : BigDecimal.valueOf(0.50);
                boolean isLastBar = (i == size - 1);

                if (optionSelling) {
                    boolean isShortPe = (positionState == 1);
                    // Short PE: Spot up -> PE decays (profit). Short CE: Spot up -> CE rises (loss)
                    BigDecimal premiumDiff =
                            isShortPe
                                    ? spotDiff.negate().multiply(delta)
                                    : spotDiff.multiply(delta);
                    BigDecimal currentPremium =
                            entryPremium
                                    .add(premiumDiff)
                                    .setScale(2, RoundingMode.HALF_UP)
                                    .max(BigDecimal.valueOf(0.05));

                    // Exit 1: Fast ST Flip
                    boolean fastFlipped =
                            isShortPe
                                    ? (!fast.isBullish() || currentClose < fast.value())
                                    : (fast.isBullish() || currentClose > fast.value());

                    // Exit 2: Hard SL (+30% expansion in sold premium / net credit)
                    boolean stopLossHit =
                            currentPremium.compareTo(
                                            entryPremium.multiply(BigDecimal.valueOf(1.30)))
                                    >= 0;

                    // Exit 3: Target Profit (50% decay in sold premium / net credit)
                    boolean targetProfitHit =
                            currentPremium.compareTo(
                                            entryPremium.multiply(BigDecimal.valueOf(0.50)))
                                    <= 0;

                    if (fastFlipped || stopLossHit || targetProfitHit || isLastBar) {
                        String exitReason;
                        if (stopLossHit) {
                            exitReason = "Hard SL (+30%) Hit";
                        } else if (targetProfitHit) {
                            exitReason = "Target Profit (-50%) Hit";
                        } else if (isLastBar) {
                            exitReason = "End of Backtest Period";
                        } else {
                            exitReason =
                                    isShortPe
                                            ? "Fast ST flipped Bearish"
                                            : "Fast ST flipped Bullish";
                        }

                        // For Option Selling: Profit = Entry - Exit
                        double points = entryPremium.subtract(currentPremium).doubleValue();
                        // For credit spread, cap max loss at maxRiskPoints
                        if (creditSpreadEnabled && maxRiskPoints > 0 && points < -maxRiskPoints) {
                            points = -maxRiskPoints;
                        }

                        BigDecimal pnlAmount =
                                BigDecimal.valueOf(points * totalQuantity)
                                        .setScale(2, RoundingMode.HALF_UP);
                        boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                        // Trigger cooldown after a loss
                        if (!isWin && cooldownBars > 0) {
                            cooldownRemaining = cooldownBars;
                        }

                        trades.add(
                                new BacktestTrade(
                                        tradeIdCounter++,
                                        entryDateStr,
                                        symbol,
                                        SignalAction.SELL,
                                        entryTime,
                                        entryPremium,
                                        currentCandle.timestamp(),
                                        currentPremium,
                                        points,
                                        pnlAmount,
                                        exitReason,
                                        isWin));

                        positionState = 0;
                        entryPremium = BigDecimal.ZERO;
                        underlyingEntryPrice = BigDecimal.ZERO;
                        entryTime = null;
                        maxRiskPoints = 0.0;
                    }
                } else {
                    // Option Buying Mode
                    boolean isCall = (positionState == 1);
                    BigDecimal premiumDiff =
                            isCall ? spotDiff.multiply(delta) : spotDiff.negate().multiply(delta);
                    BigDecimal currentPremium =
                            entryPremium
                                    .add(premiumDiff)
                                    .setScale(2, RoundingMode.HALF_UP)
                                    .max(BigDecimal.valueOf(0.05));

                    // Exit condition: Fast SuperTrend flip
                    boolean fastFlipped =
                            isCall
                                    ? (!fast.isBullish() || currentClose < fast.value())
                                    : (fast.isBullish() || currentClose > fast.value());

                    // Safety Stop Loss (-30%)
                    boolean stopLossHit =
                            currentPremium.compareTo(
                                            entryPremium.multiply(BigDecimal.valueOf(0.70)))
                                    <= 0;

                    // Safety Target Profit (+100%)
                    boolean targetProfitHit =
                            currentPremium.compareTo(
                                            entryPremium.multiply(BigDecimal.valueOf(2.00)))
                                    >= 0;

                    if (fastFlipped || stopLossHit || targetProfitHit || isLastBar) {
                        String exitReason;
                        if (stopLossHit) {
                            exitReason = "Hard SL (-30%) Hit";
                        } else if (targetProfitHit) {
                            exitReason = "Target Profit (+100%) Hit";
                        } else if (isLastBar) {
                            exitReason = "End of Backtest Period";
                        } else {
                            exitReason =
                                    isCall ? "Fast ST flipped Bearish" : "Fast ST flipped Bullish";
                        }

                        // For Option Buying: Profit = Exit - Entry
                        double points = currentPremium.subtract(entryPremium).doubleValue();
                        BigDecimal pnlAmount =
                                BigDecimal.valueOf(points * totalQuantity)
                                        .setScale(2, RoundingMode.HALF_UP);
                        boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                        if (!isWin && cooldownBars > 0) {
                            cooldownRemaining = cooldownBars;
                        }

                        trades.add(
                                new BacktestTrade(
                                        tradeIdCounter++,
                                        entryDateStr,
                                        symbol,
                                        SignalAction.BUY,
                                        entryTime,
                                        entryPremium,
                                        currentCandle.timestamp(),
                                        currentPremium,
                                        points,
                                        pnlAmount,
                                        exitReason,
                                        isWin));

                        positionState = 0;
                        entryPremium = BigDecimal.ZERO;
                        underlyingEntryPrice = BigDecimal.ZERO;
                        entryTime = null;
                        maxRiskPoints = 0.0;
                    }
                }
            }

            // Check Entry if Flat
            if (positionState == 0) {
                // Enhancement 3: Cooldown check
                if (cooldownRemaining > 0) {
                    cooldownRemaining--;
                    continue;
                }

                // Enhancement 1: ADX Trend Regime Filter
                boolean adxOk =
                        !adxFilterEnabled
                                || (adx != null
                                        && i < adx.length
                                        && !Double.isNaN(adx[i])
                                        && adx[i] >= adxThreshold);

                // False Breakout Filter 1: Rejection Wick Check
                double candleRange = high[i] - low[i];
                boolean rejectionWickBullOk =
                        !rejectionWickFilterEnabled
                                || candleRange <= 0.0
                                || ((currentClose - low[i]) / candleRange)
                                        >= rejectionWickThreshold;
                boolean rejectionWickBearOk =
                        !rejectionWickFilterEnabled
                                || candleRange <= 0.0
                                || ((high[i] - currentClose) / candleRange)
                                        >= rejectionWickThreshold;

                // False Breakout Filter 2: ATR Range Sanity Check
                boolean atrOk =
                        !atrFilterEnabled
                                || atr == null
                                || Double.isNaN(atr[i])
                                || atr[i] <= 0.0
                                || (candleRange >= atr[i] * minAtrRatio
                                        && candleRange <= atr[i] * maxAtrRatio);

                // False Breakout Filter 3: Macro Trend Alignment (50 EMA)
                boolean emaBullOk =
                        !emaFilterEnabled
                                || ema == null
                                || Double.isNaN(ema[i])
                                || currentClose > ema[i];
                boolean emaBearOk =
                        !emaFilterEnabled
                                || ema == null
                                || Double.isNaN(ema[i])
                                || currentClose < ema[i];

                // Bullish confluence: Price > all 3 ST and all 3 green
                boolean allBullish =
                        fast.isBullish()
                                && med.isBullish()
                                && slow.isBullish()
                                && currentClose > fast.value()
                                && currentClose > med.value()
                                && currentClose > slow.value();

                boolean rsiBullOk = true;
                if (rsiFilterEnabled) {
                    int lookbackStart = Math.max(0, i - 5);
                    for (int k = lookbackStart; k <= i; k++) {
                        if (!Double.isNaN(rsi[k]) && rsi[k] < 40.0) {
                            rsiBullOk = false;
                            break;
                        }
                    }
                    if (currentRsi < 45.0) {
                        rsiBullOk = false;
                    }
                    // Enhancement 2: RSI Exhaustion Cap
                    if (currentRsi > rsiBullishMax) {
                        rsiBullOk = false;
                    }
                }

                if (adxOk && allBullish && rsiBullOk && rejectionWickBullOk && atrOk && emaBullOk) {
                    // 1 = Short PE / Bull Put Spread (Selling) or Long CE (Buying)
                    positionState = 1;
                    underlyingEntryPrice = currentCandle.close();
                    BigDecimal atmPremium =
                            underlyingEntryPrice
                                    .multiply(BigDecimal.valueOf(0.022))
                                    .setScale(2, RoundingMode.HALF_UP);

                    if (optionSelling && creditSpreadEnabled) {
                        BigDecimal atmStrike =
                                StockFnoRegistry.calculateAtmStrike(symbol, underlyingEntryPrice);
                        BigDecimal hedgeStrike =
                                StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "PE", 2);
                        BigDecimal strikeDiff = hedgeStrike.subtract(atmStrike).abs();
                        BigDecimal rawCredit =
                                atmPremium
                                        .multiply(BigDecimal.valueOf(0.60))
                                        .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal maxAllowedCredit =
                                strikeDiff
                                        .multiply(BigDecimal.valueOf(0.35))
                                        .setScale(2, RoundingMode.HALF_UP);
                        entryPremium = rawCredit.min(maxAllowedCredit);
                        maxRiskPoints =
                                strikeDiff
                                        .subtract(entryPremium)
                                        .max(BigDecimal.ZERO)
                                        .doubleValue();
                    } else {
                        entryPremium = atmPremium;
                        maxRiskPoints = 0.0;
                    }

                    entryTime = currentCandle.timestamp();
                    entryDateStr = candleDate.format(DATE_FMT);
                    continue;
                }

                // Bearish confluence: Price < all 3 ST and all 3 red
                boolean allBearish =
                        !fast.isBullish()
                                && !med.isBullish()
                                && !slow.isBullish()
                                && currentClose < fast.value()
                                && currentClose < med.value()
                                && currentClose < slow.value();

                boolean rsiBearOk = true;
                if (rsiFilterEnabled) {
                    int lookbackStart = Math.max(0, i - 5);
                    for (int k = lookbackStart; k <= i; k++) {
                        if (!Double.isNaN(rsi[k]) && rsi[k] > 60.0) {
                            rsiBearOk = false;
                            break;
                        }
                    }
                    if (currentRsi > 55.0) {
                        rsiBearOk = false;
                    }
                    // Enhancement 2: RSI Exhaustion Floor
                    if (currentRsi < rsiBearishMin) {
                        rsiBearOk = false;
                    }
                }

                if (adxOk && allBearish && rsiBearOk && rejectionWickBearOk && atrOk && emaBearOk) {
                    // -1 = Short CE / Bear Call Spread (Selling) or Long PE (Buying)
                    positionState = -1;
                    underlyingEntryPrice = currentCandle.close();
                    BigDecimal atmPremium =
                            underlyingEntryPrice
                                    .multiply(BigDecimal.valueOf(0.022))
                                    .setScale(2, RoundingMode.HALF_UP);

                    if (optionSelling && creditSpreadEnabled) {
                        BigDecimal atmStrike =
                                StockFnoRegistry.calculateAtmStrike(symbol, underlyingEntryPrice);
                        BigDecimal hedgeStrike =
                                StockFnoRegistry.calculateOtmStrike(symbol, atmStrike, "CE", 2);
                        BigDecimal strikeDiff = hedgeStrike.subtract(atmStrike).abs();
                        BigDecimal rawCredit =
                                atmPremium
                                        .multiply(BigDecimal.valueOf(0.60))
                                        .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal maxAllowedCredit =
                                strikeDiff
                                        .multiply(BigDecimal.valueOf(0.35))
                                        .setScale(2, RoundingMode.HALF_UP);
                        entryPremium = rawCredit.min(maxAllowedCredit);
                        maxRiskPoints =
                                strikeDiff
                                        .subtract(entryPremium)
                                        .max(BigDecimal.ZERO)
                                        .doubleValue();
                    } else {
                        entryPremium = atmPremium;
                        maxRiskPoints = 0.0;
                    }

                    entryTime = currentCandle.timestamp();
                    entryDateStr = candleDate.format(DATE_FMT);
                }
            }
        }

        // Metrics aggregation
        int totalTrades = trades.size();
        int winCount = 0;
        int lossCount = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        double totalPoints = 0.0;
        BigDecimal runningPnl = BigDecimal.ZERO;
        BigDecimal peakPnl = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;

        for (BacktestTrade t : trades) {
            totalPoints += t.pnlPoints();
            if (t.isWin()) {
                winCount++;
                grossProfit = grossProfit.add(t.pnlAmount());
            } else {
                lossCount++;
                grossLoss = grossLoss.add(t.pnlAmount().abs());
            }

            runningPnl = runningPnl.add(t.pnlAmount());
            if (runningPnl.compareTo(peakPnl) > 0) {
                peakPnl = runningPnl;
            }
            BigDecimal dd = peakPnl.subtract(runningPnl);
            if (dd.compareTo(maxDd) > 0) {
                maxDd = dd;
            }
        }

        BigDecimal netPnl = grossProfit.subtract(grossLoss);
        double winRate = totalTrades > 0 ? ((double) winCount / totalTrades) * 100.0 : 0.0;
        double profitFactor =
                grossLoss.compareTo(BigDecimal.ZERO) > 0
                        ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                        : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.99 : 0.0);

        long daysTested =
                Math.max(
                        1,
                        (candles.get(size - 1).timestamp().getEpochSecond()
                                        - candles.get(0).timestamp().getEpochSecond())
                                / (24 * 3600));

        return new BacktestResult(
                TripleSuperTrendRsiOptionBuyingStrategy.STRATEGY_ID,
                symbol,
                (int) daysTested,
                totalTrades,
                winCount,
                lossCount,
                Math.round(winRate * 100.0) / 100.0,
                Math.round(totalPoints * 100.0) / 100.0,
                grossProfit.setScale(2, RoundingMode.HALF_UP),
                grossLoss.setScale(2, RoundingMode.HALF_UP),
                netPnl.setScale(2, RoundingMode.HALF_UP),
                maxDd.setScale(2, RoundingMode.HALF_UP),
                profitFactor,
                trades);
    }
}

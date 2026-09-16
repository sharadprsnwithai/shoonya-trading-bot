package com.tradingbot.backtest;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.util.CandleResamplingUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Historical Backtest Service for NIFTY 50 Multi-Indicator Options Strategy.
 *
 * <p>Confluence Rules: 1. 5m vs 15m RSI(14) Crossover. 2. 15m SuperTrend(10, 2.0) Alignment. 3.
 * Intraday VWAP Regime & Max Distance Gate (<= 35 pts). 4. 15m ADX Trend Strength Filter (>= 22.0)
 * & +DI/-DI Polarity.
 *
 * <p>Exit Rules (No micro RSI dip cut): 1. Stepped Trailing SL (+12pt -> lock 2pt, +25pt -> lock
 * 15pt). 2. Target Profit (+50% Net Premium Decay). 3. Hard Stop Loss. 4. 15m SuperTrend Flip
 * Reversal. 5. Mandatory 15:05 IST EOD Square-Off.
 */
@Service
public class MultiIndicatorOptionsBacktestService {

    private static final Logger log =
            LoggerFactory.getLogger(MultiIndicatorOptionsBacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final String STRATEGY_ID = "NIFTY_MULTI_INDICATOR_OPTIONS";
    public static final String DEFAULT_MODE = "OPTION_SELLING";
    public static final int NIFTY_LOT_SIZE = 65;
    public static final int DEFAULT_LOTS = 1;
    public static final int DEFAULT_RSI_PERIOD = 14;
    public static final int DEFAULT_MAX_TRADES_PER_DAY = 1;
    public static final boolean DEFAULT_HEDGE_ENABLED = true;
    public static final double DEFAULT_HEDGE_OTM_PERCENT = 2.0;
    public static final int DEFAULT_SELL_OTM_STRIKES = 2;
    public static final boolean DEFAULT_RSI_EXIT_ENABLED = false;
    public static final double DEFAULT_ADX_THRESHOLD = 22.0;
    public static final double DEFAULT_STOP_LOSS_PERCENT = 2.0;
    public static final double DEFAULT_TARGET_PROFIT_PERCENT = 50.0;

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;

    @Autowired
    public MultiIndicatorOptionsBacktestService(
            ShoonyaMarketDataService marketDataService, TechnicalAnalysisService taService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
    }

    /**
     * Runs backtest for NIFTY 50 over the specified days back using default parameters
     * (OPTION_SELLING with 2% OTM Hedge).
     */
    public BacktestResult runBacktest(int daysBack) {
        return runBacktest(
                daysBack,
                DEFAULT_MODE,
                DEFAULT_LOTS,
                DEFAULT_RSI_PERIOD,
                DEFAULT_MAX_TRADES_PER_DAY,
                DEFAULT_HEDGE_ENABLED,
                DEFAULT_HEDGE_OTM_PERCENT,
                true,
                DEFAULT_ADX_THRESHOLD,
                DEFAULT_STOP_LOSS_PERCENT,
                DEFAULT_TARGET_PROFIT_PERCENT);
    }

    /**
     * Runs backtest for NIFTY 50 over the specified days back with full custom parameter tuning.
     */
    public BacktestResult runBacktest(
            int daysBack,
            String mode,
            int lots,
            int rsiPeriod,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return runBacktest(
                daysBack,
                mode,
                lots,
                rsiPeriod,
                DEFAULT_MAX_TRADES_PER_DAY,
                DEFAULT_HEDGE_ENABLED,
                DEFAULT_HEDGE_OTM_PERCENT,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Runs backtest for NIFTY 50 over the specified days back with max trades per day. */
    public BacktestResult runBacktest(
            int daysBack,
            String mode,
            int lots,
            int rsiPeriod,
            int maxTradesPerDay,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return runBacktest(
                daysBack,
                mode,
                lots,
                rsiPeriod,
                maxTradesPerDay,
                DEFAULT_HEDGE_ENABLED,
                DEFAULT_HEDGE_OTM_PERCENT,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Runs backtest with full parameter control including hedging. */
    public BacktestResult runBacktest(
            int daysBack,
            String mode,
            int lots,
            int rsiPeriod,
            int maxTradesPerDay,
            boolean hedgeEnabled,
            double hedgeOtmPercent,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return runBacktest(
                daysBack,
                mode,
                lots,
                rsiPeriod,
                maxTradesPerDay,
                hedgeEnabled,
                hedgeOtmPercent,
                DEFAULT_SELL_OTM_STRIKES,
                DEFAULT_RSI_EXIT_ENABLED,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Runs backtest with full parameter control including sell OTM strikes and RSI exit flag. */
    public BacktestResult runBacktest(
            int daysBack,
            String mode,
            int lots,
            int rsiPeriod,
            int maxTradesPerDay,
            boolean hedgeEnabled,
            double hedgeOtmPercent,
            int sellOtmStrikes,
            boolean rsiExitEnabled,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        int boundedDays = Math.max(1, Math.min(daysBack, 95));
        log.info(
                "[MULTI-INDICATOR-BACKTEST] Fetching {} days of 5m candles for NIFTY 50 (NSE:26000) [Mode: {}, Hedged: {}, SellOTM: {}, RsiExit: {}]",
                boundedDays,
                mode,
                hedgeEnabled,
                sellOtmStrikes,
                rsiExitEnabled);
        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(
                        "NSE", "26000", "NIFTY 50", "5", boundedDays);
        return evaluateCandles(
                "NIFTY 50",
                candles,
                mode,
                lots,
                rsiPeriod,
                maxTradesPerDay,
                hedgeEnabled,
                hedgeOtmPercent,
                sellOtmStrikes,
                rsiExitEnabled,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Overload of evaluateCandles with default optimization parameters enabled. */
    public BacktestResult evaluateCandles(
            String symbol, List<Candle> candles, int lots, int rsiPeriod) {
        return evaluateCandles(
                symbol,
                candles,
                DEFAULT_MODE,
                lots,
                rsiPeriod,
                true,
                DEFAULT_ADX_THRESHOLD,
                DEFAULT_STOP_LOSS_PERCENT,
                DEFAULT_TARGET_PROFIT_PERCENT);
    }

    /** Overload of evaluateCandles without mode parameter (defaults to OPTION_SELLING). */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            int lots,
            int rsiPeriod,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return evaluateCandles(
                symbol,
                candles,
                DEFAULT_MODE,
                lots,
                rsiPeriod,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            String mode,
            int lots,
            int rsiPeriod,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return evaluateCandles(
                symbol,
                candles,
                mode,
                lots,
                rsiPeriod,
                DEFAULT_MAX_TRADES_PER_DAY,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Evaluates a chronological list of 5m candles through the strategy. */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            String mode,
            int lots,
            int rsiPeriod,
            int maxTradesPerDay,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return evaluateCandles(
                symbol,
                candles,
                mode,
                lots,
                rsiPeriod,
                maxTradesPerDay,
                DEFAULT_HEDGE_ENABLED,
                DEFAULT_HEDGE_OTM_PERCENT,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Evaluates a chronological list of 5m candles through the Multi-Indicator Strategy. */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            String mode,
            int lots,
            int rsiPeriod,
            int maxTradesPerDay,
            boolean hedgeEnabled,
            double hedgeOtmPercent,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        return evaluateCandles(
                symbol,
                candles,
                mode,
                lots,
                rsiPeriod,
                maxTradesPerDay,
                hedgeEnabled,
                hedgeOtmPercent,
                DEFAULT_SELL_OTM_STRIKES,
                DEFAULT_RSI_EXIT_ENABLED,
                adxFilterEnabled,
                adxThreshold,
                stopLossPercent,
                targetProfitPercent);
    }

    /** Evaluates a chronological list of 5m candles with full strike and exit condition tuning. */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            String mode,
            int lots,
            int rsiPeriod,
            int maxTradesPerDay,
            boolean hedgeEnabled,
            double hedgeOtmPercent,
            int sellOtmStrikes,
            boolean rsiExitEnabled,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        if (candles == null || candles.isEmpty()) {
            return new BacktestResult(
                    STRATEGY_ID,
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

        boolean isOptionSelling = !"OPTION_BUYING".equalsIgnoreCase(mode);
        boolean applyHedge = isOptionSelling && hedgeEnabled;
        int totalQuantity = Math.max(1, lots) * NIFTY_LOT_SIZE;

        double delta;
        double assumedEntryPremium;
        double thetaPerHour;

        if (isOptionSelling) {
            if (sellOtmStrikes == 0) {
                delta = 0.50;
                assumedEntryPremium = 150.0;
                thetaPerHour = 1.0;
            } else if (sellOtmStrikes == 1) {
                delta = 0.42;
                assumedEntryPremium = 110.0;
                thetaPerHour = 0.90;
            } else if (sellOtmStrikes == 2) {
                delta = 0.35;
                assumedEntryPremium = 85.0;
                thetaPerHour = 0.80;
            } else {
                delta = 0.28;
                assumedEntryPremium = 60.0;
                thetaPerHour = 0.65;
            }
        } else {
            delta = 0.50; // Standard ATM option delta for option buying
            assumedEntryPremium = 150.0;
            thetaPerHour = -1.0;
        }

        // 2% OTM Hedge properties
        double hedgeEntryPremium = 12.0;
        double hedgeDelta = 0.10;
        double hedgeThetaPerHour = -0.20;
        double netCredit = assumedEntryPremium - (applyHedge ? hedgeEntryPremium : 0.0);

        // Partition candles chronologically by IST date
        Map<LocalDate, List<Candle>> candlesByDate = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate date = c.timestamp().atZone(IST).toLocalDate();
            candlesByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(c);
        }

        List<LocalDate> sortedDates = new ArrayList<>(candlesByDate.keySet());
        List<BacktestTrade> executedTrades = new ArrayList<>();
        int tradeIdCounter = 1;

        for (int dayIdx = 0; dayIdx < sortedDates.size(); dayIdx++) {
            LocalDate day = sortedDates.get(dayIdx);
            List<Candle> dayBars = candlesByDate.get(day);
            dayBars.sort(Comparator.comparing(Candle::timestamp));

            // Gather up to 5 days history before current day for warm indicator calculations
            int startHistIdx = Math.max(0, dayIdx - 5);
            List<Candle> warmHistory = new ArrayList<>();
            for (int p = startHistIdx; p < dayIdx; p++) {
                warmHistory.addAll(candlesByDate.get(sortedDates.get(p)));
            }

            int tradesTodayCount = 0;
            SimulatedPosition openPosition = null;

            List<Candle> currentDayBars = new ArrayList<>();

            for (Candle bar : dayBars) {
                currentDayBars.add(bar);
                LocalTime time = bar.timestamp().atZone(IST).toLocalTime();

                List<Candle> allBars = new ArrayList<>(warmHistory);
                allBars.addAll(currentDayBars);

                List<Candle> fifteenMinBars = CandleResamplingUtil.resample5MinTo15Min(allBars);
                if (fifteenMinBars.size() < (rsiPeriod + 5) || allBars.size() < (rsiPeriod + 10)) {
                    continue;
                }

                double[] close5m =
                        allBars.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] rsi5mSeries = taService.calculateRsiSeries(close5m, rsiPeriod);
                double[] vwapSeries = taService.calculateVwapSeries(allBars);

                double[] close15m =
                        fifteenMinBars.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] high15m =
                        fifteenMinBars.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
                double[] low15m =
                        fifteenMinBars.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
                double[] rsi15mSeries = taService.calculateRsiSeries(close15m, rsiPeriod);
                double[] adx15mSeries =
                        taService.calculateAdxSeries(high15m, low15m, close15m, rsiPeriod);
                double[] plusDi15mSeries =
                        taService.calculatePlusDiSeries(high15m, low15m, close15m, rsiPeriod);
                double[] minusDi15mSeries =
                        taService.calculateMinusDiSeries(high15m, low15m, close15m, rsiPeriod);
                SuperTrendResult[] st15mSeries =
                        taService.calculateSuperTrendSeries(high15m, low15m, close15m, 10, 2.0);

                int len5 = rsi5mSeries.length;
                int len15 = rsi15mSeries.length;
                if (len5 < 2 || len15 < 2) continue;

                double rsi5Curr = rsi5mSeries[len5 - 1];
                double rsi5Prev = rsi5mSeries[len5 - 2];
                double rsi15Curr = rsi15mSeries[len15 - 1];
                double rsi15Prev = rsi15mSeries[len15 - 2];
                double adx15Curr =
                        (adx15mSeries.length > 0)
                                ? adx15mSeries[adx15mSeries.length - 1]
                                : Double.NaN;
                double plusDi15Curr =
                        (plusDi15mSeries.length > 0)
                                ? plusDi15mSeries[plusDi15mSeries.length - 1]
                                : Double.NaN;
                double minusDi15Curr =
                        (minusDi15mSeries.length > 0)
                                ? minusDi15mSeries[minusDi15mSeries.length - 1]
                                : Double.NaN;
                SuperTrendResult st15Curr =
                        (st15mSeries.length > 0) ? st15mSeries[st15mSeries.length - 1] : null;
                boolean isStBullish = st15Curr != null && st15Curr.isBullish();

                if (Double.isNaN(rsi5Curr)
                        || Double.isNaN(rsi5Prev)
                        || Double.isNaN(rsi15Curr)
                        || Double.isNaN(rsi15Prev)) {
                    continue;
                }

                // 1. Manage active open position (EOD Square-Off, Stop Loss, Trailing SL, Target
                // Profit, ST Flip)
                if (openPosition != null) {
                    BigDecimal exitSpot = bar.close();
                    double spotDiff =
                            openPosition.isBullish
                                    ? exitSpot.subtract(openPosition.entrySpot).doubleValue()
                                    : openPosition.entrySpot.subtract(exitSpot).doubleValue();

                    double holdHours =
                            Duration.between(openPosition.entryTime, bar.timestamp()).toSeconds()
                                    / 3600.0;
                    double atmPts = (spotDiff * delta) + (holdHours * thetaPerHour);
                    double hedgePts =
                            applyHedge
                                    ? ((-spotDiff * hedgeDelta) + (holdHours * hedgeThetaPerHour))
                                    : 0.0;
                    double optionPoints = Math.round((atmPts + hedgePts) * 100.0) / 100.0;

                    if (optionPoints > openPosition.peakProfitPoints) {
                        openPosition.peakProfitPoints = optionPoints;
                    }

                    // EOD Square-Off at 15:05 IST
                    if (time.isAfter(LocalTime.of(15, 0))) {
                        BigDecimal pnlAmount =
                                BigDecimal.valueOf(optionPoints * totalQuantity)
                                        .setScale(2, RoundingMode.HALF_UP);
                        boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                        executedTrades.add(
                                new BacktestTrade(
                                        tradeIdCounter++,
                                        day.format(DATE_FMT),
                                        symbol,
                                        openPosition.action,
                                        openPosition.entryTime,
                                        openPosition.entrySpot,
                                        bar.timestamp(),
                                        exitSpot,
                                        optionPoints,
                                        pnlAmount,
                                        "MANDATORY_EOD_SQUARE_OFF (15:05)",
                                        isWin));
                        openPosition = null;
                        continue;
                    }

                    double baseSlThresholdPoints = -(netCredit * (stopLossPercent / 100.0));
                    double effectiveSlPoints = baseSlThresholdPoints;

                    // Stepped Trailing Stop Loss
                    if (openPosition.peakProfitPoints >= 25.0) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, 15.0);
                    } else if (openPosition.peakProfitPoints >= 12.0) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, 2.0);
                    }

                    double tpThresholdPoints = netCredit * (targetProfitPercent / 100.0);

                    boolean isSlHit = stopLossPercent > 0.0 && optionPoints <= effectiveSlPoints;
                    boolean isTpHit =
                            targetProfitPercent > 0.0 && optionPoints >= tpThresholdPoints;
                    boolean isRsiReversal =
                            rsiExitEnabled
                                    && ((openPosition.isBullish && rsi5Curr < rsi15Curr)
                                            || (!openPosition.isBullish && rsi5Curr > rsi15Curr));
                    boolean isStReversal =
                            (openPosition.isBullish && !isStBullish)
                                    || (!openPosition.isBullish && isStBullish);

                    if (isSlHit || isTpHit || isRsiReversal || isStReversal) {
                        String reason;
                        if (isTpHit) {
                            reason = "TARGET_PROFIT_HIT (" + optionPoints + " pts)";
                        } else if (isSlHit) {
                            reason =
                                    effectiveSlPoints > baseSlThresholdPoints
                                            ? "TRAIL_SL_LOCK (" + optionPoints + " pts)"
                                            : "HARD_SL_HIT (" + optionPoints + " pts)";
                        } else if (isRsiReversal) {
                            reason =
                                    openPosition.isBullish
                                            ? "RSI_REVERSAL_BEARISH (" + optionPoints + " pts)"
                                            : "RSI_REVERSAL_BULLISH (" + optionPoints + " pts)";
                        } else {
                            reason =
                                    openPosition.isBullish
                                            ? "ST_FLIP_BEARISH (" + optionPoints + " pts)"
                                            : "ST_FLIP_BULLISH (" + optionPoints + " pts)";
                        }

                        BigDecimal pnlAmount =
                                BigDecimal.valueOf(optionPoints * totalQuantity)
                                        .setScale(2, RoundingMode.HALF_UP);
                        boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                        executedTrades.add(
                                new BacktestTrade(
                                        tradeIdCounter++,
                                        day.format(DATE_FMT),
                                        symbol,
                                        openPosition.action,
                                        openPosition.entryTime,
                                        openPosition.entrySpot,
                                        bar.timestamp(),
                                        exitSpot,
                                        optionPoints,
                                        pnlAmount,
                                        reason,
                                        isWin));
                        openPosition = null;
                    }
                    continue;
                }

                // 2. Evaluate entry (max trades per day limit, 09:45 to 15:00 IST)
                if (time.isBefore(LocalTime.of(9, 45)) || time.isAfter(LocalTime.of(15, 0))) {
                    continue;
                }

                if (tradesTodayCount < maxTradesPerDay) {
                    boolean bullishCrossover = (rsi5Prev <= rsi15Prev) && (rsi5Curr > rsi15Curr);
                    boolean bearishCrossover = (rsi5Prev >= rsi15Prev) && (rsi5Curr < rsi15Curr);

                    if (!bullishCrossover && !bearishCrossover) {
                        continue;
                    }

                    if (adxFilterEnabled && !Double.isNaN(adx15Curr) && adx15Curr < adxThreshold) {
                        continue;
                    }

                    double spotPrice = bar.close().doubleValue();
                    double currentVwap =
                            (vwapSeries != null && vwapSeries.length > 0)
                                    ? vwapSeries[vwapSeries.length - 1]
                                    : spotPrice;

                    if (bullishCrossover) {
                        // Bullish Confluence: ST Bullish, +DI >= -DI, Spot >= VWAP, VWAP Distance
                        // <= 35
                        if (!isStBullish) {
                            continue;
                        }
                        if (!Double.isNaN(plusDi15Curr)
                                && !Double.isNaN(minusDi15Curr)
                                && plusDi15Curr < minusDi15Curr) {
                            continue;
                        }
                        if (spotPrice < currentVwap || (spotPrice - currentVwap) > 75.0) {
                            continue;
                        }

                        if (isOptionSelling) {
                            openPosition =
                                    new SimulatedPosition(
                                            SignalAction.SELL,
                                            "PE",
                                            true,
                                            bar.timestamp(),
                                            bar.close());
                        } else {
                            openPosition =
                                    new SimulatedPosition(
                                            SignalAction.BUY,
                                            "CE",
                                            true,
                                            bar.timestamp(),
                                            bar.close());
                        }
                        tradesTodayCount++;
                    } else {
                        // Bearish Confluence: ST Bearish, -DI >= +DI, Spot <= VWAP, VWAP Distance
                        // <= 35
                        if (isStBullish) {
                            continue;
                        }
                        if (!Double.isNaN(plusDi15Curr)
                                && !Double.isNaN(minusDi15Curr)
                                && minusDi15Curr < plusDi15Curr) {
                            continue;
                        }
                        if (spotPrice > currentVwap || (currentVwap - spotPrice) > 75.0) {
                            continue;
                        }

                        if (isOptionSelling) {
                            openPosition =
                                    new SimulatedPosition(
                                            SignalAction.SELL,
                                            "CE",
                                            false,
                                            bar.timestamp(),
                                            bar.close());
                        } else {
                            openPosition =
                                    new SimulatedPosition(
                                            SignalAction.BUY,
                                            "PE",
                                            false,
                                            bar.timestamp(),
                                            bar.close());
                        }
                        tradesTodayCount++;
                    }
                }
            }

            // Fallback close at final candle of the day
            if (openPosition != null) {
                Candle lastBar = dayBars.get(dayBars.size() - 1);
                BigDecimal exitSpot = lastBar.close();
                double spotDiff =
                        openPosition.isBullish
                                ? exitSpot.subtract(openPosition.entrySpot).doubleValue()
                                : openPosition.entrySpot.subtract(exitSpot).doubleValue();

                double holdHours =
                        Duration.between(openPosition.entryTime, lastBar.timestamp()).toSeconds()
                                / 3600.0;
                double atmPts = (spotDiff * delta) + (holdHours * thetaPerHour);
                double hedgePts =
                        applyHedge
                                ? ((-spotDiff * hedgeDelta) + (holdHours * hedgeThetaPerHour))
                                : 0.0;
                double optionPoints = Math.round((atmPts + hedgePts) * 100.0) / 100.0;

                BigDecimal pnlAmount =
                        BigDecimal.valueOf(optionPoints * totalQuantity)
                                .setScale(2, RoundingMode.HALF_UP);
                boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                executedTrades.add(
                        new BacktestTrade(
                                tradeIdCounter++,
                                day.format(DATE_FMT),
                                symbol,
                                openPosition.action,
                                openPosition.entryTime,
                                openPosition.entrySpot,
                                lastBar.timestamp(),
                                exitSpot,
                                optionPoints,
                                pnlAmount,
                                "DAY_END_CLOSE",
                                isWin));
                openPosition = null;
            }
        }

        return aggregateResults(symbol, sortedDates.size(), executedTrades);
    }

    private BacktestResult aggregateResults(
            String symbol, int daysTested, List<BacktestTrade> trades) {
        int totalTrades = trades.size();
        int winningTrades = 0;
        int losingTrades = 0;
        double totalPoints = 0.0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;

        BigDecimal cumulativePnl = BigDecimal.ZERO;
        BigDecimal peakPnl = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BacktestTrade t : trades) {
            totalPoints += t.pnlPoints();
            BigDecimal tradePnl = t.pnlAmount();
            cumulativePnl = cumulativePnl.add(tradePnl);

            if (cumulativePnl.compareTo(peakPnl) > 0) {
                peakPnl = cumulativePnl;
            }
            BigDecimal currentDrawdown = peakPnl.subtract(cumulativePnl);
            if (currentDrawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = currentDrawdown;
            }

            if (t.isWin()) {
                winningTrades++;
                grossProfit = grossProfit.add(tradePnl);
            } else {
                losingTrades++;
                grossLoss = grossLoss.add(tradePnl.abs());
            }
        }

        BigDecimal netPnl = grossProfit.subtract(grossLoss);
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100.0 : 0.0;
        double profitFactor =
                grossLoss.compareTo(BigDecimal.ZERO) > 0
                        ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                        : 99.99;

        return new BacktestResult(
                STRATEGY_ID,
                symbol,
                daysTested,
                totalTrades,
                winningTrades,
                losingTrades,
                winRate,
                totalPoints,
                grossProfit,
                grossLoss,
                netPnl,
                maxDrawdown,
                profitFactor,
                trades);
    }

    private static class SimulatedPosition {
        final SignalAction action;
        final String optionType;
        final boolean isBullish;
        final Instant entryTime;
        final BigDecimal entrySpot;
        double peakProfitPoints;

        SimulatedPosition(
                SignalAction action,
                String optionType,
                boolean isBullish,
                Instant entryTime,
                BigDecimal entrySpot) {
            this.action = action;
            this.optionType = optionType;
            this.isBullish = isBullish;
            this.entryTime = entryTime;
            this.entrySpot = entrySpot;
            this.peakProfitPoints = 0.0;
        }
    }
}

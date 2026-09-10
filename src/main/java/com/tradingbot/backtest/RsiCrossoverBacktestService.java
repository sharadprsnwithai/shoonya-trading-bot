package com.tradingbot.backtest;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.util.CandleResamplingUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Historical Backtest Service for NIFTY 50 5m vs 15m RSI(14) Crossover Option Buying Strategy.
 *
 * <p>Simulates:
 * 1. 09:45:10 to 15:00:10 IST evaluation cycle on 5m NIFTY candles.
 * 2. 5m RSI(14) crosses above 15m RSI(14) -> Buy ATM CE.
 * 3. 5m RSI(14) crosses below 15m RSI(14) -> Buy ATM PE.
 * 4. ADX trend strength filter (default >= 20.0 on 15m).
 * 5. Strict 1 trade per day limit.
 * 6. Stop-Loss exit (default 20.0% / 20 pts) & Target Profit exit (default 50.0% / 75 pts).
 * 7. Reversal exit (holding CE exits when 5m < 15m RSI; holding PE exits when 5m > 15m RSI).
 * 8. Mandatory 15:05:10 IST EOD square-off.
 * 9. ATM Option Delta ~ 0.50 PnL simulation with lot sizing.
 */
@Service
public class RsiCrossoverBacktestService {

    private static final Logger log = LoggerFactory.getLogger(RsiCrossoverBacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final String STRATEGY_ID = "NIFTY_RSI_5M_15M_CROSSOVER";
    public static final int NIFTY_LOT_SIZE = 65;
    public static final int DEFAULT_LOTS = 1;
    public static final int DEFAULT_RSI_PERIOD = 14;
    public static final double DEFAULT_ADX_THRESHOLD = 20.0;
    public static final double DEFAULT_STOP_LOSS_PERCENT = 20.0;
    public static final double DEFAULT_TARGET_PROFIT_PERCENT = 50.0;

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;

    @Autowired
    public RsiCrossoverBacktestService(
            ShoonyaMarketDataService marketDataService, TechnicalAnalysisService taService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
    }

    /** Runs backtest for NIFTY 50 over the specified days back using default parameters. */
    public BacktestResult runBacktest(int daysBack) {
        return runBacktest(
                daysBack,
                DEFAULT_LOTS,
                DEFAULT_RSI_PERIOD,
                true,
                DEFAULT_ADX_THRESHOLD,
                DEFAULT_STOP_LOSS_PERCENT,
                DEFAULT_TARGET_PROFIT_PERCENT);
    }

    /** Runs backtest for NIFTY 50 over the specified days back with full custom parameter tuning. */
    public BacktestResult runBacktest(
            int daysBack,
            int lots,
            int rsiPeriod,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        int boundedDays = Math.max(1, Math.min(daysBack, 95));
        log.info("[RSI-BACKTEST] Fetching {} days of 5m candles for NIFTY 50 (NSE:10576)", boundedDays);
        List<Candle> candles = marketDataService.fetchHistoricalCandles("NSE", "10576", "NIFTY 50", "5", boundedDays);
        return evaluateCandles(
                "NIFTY 50",
                candles,
                lots,
                rsiPeriod,
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
                lots,
                rsiPeriod,
                true,
                DEFAULT_ADX_THRESHOLD,
                DEFAULT_STOP_LOSS_PERCENT,
                DEFAULT_TARGET_PROFIT_PERCENT);
    }

    /**
     * Evaluates a chronological list of 5m candles through the RSI Crossover Option Buying strategy.
     *
     * @param symbol display symbol
     * @param candles chronological 5m candles
     * @param lots number of lots (1 lot = 65 qty)
     * @param rsiPeriod RSI lookback period (default 14)
     * @param adxFilterEnabled whether to filter entries by 15m ADX
     * @param adxThreshold minimum 15m ADX required for entry
     * @param stopLossPercent stop-loss percentage on option premium (e.g. 20.0%)
     * @param targetProfitPercent target profit percentage on option premium (e.g. 50.0%)
     * @return BacktestResult summary with trade log and performance metrics
     */
    public BacktestResult evaluateCandles(
            String symbol,
            List<Candle> candles,
            int lots,
            int rsiPeriod,
            boolean adxFilterEnabled,
            double adxThreshold,
            double stopLossPercent,
            double targetProfitPercent) {
        if (candles == null || candles.isEmpty()) {
            return new BacktestResult(
                    STRATEGY_ID, symbol, 0, 0, 0, 0, 0.0, 0.0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, List.of());
        }

        int totalQuantity = Math.max(1, lots) * NIFTY_LOT_SIZE;
        double delta = 0.50; // Standard ATM option delta
        double assumedEntryPremium = 150.0;

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

            boolean tradeExecutedToday = false;
            SimulatedPosition openPosition = null;

            List<Candle> currentDayBars = new ArrayList<>();

            for (Candle bar : dayBars) {
                currentDayBars.add(bar);
                LocalTime time = bar.timestamp().atZone(IST).toLocalTime();

                // EOD Square-Off at 15:05 IST
                if (time.isAfter(LocalTime.of(15, 0)) && openPosition != null) {
                    BigDecimal exitSpot = bar.close();
                    double spotDiff = "CE".equalsIgnoreCase(openPosition.optionType)
                            ? exitSpot.subtract(openPosition.entrySpot).doubleValue()
                            : openPosition.entrySpot.subtract(exitSpot).doubleValue();
                    double optionPoints = Math.round(spotDiff * delta * 100.0) / 100.0;
                    BigDecimal pnlAmount = BigDecimal.valueOf(optionPoints * totalQuantity).setScale(2, RoundingMode.HALF_UP);
                    boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                    executedTrades.add(
                            new BacktestTrade(
                                    tradeIdCounter++,
                                    day.format(DATE_FMT),
                                    symbol,
                                    SignalAction.BUY,
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

                if (time.isBefore(LocalTime.of(9, 45))) {
                    continue;
                }

                List<Candle> allBars = new ArrayList<>(warmHistory);
                allBars.addAll(currentDayBars);

                List<Candle> fifteenMinBars = CandleResamplingUtil.resample5MinTo15Min(allBars);
                if (fifteenMinBars.size() < (rsiPeriod + 5) || allBars.size() < (rsiPeriod + 10)) {
                    continue;
                }

                double[] close5m = allBars.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] rsi5mSeries = taService.calculateRsiSeries(close5m, rsiPeriod);

                double[] close15m = fifteenMinBars.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] high15m = fifteenMinBars.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
                double[] low15m = fifteenMinBars.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
                double[] rsi15mSeries = taService.calculateRsiSeries(close15m, rsiPeriod);
                double[] adx15mSeries = taService.calculateAdxSeries(high15m, low15m, close15m, rsiPeriod);

                int len5 = rsi5mSeries.length;
                int len15 = rsi15mSeries.length;
                if (len5 < 2 || len15 < 2) continue;

                double rsi5Curr = rsi5mSeries[len5 - 1];
                double rsi5Prev = rsi5mSeries[len5 - 2];
                double rsi15Curr = rsi15mSeries[len15 - 1];
                double rsi15Prev = rsi15mSeries[len15 - 2];
                double adx15Curr = (adx15mSeries.length > 0) ? adx15mSeries[adx15mSeries.length - 1] : Double.NaN;

                if (Double.isNaN(rsi5Curr) || Double.isNaN(rsi5Prev) || Double.isNaN(rsi15Curr) || Double.isNaN(rsi15Prev)) {
                    continue;
                }

                // 1. Manage active open position (Stop Loss, Target Profit, or Reversal Exit)
                if (openPosition != null) {
                    BigDecimal exitSpot = bar.close();
                    double spotDiff = "CE".equalsIgnoreCase(openPosition.optionType)
                            ? exitSpot.subtract(openPosition.entrySpot).doubleValue()
                            : openPosition.entrySpot.subtract(exitSpot).doubleValue();
                    double optionPoints = Math.round(spotDiff * delta * 100.0) / 100.0;

                    double slThresholdPoints = -(assumedEntryPremium * (stopLossPercent / 100.0));
                    double tpThresholdPoints = assumedEntryPremium * (targetProfitPercent / 100.0);

                    boolean isSlHit = stopLossPercent > 0.0 && optionPoints <= slThresholdPoints;
                    boolean isTpHit = targetProfitPercent > 0.0 && optionPoints >= tpThresholdPoints;
                    boolean isReversal = ("CE".equalsIgnoreCase(openPosition.optionType) && rsi5Curr < rsi15Curr)
                            || ("PE".equalsIgnoreCase(openPosition.optionType) && rsi5Curr > rsi15Curr);

                    if (isSlHit || isTpHit || isReversal) {
                        String reason;
                        if (isSlHit) {
                            reason = "HARD_SL_HIT (" + optionPoints + " pts)";
                        } else if (isTpHit) {
                            reason = "TARGET_PROFIT_HIT (" + optionPoints + " pts)";
                        } else {
                            reason = "CE".equalsIgnoreCase(openPosition.optionType) ? "RSI_REVERSAL_BEARISH" : "RSI_REVERSAL_BULLISH";
                        }

                        BigDecimal pnlAmount = BigDecimal.valueOf(optionPoints * totalQuantity).setScale(2, RoundingMode.HALF_UP);
                        boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                        executedTrades.add(
                                new BacktestTrade(
                                        tradeIdCounter++,
                                        day.format(DATE_FMT),
                                        symbol,
                                        SignalAction.BUY,
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

                // 2. Check Entry Signal (Strict 1 trade per day limit)
                if (!tradeExecutedToday && !time.isAfter(LocalTime.of(15, 0))) {
                    boolean bullish = (rsi5Prev <= rsi15Prev) && (rsi5Curr > rsi15Curr);
                    boolean bearish = (rsi5Prev >= rsi15Prev) && (rsi5Curr < rsi15Curr);

                    if (!bullish && !bearish) {
                        continue;
                    }

                    // ADX trend momentum filter
                    if (adxFilterEnabled && !Double.isNaN(adx15Curr) && adx15Curr < adxThreshold) {
                        continue;
                    }

                    if (bullish) {
                        openPosition = new SimulatedPosition("CE", bar.close(), bar.timestamp());
                        tradeExecutedToday = true;
                    } else {
                        openPosition = new SimulatedPosition("PE", bar.close(), bar.timestamp());
                        tradeExecutedToday = true;
                    }
                }
            }

            // Close leftover position at day end if any
            if (openPosition != null && !dayBars.isEmpty()) {
                Candle lastBar = dayBars.get(dayBars.size() - 1);
                BigDecimal exitSpot = lastBar.close();
                double spotDiff = "CE".equalsIgnoreCase(openPosition.optionType)
                        ? exitSpot.subtract(openPosition.entrySpot).doubleValue()
                        : openPosition.entrySpot.subtract(exitSpot).doubleValue();
                double optionPoints = Math.round(spotDiff * delta * 100.0) / 100.0;
                BigDecimal pnlAmount = BigDecimal.valueOf(optionPoints * totalQuantity).setScale(2, RoundingMode.HALF_UP);
                boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                executedTrades.add(
                        new BacktestTrade(
                                tradeIdCounter++,
                                day.format(DATE_FMT),
                                symbol,
                                SignalAction.BUY,
                                openPosition.entryTime,
                                openPosition.entrySpot,
                                lastBar.timestamp(),
                                exitSpot,
                                optionPoints,
                                pnlAmount,
                                "EOD_CLOSE",
                                isWin));
                openPosition = null;
            }
        }

        // Aggregate Performance Metrics
        int totalTrades = executedTrades.size();
        int wins = 0;
        int losses = 0;
        double totalPoints = 0.0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        BigDecimal netPnl = BigDecimal.ZERO;
        BigDecimal peakPnl = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal runningPnl = BigDecimal.ZERO;

        for (BacktestTrade t : executedTrades) {
            totalPoints += t.pnlPoints();
            netPnl = netPnl.add(t.pnlAmount());
            runningPnl = runningPnl.add(t.pnlAmount());

            if (t.isWin()) {
                wins++;
                grossProfit = grossProfit.add(t.pnlAmount());
            } else {
                losses++;
                grossLoss = grossLoss.add(t.pnlAmount().abs());
            }

            if (runningPnl.compareTo(peakPnl) > 0) {
                peakPnl = runningPnl;
            }
            BigDecimal dd = peakPnl.subtract(runningPnl);
            if (dd.compareTo(maxDrawdown) > 0) {
                maxDrawdown = dd;
            }
        }

        double winRate = totalTrades > 0 ? (wins * 100.0) / totalTrades : 0.0;
        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.99 : 0.0);

        return new BacktestResult(
                STRATEGY_ID,
                symbol,
                sortedDates.size(),
                totalTrades,
                wins,
                losses,
                Math.round(winRate * 10.0) / 10.0,
                Math.round(totalPoints * 100.0) / 100.0,
                grossProfit.setScale(2, RoundingMode.HALF_UP),
                grossLoss.setScale(2, RoundingMode.HALF_UP),
                netPnl.setScale(2, RoundingMode.HALF_UP),
                maxDrawdown.setScale(2, RoundingMode.HALF_UP),
                profitFactor,
                executedTrades);
    }

    private record SimulatedPosition(String optionType, BigDecimal entrySpot, Instant entryTime) {}
}

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
 * 4. Strict 1 trade per day limit.
 * 5. Reversal exit (holding CE exits when 5m < 15m RSI; holding PE exits when 5m > 15m RSI).
 * 6. Mandatory 15:05:10 IST EOD square-off.
 * 7. ATM Option Delta ~ 0.50 PnL simulation with lot sizing.
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

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;

    @Autowired
    public RsiCrossoverBacktestService(
            ShoonyaMarketDataService marketDataService, TechnicalAnalysisService taService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
    }

    /** Runs backtest for NIFTY 50 over the specified days back using default 1 lot. */
    public BacktestResult runBacktest(int daysBack) {
        return runBacktest(daysBack, DEFAULT_LOTS, DEFAULT_RSI_PERIOD);
    }

    /** Runs backtest for NIFTY 50 over the specified days back using custom lots and RSI period. */
    public BacktestResult runBacktest(int daysBack, int lots, int rsiPeriod) {
        int boundedDays = Math.max(1, Math.min(daysBack, 95));
        log.info("[RSI-BACKTEST] Fetching {} days of 5m candles for NIFTY 50 (NSE:10576)", boundedDays);
        List<Candle> candles = marketDataService.fetchHistoricalCandles("NSE", "10576", "NIFTY 50", "5", boundedDays);
        return evaluateCandles("NIFTY 50", candles, lots, rsiPeriod);
    }

    /**
     * Evaluates a chronological list of 5m candles through the RSI Crossover Option Buying strategy.
     *
     * @param symbol display symbol
     * @param candles chronological 5m candles
     * @param lots number of lots (1 lot = 65 qty)
     * @param rsiPeriod RSI lookback period (default 14)
     * @return BacktestResult summary with trade log and performance metrics
     */
    public BacktestResult evaluateCandles(
            String symbol, List<Candle> candles, int lots, int rsiPeriod) {
        if (candles == null || candles.isEmpty()) {
            return new BacktestResult(
                    STRATEGY_ID, symbol, 0, 0, 0, 0, 0.0, 0.0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, List.of());
        }

        int totalQuantity = Math.max(1, lots) * NIFTY_LOT_SIZE;
        double delta = 0.50; // Standard ATM option delta

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
                double[] rsi15mSeries = taService.calculateRsiSeries(close15m, rsiPeriod);

                int len5 = rsi5mSeries.length;
                int len15 = rsi15mSeries.length;
                if (len5 < 2 || len15 < 2) continue;

                double rsi5Curr = rsi5mSeries[len5 - 1];
                double rsi5Prev = rsi5mSeries[len5 - 2];
                double rsi15Curr = rsi15mSeries[len15 - 1];
                double rsi15Prev = rsi15mSeries[len15 - 2];

                if (Double.isNaN(rsi5Curr) || Double.isNaN(rsi5Prev) || Double.isNaN(rsi15Curr) || Double.isNaN(rsi15Prev)) {
                    continue;
                }

                // 1. Manage active open position (Exit on Crossover Reversal)
                if (openPosition != null) {
                    boolean shouldExit = false;
                    String reason = "";

                    if ("CE".equalsIgnoreCase(openPosition.optionType) && rsi5Curr < rsi15Curr) {
                        shouldExit = true;
                        reason = "RSI_REVERSAL_BEARISH";
                    } else if ("PE".equalsIgnoreCase(openPosition.optionType) && rsi5Curr > rsi15Curr) {
                        shouldExit = true;
                        reason = "RSI_REVERSAL_BULLISH";
                    }

                    if (shouldExit) {
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
                                        reason,
                                        isWin));
                        openPosition = null;
                    }
                    continue;
                }

                // 2. Check Entry Signal (Strict 1 trade per day)
                if (!tradeExecutedToday && !time.isAfter(LocalTime.of(15, 0))) {
                    boolean bullish = (rsi5Prev <= rsi15Prev) && (rsi5Curr > rsi15Curr);
                    boolean bearish = (rsi5Prev >= rsi15Prev) && (rsi5Curr < rsi15Curr);

                    if (bullish) {
                        openPosition = new SimulatedPosition("CE", bar.close(), bar.timestamp());
                        tradeExecutedToday = true;
                    } else if (bearish) {
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

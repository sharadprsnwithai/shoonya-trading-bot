package com.tradingbot.backtest;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.impl.PivotSuperTrendOptionSellingStrategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Historical Backtest Service for Intraday Directional Option Selling (Pivot R1/S1 + SuperTrend
 * 7,3).
 */
@Service
public class PivotSuperTrendBacktestService {

    private static final Logger log = LoggerFactory.getLogger(PivotSuperTrendBacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final int NIFTY_LOT_SIZE = 65;
    public static final int DEFAULT_LOTS = 5;

    private final ShoonyaMarketDataService marketDataService;
    private final PivotSuperTrendOptionSellingStrategy strategy;

    @Autowired
    public PivotSuperTrendBacktestService(
            ShoonyaMarketDataService marketDataService,
            PivotSuperTrendOptionSellingStrategy strategy) {
        this.marketDataService = marketDataService;
        this.strategy = strategy;
    }

    /**
     * Executes backtest for NIFTY 50 over the specified days back using default 5 lots and price
     * action.
     */
    public BacktestResult runBacktest(int daysBack) {
        return runBacktest(daysBack, DEFAULT_LOTS, false);
    }

    /** Executes backtest for NIFTY 50 over the specified days back using specified lots. */
    public BacktestResult runBacktest(int daysBack, int lots) {
        return runBacktest(daysBack, lots, false);
    }

    /** Executes backtest for NIFTY 50 with option to simulate 3-strike OTM OI dynamics. */
    public BacktestResult runBacktest(int daysBack, int lots, boolean simulateOi) {
        int boundedDays = Math.max(1, Math.min(daysBack, 95));
        List<Candle> candles;

        if (boundedDays > 5) {
            log.info(
                    "[BACKTEST-PIVOT-ST] Fetching continuous NIFTY data (NSE:10576) for {} days.",
                    boundedDays);
            List<Candle> rawEtf =
                    marketDataService.fetchHistoricalCandles(
                            "NSE", "10576", "NIFTY50", "5", boundedDays);
            candles = new ArrayList<>();
            for (Candle c : rawEtf) {
                double scale = (c.close().doubleValue() < 1000.0) ? 100.0 : 1.0;
                candles.add(
                        new Candle(
                                "NIFTY50",
                                "5",
                                c.timestamp(),
                                c.open().multiply(BigDecimal.valueOf(scale)),
                                c.high().multiply(BigDecimal.valueOf(scale)),
                                c.low().multiply(BigDecimal.valueOf(scale)),
                                c.close().multiply(BigDecimal.valueOf(scale)),
                                c.volume()));
            }
        } else {
            candles =
                    marketDataService.fetchHistoricalCandles(
                            "NFO", "68407", "NIFTY50", "5", boundedDays);
        }

        return evaluateCandles(candles, lots, simulateOi);
    }

    /**
     * Evaluates a provided series of 5m candles through the Pivot SuperTrend Option Selling
     * lifecycle (default 5 lots).
     */
    public BacktestResult evaluateCandles(List<Candle> candles) {
        return evaluateCandles(candles, DEFAULT_LOTS, false);
    }

    /**
     * Evaluates a provided series of 5m candles through the Pivot SuperTrend Option Selling
     * lifecycle with specified lots.
     */
    public BacktestResult evaluateCandles(List<Candle> candles, int lots) {
        return evaluateCandles(candles, lots, false);
    }

    /** Evaluates a provided series of 5m candles with optional 3-OTM OI simulation. */
    public BacktestResult evaluateCandles(List<Candle> candles, int lots, boolean simulateOi) {
        int totalQuantity = Math.max(1, lots) * NIFTY_LOT_SIZE;
        if (candles == null || candles.isEmpty()) {
            return new BacktestResult(
                    PivotSuperTrendOptionSellingStrategy.STRATEGY_ID,
                    "NIFTY50",
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

        // Group candles by trading day
        Map<LocalDate, List<Candle>> dailyCandles = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate date = c.timestamp().atZone(IST).toLocalDate();
            dailyCandles.computeIfAbsent(date, d -> new ArrayList<>()).add(c);
        }

        boolean prevTelegramState = strategy.isTelegramAlertsEnabled();
        boolean prevOiFilter = strategy.isOiFilterEnabled();
        try {
            strategy.setTelegramAlertsEnabled(false);
            if (!simulateOi) {
                strategy.setOiFilterEnabled(false);
            }

            List<BacktestTrade> executedTrades = new ArrayList<>();
            int tradeIdCounter = 1;
            List<Candle> fullHistorySoFar = new ArrayList<>();

            for (Map.Entry<LocalDate, List<Candle>> entry : dailyCandles.entrySet()) {
                LocalDate day = entry.getKey();
                List<Candle> dayBars = entry.getValue();
                dayBars.sort(Comparator.comparing(Candle::timestamp));

                // Reset strategy state for the new day
                strategy.onResetDaily();
                if (!simulateOi) {
                    strategy.setOiFilterEnabled(false);
                }

                ActiveTradeContext activeTrade = null;
                double dayOpen = dayBars.get(0).open().doubleValue();
                double cumPv = 0.0;
                double cumVol = 0.0;
                long baseCallOi = 12_000_000L;
                long basePutOi = 12_000_000L;

                for (int i = 0; i < dayBars.size(); i++) {
                    Candle bar = dayBars.get(i);
                    if (simulateOi) {
                        double close = bar.close().doubleValue();
                        double high = bar.high().doubleValue();
                        double low = bar.low().doubleValue();
                        double vol = Math.max(100.0, (double) bar.volume());
                        double typical = (high + low + close) / 3.0;
                        cumPv += typical * vol;
                        cumVol += vol;
                        double vwap = cumPv / cumVol;

                        double vwapDiff = close - vwap;
                        double openDiff = close - dayOpen;
                        long callOiAdd =
                                (long) Math.max(0, -openDiff * 75000.0 + -vwapDiff * 60000.0);
                        long putOiAdd = (long) Math.max(0, openDiff * 75000.0 + vwapDiff * 60000.0);
                        strategy.setManualOtmOi(baseCallOi + callOiAdd, basePutOi + putOiAdd);
                    }
                    List<Candle> currentHistory = new ArrayList<>(fullHistorySoFar);
                    currentHistory.addAll(dayBars.subList(0, i));

                    TradeSignal signal = strategy.onCandle(bar, currentHistory);

                    if (signal != null && signal.isActionable()) {
                        if (signal.action() == SignalAction.SELL && activeTrade == null) {
                            // Open new Option Selling Trade (Short PE or Short CE)
                            boolean isShortPe = signal.symbol().contains("PE");
                            activeTrade =
                                    new ActiveTradeContext(
                                            tradeIdCounter++,
                                            day.toString(),
                                            signal.symbol(),
                                            isShortPe
                                                    ? SignalAction.BUY
                                                    : SignalAction.SELL, // Directional bias
                                            bar.timestamp(),
                                            bar.close(),
                                            isShortPe);
                            log.debug(
                                    "[BACKTEST-PIVOT-ST] Day {} | Trade #{} OPEN SHORT {}: at price {}",
                                    day,
                                    activeTrade.tradeId,
                                    isShortPe ? "PE" : "CE",
                                    activeTrade.entryPrice);

                        } else if ((signal.action() == SignalAction.EXIT_LONG
                                        || signal.action() == SignalAction.EXIT_SHORT
                                        || signal.action() == SignalAction.SQUARE_OFF)
                                && activeTrade != null) {
                            // Close active option short position
                            BigDecimal exitPrice = bar.close();
                            double underlyingDiff =
                                    activeTrade.isShortPe
                                            ? exitPrice
                                                    .subtract(activeTrade.entryPrice)
                                                    .doubleValue() // Bullish: gain if price rises
                                            : activeTrade
                                                    .entryPrice
                                                    .subtract(exitPrice)
                                                    .doubleValue(); // Bearish: gain if price drops

                            // Option Seller captures ~0.50 delta move + approx theta gain
                            double optionPoints = underlyingDiff * 0.50;
                            BigDecimal pnlAmount =
                                    BigDecimal.valueOf(optionPoints * totalQuantity)
                                            .setScale(2, RoundingMode.HALF_UP);
                            boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                            BacktestTrade trade =
                                    new BacktestTrade(
                                            activeTrade.tradeId,
                                            activeTrade.date,
                                            activeTrade.symbol,
                                            SignalAction.SELL,
                                            activeTrade.entryTime,
                                            activeTrade.entryPrice,
                                            bar.timestamp(),
                                            exitPrice,
                                            Math.round(optionPoints * 100.0) / 100.0,
                                            pnlAmount,
                                            signal.reason(),
                                            isWin);

                            executedTrades.add(trade);
                            log.debug(
                                    "[BACKTEST-PIVOT-ST] Day {} | Trade #{} CLOSED: PnL Points: {} | PnL Amount: Rs.{} | Reason: {}",
                                    day,
                                    trade.tradeId(),
                                    trade.pnlPoints(),
                                    trade.pnlAmount(),
                                    trade.exitReason());
                            activeTrade = null;
                        }
                    }
                }

                // EOD square-off safety: ensure no active position ever carries overnight
                if (activeTrade != null && !dayBars.isEmpty()) {
                    Candle lastBar = dayBars.get(dayBars.size() - 1);
                    BigDecimal exitPrice = lastBar.close();
                    double underlyingDiff =
                            activeTrade.isShortPe
                                    ? exitPrice.subtract(activeTrade.entryPrice).doubleValue()
                                    : activeTrade.entryPrice.subtract(exitPrice).doubleValue();
                    double optionPoints = underlyingDiff * 0.50;
                    BigDecimal pnlAmount =
                            BigDecimal.valueOf(optionPoints * totalQuantity)
                                    .setScale(2, RoundingMode.HALF_UP);
                    boolean isWin = pnlAmount.compareTo(BigDecimal.ZERO) > 0;

                    BacktestTrade trade =
                            new BacktestTrade(
                                    activeTrade.tradeId,
                                    activeTrade.date,
                                    activeTrade.symbol,
                                    SignalAction.SELL,
                                    activeTrade.entryTime,
                                    activeTrade.entryPrice,
                                    lastBar.timestamp(),
                                    exitPrice,
                                    Math.round(optionPoints * 100.0) / 100.0,
                                    pnlAmount,
                                    "Mandatory 15:14 IST Auto Square-Off",
                                    isWin);
                    executedTrades.add(trade);
                    activeTrade = null;
                }

                // Append completed day's bars to historical series for pivot calculation next day
                fullHistorySoFar.addAll(dayBars);
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

            double winRate = totalTrades > 0 ? (double) wins / totalTrades * 100.0 : 0.0;
            double profitFactor =
                    grossLoss.compareTo(BigDecimal.ZERO) > 0
                            ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                            : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.9 : 1.0);

            return new BacktestResult(
                    PivotSuperTrendOptionSellingStrategy.STRATEGY_ID,
                    "NIFTY50",
                    dailyCandles.size(),
                    totalTrades,
                    wins,
                    losses,
                    Math.round(winRate * 100.0) / 100.0,
                    Math.round(totalPoints * 100.0) / 100.0,
                    grossProfit,
                    grossLoss,
                    netPnl,
                    maxDrawdown,
                    profitFactor,
                    executedTrades);
        } finally {
            strategy.setTelegramAlertsEnabled(prevTelegramState);
            strategy.setOiFilterEnabled(prevOiFilter);
            strategy.clearManualOtmOi();
        }
    }

    private static class ActiveTradeContext {
        int tradeId;
        String date;
        String symbol;
        SignalAction action;
        java.time.Instant entryTime;
        BigDecimal entryPrice;
        boolean isShortPe;

        ActiveTradeContext(
                int tradeId,
                String date,
                String symbol,
                SignalAction action,
                java.time.Instant entryTime,
                BigDecimal entryPrice,
                boolean isShortPe) {
            this.tradeId = tradeId;
            this.date = date;
            this.symbol = symbol;
            this.action = action;
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.isShortPe = isShortPe;
        }
    }
}

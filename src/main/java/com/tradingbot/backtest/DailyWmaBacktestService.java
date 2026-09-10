package com.tradingbot.backtest;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.util.BlackScholesUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Historical Backtesting Engine for the 19-period Daily WMA Positional Option Selling Strategy.
 */
@Service
public class DailyWmaBacktestService {

    private static final Logger log = LoggerFactory.getLogger(DailyWmaBacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final String STRATEGY_ID = "19WMA_POSITIONAL_OPTION_SELLING";
    public static final int NIFTY_LOT_SIZE = 65;

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;

    @Autowired
    public DailyWmaBacktestService(
            ShoonyaMarketDataService marketDataService, TechnicalAnalysisService taService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
    }

    /** Runs backtest for NIFTY 50 over the specified days with default settings. */
    public BacktestResult runBacktest(int daysBack) {
        return runBacktest(daysBack, 19, 0.22, 105.0, 15, 0.02, 1.0, 1);
    }

    /** Runs parameterized backtest over historical daily candles. */
    public BacktestResult runBacktest(
            int daysBack,
            int wmaPeriod,
            double targetDelta,
            double maxEntryPremium,
            int expirySwitchDay,
            double hedgeOtmPercent,
            double stopLossPercent,
            int lots) {

        List<Candle> dailyCandles = marketDataService.fetchDailyCandles("10576", daysBack);
        if (dailyCandles == null || dailyCandles.size() < wmaPeriod + 5) {
            log.warn("[DAILY-WMA-BACKTEST] Insufficient candles (found {}) for {} days test.",
                    dailyCandles == null ? 0 : dailyCandles.size(), daysBack);
            return emptyResult(daysBack);
        }

        double[] closes = dailyCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] wmaSeries = taService.calculateWmaSeries(closes, wmaPeriod);

        List<BacktestTrade> trades = new ArrayList<>();
        int tradeCounter = 1;
        int qty = lots * NIFTY_LOT_SIZE;

        // Positional State
        boolean inTrade = false;
        String currentBias = null; // BULLISH or BEARISH
        String optionType = null;  // PE or CE
        LocalDate entryDate = null;
        LocalDate expiryDate = null;
        double shortStrike = 0.0;
        double shortEntryPrice = 0.0;
        double hedgeStrike = 0.0;
        double hedgeEntryPrice = 0.0;
        double stopLossPrice = 0.0;

        for (int i = wmaPeriod; i < dailyCandles.size(); i++) {
            Candle candle = dailyCandles.get(i);
            LocalDate currentDate = candle.timestamp().atZone(IST).toLocalDate();
            double spot = candle.close().doubleValue();
            double wma = wmaSeries[i];

            if (Double.isNaN(wma)) continue;

            String bias = spot > wma ? "BULLISH" : "BEARISH";

            if (inTrade) {
                long dte = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(currentDate, expiryDate));
                double tteYears = dte / 365.0;

                double shortLtp = BlackScholesUtil.calculateOptionPrice(spot, shortStrike, Math.max(0.001, tteYears), 0.14, 0.07, "CE".equalsIgnoreCase(optionType));
                double hedgeLtp = BlackScholesUtil.calculateOptionPrice(spot, hedgeStrike, Math.max(0.001, tteYears), 0.14, 0.07, "CE".equalsIgnoreCase(optionType));

                boolean isReversal = !bias.equalsIgnoreCase(currentBias);
                boolean isExpiry = currentDate.isEqual(expiryDate) || currentDate.isAfter(expiryDate);
                boolean isSlHit = shortLtp >= stopLossPrice;

                if (isReversal || isExpiry || isSlHit) {
                    String exitReason = isSlHit ? "STOP_LOSS_HIT" : (isExpiry ? "EXPIRY_SQUARE_OFF" : "19WMA_TREND_REVERSAL");

                    double shortPnl = (shortEntryPrice - shortLtp) * qty;
                    double hedgePnl = (hedgeLtp - hedgeEntryPrice) * qty;
                    double totalPnl = shortPnl + hedgePnl;
                    double netPointsCaptured = (shortEntryPrice - shortLtp) + (hedgeLtp - hedgeEntryPrice);

                    trades.add(
                            new BacktestTrade(
                                    tradeCounter++,
                                    entryDate.format(DATE_FMT),
                                    "NIFTY " + (int) shortStrike + " / " + (int) hedgeStrike + " " + optionType,
                                    "BULLISH".equalsIgnoreCase(currentBias) ? SignalAction.BUY : SignalAction.SELL,
                                    candle.timestamp(),
                                    BigDecimal.valueOf(shortEntryPrice).setScale(2, RoundingMode.HALF_UP),
                                    candle.timestamp(),
                                    BigDecimal.valueOf(shortLtp).setScale(2, RoundingMode.HALF_UP),
                                    Math.round(netPointsCaptured * 100.0) / 100.0,
                                    BigDecimal.valueOf(totalPnl).setScale(2, RoundingMode.HALF_UP),
                                    exitReason,
                                    totalPnl > 0));

                    inTrade = false;
                }
            }

            if (!inTrade) {
                currentBias = bias;
                optionType = "BULLISH".equalsIgnoreCase(bias) ? "PE" : "CE";
                entryDate = currentDate;
                expiryDate = resolveExpiry(currentDate, expirySwitchDay);

                long dte = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(currentDate, expiryDate));
                double tteYears = dte / 365.0;

                // Strike Selection
                shortStrike = 0.0;
                shortEntryPrice = 0.0;
                double roundedAtm = Math.round(spot / 50.0) * 50.0;

                if ("PE".equalsIgnoreCase(optionType)) {
                    for (double strike = roundedAtm - 50.0; strike >= roundedAtm - 1500.0; strike -= 50.0) {
                        double delta = Math.abs(BlackScholesUtil.calculateDelta(spot, strike, tteYears, 0.14, 0.07, false));
                        double price = BlackScholesUtil.calculateOptionPrice(spot, strike, tteYears, 0.14, 0.07, false);
                        if (price <= maxEntryPremium && delta >= 0.15 && delta <= 0.30) {
                            shortStrike = strike;
                            shortEntryPrice = price;
                            if (delta <= targetDelta + 0.02) break;
                        }
                    }
                    hedgeStrike = Math.round((shortStrike * (1.0 - hedgeOtmPercent)) / 50.0) * 50.0;
                } else {
                    for (double strike = roundedAtm + 50.0; strike <= roundedAtm + 1500.0; strike += 50.0) {
                        double delta = BlackScholesUtil.calculateDelta(spot, strike, tteYears, 0.14, 0.07, true);
                        double price = BlackScholesUtil.calculateOptionPrice(spot, strike, tteYears, 0.14, 0.07, true);
                        if (price <= maxEntryPremium && delta >= 0.15 && delta <= 0.30) {
                            shortStrike = strike;
                            shortEntryPrice = price;
                            if (delta <= targetDelta + 0.02) break;
                        }
                    }
                    hedgeStrike = Math.round((shortStrike * (1.0 + hedgeOtmPercent)) / 50.0) * 50.0;
                }

                if (shortStrike > 0.0) {
                    hedgeEntryPrice = BlackScholesUtil.calculateOptionPrice(spot, hedgeStrike, tteYears, 0.14, 0.07, "CE".equalsIgnoreCase(optionType));
                    if (hedgeEntryPrice < 0.50) hedgeEntryPrice = 1.00;
                    stopLossPrice = shortEntryPrice * (1.0 + stopLossPercent);
                    inTrade = true;
                }
            }
        }

        return compileResult(trades, daysBack);
    }

    private LocalDate resolveExpiry(LocalDate tradeDate, int expirySwitchDay) {
        LocalDate monthToTarget = (tradeDate.getDayOfMonth() <= expirySwitchDay)
                ? tradeDate
                : tradeDate.plusMonths(1);

        LocalDate lastDayOfMonth = monthToTarget.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate lastThursday = lastDayOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));

        if (lastThursday.isBefore(tradeDate) || lastThursday.isEqual(tradeDate)) {
            LocalDate nextMonth = tradeDate.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
            lastThursday = nextMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));
        }

        return lastThursday;
    }

    private BacktestResult compileResult(List<BacktestTrade> trades, int daysTested) {
        int totalTrades = trades.size();
        int wins = (int) trades.stream().filter(BacktestTrade::isWin).count();
        int losses = totalTrades - wins;
        double winRate = totalTrades > 0 ? (wins * 100.0) / totalTrades : 0.0;

        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        double totalPoints = 0.0;

        BigDecimal runningPnl = BigDecimal.ZERO;
        BigDecimal peakPnl = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (BacktestTrade t : trades) {
            BigDecimal pnl = t.pnlAmount();
            totalPoints += t.pnlPoints();

            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                grossProfit = grossProfit.add(pnl);
            } else {
                grossLoss = grossLoss.add(pnl.abs());
            }

            runningPnl = runningPnl.add(pnl);
            if (runningPnl.compareTo(peakPnl) > 0) {
                peakPnl = runningPnl;
            }
            BigDecimal dd = peakPnl.subtract(runningPnl);
            if (dd.compareTo(maxDrawdown) > 0) {
                maxDrawdown = dd;
            }
        }

        BigDecimal netPnL = grossProfit.subtract(grossLoss).setScale(2, RoundingMode.HALF_UP);
        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.99 : 0.0);

        return new BacktestResult(
                STRATEGY_ID,
                "NIFTY50",
                daysTested,
                totalTrades,
                wins,
                losses,
                Math.round(winRate * 100.0) / 100.0,
                Math.round(totalPoints * 100.0) / 100.0,
                grossProfit.setScale(2, RoundingMode.HALF_UP),
                grossLoss.setScale(2, RoundingMode.HALF_UP),
                netPnL,
                maxDrawdown.setScale(2, RoundingMode.HALF_UP),
                profitFactor,
                trades);
    }

    private BacktestResult emptyResult(int daysTested) {
        return new BacktestResult(
                STRATEGY_ID,
                "NIFTY50",
                daysTested,
                0, 0, 0, 0.0, 0.0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0.0, List.of());
    }
}

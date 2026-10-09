package com.tradingbot.runner;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.commodity.model.CommodityBias;
import com.tradingbot.strategy.commodity.model.CommodityTradePosition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("research")
class ShoonyaCrudeOilBacktestRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Test
    @DisplayName(
            "RESEARCH (price-bias, not production): Shoonya Crude Oil Mini (CRUDEOILM) 1:2.5 Full Exit")
    void testShoonyaCrudeOilBacktest() {
        ShoonyaConfig config = ShoonyaConfig.load();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService marketDataService = new ShoonyaMarketDataService(config, auth);
        TechnicalAnalysisService taService = new TechnicalAnalysisService();

        System.out.println(
                "==========================================================================================");
        System.out.println(
                " 🚀 MCX CRUDE OIL MINI (CRUDEOILM) - SHOONYA HISTORICAL TPSERIES REPLAY (2.5 RR FULL EXIT)");
        System.out.println(
                "==========================================================================================");

        // Fetch historical 15m candles from Shoonya for CRUDEOILM (Token 569901)
        String token = "569901";
        String symbol = "CRUDEOILM";
        String exchange = "MCX";
        int daysBack = 35; // Last 1 month plus

        System.out.printf(
                "[SHOONYA] Fetching %d-day 15m historical candles for %s:%s (Token %s) from Finvasia...\n",
                daysBack, exchange, symbol, token);

        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(exchange, token, symbol, "15", daysBack);
        if (candles == null || candles.isEmpty()) {
            System.out.printf(
                    "[SHOONYA] No candles returned for token %s. Trying near-month CRUDEOIL token 569900...\n",
                    token);
            token = "569900";
            candles =
                    marketDataService.fetchHistoricalCandles(
                            exchange, token, "CRUDEOIL", "15", daysBack);
        }

        if (candles == null || candles.isEmpty()) {
            System.err.println(
                    "❌ Could not fetch candles from Shoonya. Please verify active session.");
            assumeTrue(false, "No Shoonya candle data available - research replay skipped.");
            return;
        }

        System.out.printf(
                "✅ Successfully retrieved %d candles directly from Shoonya API!\n", candles.size());
        System.out.printf(
                "   First Candle: %s | Latest Candle: %s\n",
                DATE_FMT.format(candles.get(0).timestamp()),
                DATE_FMT.format(candles.get(candles.size() - 1).timestamp()));

        // Group by Trading Session (Date)
        Map<LocalDate, List<Candle>> sessionMap = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate d = LocalDate.ofInstant(c.timestamp(), IST);
            sessionMap.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
        }

        System.out.printf("   Total Trading Sessions: %d days\n\n", sessionMap.size());

        List<CommodityTradePosition> allTrades = new ArrayList<>();
        BigDecimal riskRewardRatio = new BigDecimal("2.5");
        int miniLotSize = 10; // 10 barrels for CRUDEOILM (1 pt = ₹10)
        double unitMultiplier = 1.0;

        for (Map.Entry<LocalDate, List<Candle>> entry : sessionMap.entrySet()) {
            LocalDate sessionDate = entry.getKey();
            List<Candle> dayCandles = entry.getValue();
            if (dayCandles.size() < 6) continue;

            // Calculate Intraday VWAP
            double[] vwapSeries = taService.calculateVwapSeries(dayCandles);

            // 1. Bias Determination at 13:30 IST
            Candle bar1330 = null;
            double vwap1330 = 0.0;
            Candle openBar = dayCandles.get(0);
            int idx1330 = -1;

            for (int i = 0; i < dayCandles.size(); i++) {
                Candle c = dayCandles.get(i);
                LocalTime t = LocalTime.ofInstant(c.timestamp(), IST);
                if (!t.isAfter(LocalTime.of(13, 30))) {
                    bar1330 = c;
                    vwap1330 = vwapSeries[i];
                    idx1330 = i;
                }
            }

            if (bar1330 == null || idx1330 < 1) continue;

            CommodityBias bias;
            double cClose = bar1330.close().doubleValue();
            double oPrice = openBar.open().doubleValue();
            if (cClose >= vwap1330 && cClose >= oPrice) {
                bias = CommodityBias.BULLISH;
            } else if (cClose <= vwap1330 && cClose <= oPrice) {
                bias = CommodityBias.BEARISH;
            } else {
                bias = (cClose >= vwap1330) ? CommodityBias.BULLISH : CommodityBias.BEARISH;
            }

            // 2. Scan for 15m VWAP crossover between 13:30 and 22:30 IST
            boolean armed = false;
            String armedSide = null;
            BigDecimal triggerPrice = null;
            BigDecimal setupVwap = null;
            CommodityTradePosition activePosition = null;

            for (int i = idx1330 + 1; i < dayCandles.size(); i++) {
                Candle currBar = dayCandles.get(i);
                Candle prevBar = dayCandles.get(i - 1);
                LocalTime barTime = LocalTime.ofInstant(currBar.timestamp(), IST);
                double currVwap = vwapSeries[i];
                double prevVwap = vwapSeries[i - 1];

                // A. Manage Active In-Trade Position
                if (activePosition != null) {
                    BigDecimal high = currBar.high();
                    BigDecimal low = currBar.low();
                    BigDecimal close = currBar.close();

                    if (barTime.isAfter(LocalTime.of(23, 14))) {
                        activePosition.close(
                                close, currBar.timestamp(), "EOD_SQUAREOFF", unitMultiplier);
                        allTrades.add(activePosition);
                        break;
                    } else if ("LONG".equalsIgnoreCase(activePosition.side())) {
                        if (low.compareTo(activePosition.stopLoss()) <= 0) {
                            activePosition.close(
                                    activePosition.stopLoss(),
                                    currBar.timestamp(),
                                    "STOP_LOSS",
                                    unitMultiplier);
                            allTrades.add(activePosition);
                            break;
                        } else if (high.compareTo(activePosition.targetPrice()) >= 0) {
                            activePosition.close(
                                    activePosition.targetPrice(),
                                    currBar.timestamp(),
                                    "TARGET_HIT",
                                    unitMultiplier);
                            allTrades.add(activePosition);
                            break;
                        }
                    } else if ("SHORT".equalsIgnoreCase(activePosition.side())) {
                        if (high.compareTo(activePosition.stopLoss()) >= 0) {
                            activePosition.close(
                                    activePosition.stopLoss(),
                                    currBar.timestamp(),
                                    "STOP_LOSS",
                                    unitMultiplier);
                            allTrades.add(activePosition);
                            break;
                        } else if (low.compareTo(activePosition.targetPrice()) <= 0) {
                            activePosition.close(
                                    activePosition.targetPrice(),
                                    currBar.timestamp(),
                                    "TARGET_HIT",
                                    unitMultiplier);
                            allTrades.add(activePosition);
                            break;
                        }
                    }
                    continue;
                }

                // B. Check Breakout Entry if Armed
                if (armed && activePosition == null) {
                    if ("LONG".equals(armedSide) && currBar.high().compareTo(triggerPrice) >= 0) {
                        BigDecimal entPrice = triggerPrice;
                        BigDecimal sl = setupVwap;
                        BigDecimal risk = entPrice.subtract(sl).abs();
                        if (risk.compareTo(entPrice.multiply(new BigDecimal("0.001"))) <= 0) {
                            risk = entPrice.multiply(new BigDecimal("0.002"));
                            sl = entPrice.subtract(risk);
                        }
                        activePosition =
                                CommodityTradePosition.createLong(
                                        "CRUDEOILM",
                                        entPrice,
                                        sl,
                                        riskRewardRatio,
                                        miniLotSize,
                                        currBar.timestamp());
                        armed = false;
                        continue;
                    } else if ("SHORT".equals(armedSide)
                            && currBar.low().compareTo(triggerPrice) <= 0) {
                        BigDecimal entPrice = triggerPrice;
                        BigDecimal sl = setupVwap;
                        BigDecimal risk = sl.subtract(entPrice).abs();
                        if (risk.compareTo(entPrice.multiply(new BigDecimal("0.001"))) <= 0) {
                            risk = entPrice.multiply(new BigDecimal("0.002"));
                            sl = entPrice.add(risk);
                        }
                        activePosition =
                                CommodityTradePosition.createShort(
                                        "CRUDEOILM",
                                        entPrice,
                                        sl,
                                        riskRewardRatio,
                                        miniLotSize,
                                        currBar.timestamp());
                        armed = false;
                        continue;
                    }
                }

                // C. VWAP Crossover Check (before 22:30 IST)
                if (barTime.isBefore(LocalTime.of(22, 30)) && activePosition == null) {
                    if (bias == CommodityBias.BULLISH
                            && prevBar.close().doubleValue() <= prevVwap
                            && currBar.close().doubleValue() > currVwap) {
                        armed = true;
                        armedSide = "LONG";
                        triggerPrice = currBar.high();
                        setupVwap = BigDecimal.valueOf(currVwap);
                    } else if (bias == CommodityBias.BEARISH
                            && prevBar.close().doubleValue() >= prevVwap
                            && currBar.close().doubleValue() < currVwap) {
                        armed = true;
                        armedSide = "SHORT";
                        triggerPrice = currBar.low();
                        setupVwap = BigDecimal.valueOf(currVwap);
                    }
                }
            }
        }

        printReport(allTrades);
    }

    private void printReport(List<CommodityTradePosition> trades) {
        if (trades.isEmpty()) {
            System.out.println("No trades executed across Shoonya historical data.");
            return;
        }

        long wins = trades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        long losses = trades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) <= 0).count();
        double wr = (double) wins / trades.size() * 100.0;

        BigDecimal totalPnl =
                trades.stream()
                        .map(CommodityTradePosition::pnl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossProfit =
                trades.stream()
                        .map(CommodityTradePosition::pnl)
                        .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss =
                trades.stream()
                        .map(CommodityTradePosition::pnl)
                        .filter(p -> p.compareTo(BigDecimal.ZERO) < 0)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .abs();

        double profitFactor =
                (grossLoss.compareTo(BigDecimal.ZERO) > 0)
                        ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                        : grossProfit.doubleValue();

        double avgWin =
                (wins > 0)
                        ? grossProfit
                                .divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP)
                                .doubleValue()
                        : 0.0;
        double avgLoss =
                (losses > 0)
                        ? grossLoss
                                .divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP)
                                .doubleValue()
                        : 0.0;

        System.out.println(
                "==========================================================================================");
        System.out.println(
                " 📊 OFFICIAL SHOONYA MCX CRUDE OIL (CRUDEOILM) BACKTEST PERFORMANCE (1:2.5 FULL EXIT)");
        System.out.println(
                "==========================================================================================");
        System.out.printf(" Total Trades Executed   : %d\n", trades.size());
        System.out.printf(" Winning Trades          : %d (%.1f%%)\n", wins, wr);
        System.out.printf(" Losing Trades           : %d (%.1f%%)\n", losses, 100.0 - wr);
        System.out.printf(" Profit Factor           : %.2f\n", profitFactor);
        System.out.printf(" Net Realized P&L        : ₹%,.2f\n", totalPnl);
        System.out.printf(" Average Winning Trade   : ₹%,.2f\n", avgWin);
        System.out.printf(" Average Losing Trade    : ₹%,.2f\n", avgLoss);
        System.out.println(
                "==========================================================================================\n");

        System.out.println("📋 DETAILED TRADE LOG FROM SHOONYA MARKET DATA:");
        System.out.printf(
                "%-12s | %-6s | %-6s | %-14s | %-14s | %-16s | %-14s\n",
                "Date", "Time", "Side", "Entry (₹)", "Exit (₹)", "Reason", "Realized P&L");
        System.out.println(
                "------------------------------------------------------------------------------------------");
        for (CommodityTradePosition t : trades) {
            String dStr = DATE_FMT.format(t.entryTime());
            String tStr = TIME_FMT.format(t.entryTime());
            System.out.printf(
                    "%-12s | %-6s | %-6s | ₹%-13.1f | ₹%-13.1f | %-16s | ₹%,.2f\n",
                    dStr, tStr, t.side(), t.entryPrice(), t.exitPrice(), t.exitReason(), t.pnl());
        }
    }
}

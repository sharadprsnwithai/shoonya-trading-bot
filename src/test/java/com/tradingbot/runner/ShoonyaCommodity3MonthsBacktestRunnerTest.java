package com.tradingbot.runner;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShoonyaCommodity3MonthsBacktestRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Test
    @DisplayName("Comprehensive 3-Month Shoonya Replay for Silver, Crude Oil, and Gold Mini (20-EMA Filter + 2.5 RR)")
    void testShoonyaCommodities3MonthsBacktest() {
        ShoonyaConfig config = ShoonyaConfig.load();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService marketDataService = new ShoonyaMarketDataService(config, auth);
        TechnicalAnalysisService taService = new TechnicalAnalysisService();

        System.out.println("==========================================================================================");
        System.out.println(" 🚀 MCX 3-COMMODITY OFFICIAL SHOONYA REPLAY: SILVER, CRUDE OIL & GOLD (2.5 RR)");
        System.out.println("==========================================================================================");
        System.out.println("⚙️ Strategy: 1:30 PM Directional Bias + 15m VWAP Crossover + 20-EMA Trend Filter");
        System.out.println("📦 Risk Model: 1:2.5 RR Full Target Exit | SL Anchored to VWAP | Max 1 Trade/Day | EOD 23:15");
        System.out.println("==========================================================================================\n");

        int daysBack = 90;

        // 1. Run Silver Mini (SILVERM: 5 kg, 1 pt = ₹5)
        List<CommodityTradePosition> silverTrades = runSingleCommodityReplay(
                "SILVERM", "483080", "495214", "MCX", 5, 1.0, daysBack, marketDataService, taService);

        // 2. Run Crude Oil Mini (CRUDEOILM: 10 barrels, 1 pt = ₹10)
        List<CommodityTradePosition> crudeTrades = runSingleCommodityReplay(
                "CRUDEOILM", "569901", "569900", "MCX", 10, 1.0, daysBack, marketDataService, taService);

        // 3. Run Gold Mini (GOLDM: 100 grams / 10 units of 10g, 1 pt = ₹10)
        List<CommodityTradePosition> goldTrades = runSingleCommodityReplay(
                "GOLDM", "571445", "495213", "MCX", 10, 1.0, daysBack, marketDataService, taService);

        // 4. Print Individual Reports
        printSingleReport("SILVER MINI (SILVERM — 5 kg)", silverTrades);
        printSingleReport("CRUDE OIL MINI (CRUDEOILM — 10 bbl)", crudeTrades);
        printSingleReport("GOLD MINI (GOLDM — 100 g)", goldTrades);

        // 5. Print Combined Portfolio Summary
        List<CommodityTradePosition> combinedTrades = new ArrayList<>();
        if (silverTrades != null) combinedTrades.addAll(silverTrades);
        if (crudeTrades != null) combinedTrades.addAll(crudeTrades);
        if (goldTrades != null) combinedTrades.addAll(goldTrades);
        combinedTrades.sort((a, b) -> a.entryTime().compareTo(b.entryTime()));

        printCombinedSummary(combinedTrades);
    }

    private List<CommodityTradePosition> runSingleCommodityReplay(
            String symbol,
            String primaryToken,
            String fallbackToken,
            String exchange,
            int lotSize,
            double unitMultiplier,
            int daysBack,
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService) {

        System.out.printf("[SHOONYA] Fetching %d-day 15m candles for %s:%s (Token %s)...\n",
                daysBack, exchange, symbol, primaryToken);

        List<Candle> candles = marketDataService.fetchHistoricalCandles(exchange, primaryToken, symbol, "15", daysBack);
        if (candles == null || candles.isEmpty()) {
            System.out.printf("[SHOONYA] Fallback to secondary token %s for %s...\n", fallbackToken, symbol);
            candles = marketDataService.fetchHistoricalCandles(exchange, fallbackToken, symbol, "15", daysBack);
        }

        if (candles == null || candles.isEmpty()) {
            System.err.printf("❌ Could not fetch candles from Shoonya for %s.\n", symbol);
            return Collections.emptyList();
        }

        System.out.printf("✅ Retrieved %d candles from Shoonya (%s to %s)\n",
                candles.size(),
                DATE_FMT.format(candles.get(0).timestamp()),
                DATE_FMT.format(candles.get(candles.size() - 1).timestamp()));

        Map<LocalDate, List<Candle>> sessionMap = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate d = LocalDate.ofInstant(c.timestamp(), IST);
            sessionMap.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
        }

        List<CommodityTradePosition> trades = new ArrayList<>();
        BigDecimal riskRewardRatio = new BigDecimal("2.5");

        for (Map.Entry<LocalDate, List<Candle>> entryMap : sessionMap.entrySet()) {
            LocalDate sessionDate = entryMap.getKey();
            List<Candle> dayCandles = entryMap.getValue();
            if (dayCandles.size() < 6) continue;

            double[] vwapSeries = taService.calculateVwapSeries(dayCandles);
            double[] closes = dayCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            double[] ema20Series = taService.calculateEmaSeries(closes, 20);

            // Bias at 13:30 IST
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

            boolean armed = false;
            String armedSide = null;
            BigDecimal triggerPrice = null;
            BigDecimal setupVwap = null;
            int armedIndex = -1;
            CommodityTradePosition activePosition = null;

            for (int i = idx1330 + 1; i < dayCandles.size(); i++) {
                Candle currBar = dayCandles.get(i);
                Candle prevBar = dayCandles.get(i - 1);
                LocalTime barTime = LocalTime.ofInstant(currBar.timestamp(), IST);
                double currVwap = vwapSeries[i];
                double prevVwap = vwapSeries[i - 1];
                double currEma20 = ema20Series[i];

                if (activePosition != null) {
                    BigDecimal high = currBar.high();
                    BigDecimal low = currBar.low();
                    BigDecimal close = currBar.close();

                    if (barTime.isAfter(LocalTime.of(23, 14))) {
                        activePosition.close(close, currBar.timestamp(), "EOD_SQUAREOFF", unitMultiplier);
                        trades.add(activePosition);
                        break;
                    } else if ("LONG".equalsIgnoreCase(activePosition.side())) {
                        if (low.compareTo(activePosition.stopLoss()) <= 0) {
                            activePosition.close(activePosition.stopLoss(), currBar.timestamp(), "STOP_LOSS", unitMultiplier);
                            trades.add(activePosition);
                            break;
                        } else if (high.compareTo(activePosition.targetPrice()) >= 0) {
                            activePosition.close(activePosition.targetPrice(), currBar.timestamp(), "TARGET_HIT", unitMultiplier);
                            trades.add(activePosition);
                            break;
                        }
                    } else if ("SHORT".equalsIgnoreCase(activePosition.side())) {
                        if (high.compareTo(activePosition.stopLoss()) >= 0) {
                            activePosition.close(activePosition.stopLoss(), currBar.timestamp(), "STOP_LOSS", unitMultiplier);
                            trades.add(activePosition);
                            break;
                        } else if (low.compareTo(activePosition.targetPrice()) <= 0) {
                            activePosition.close(activePosition.targetPrice(), currBar.timestamp(), "TARGET_HIT", unitMultiplier);
                            trades.add(activePosition);
                            break;
                        }
                    }
                    continue;
                }

                // Check Breakout with 20 EMA trend confirmation & fresh age <= 2 bars (30 min)
                if (armed && activePosition == null) {
                    int age = i - armedIndex;
                    if (age > 2) {
                        armed = false;
                    } else {
                        boolean trendOk = "LONG".equals(armedSide)
                                ? (Double.isNaN(currEma20) || currBar.close().doubleValue() >= currEma20)
                                : (Double.isNaN(currEma20) || currBar.close().doubleValue() <= currEma20);

                        if (trendOk) {
                            if ("LONG".equals(armedSide) && currBar.high().compareTo(triggerPrice) >= 0) {
                                BigDecimal entPrice = triggerPrice;
                                BigDecimal sl = setupVwap;
                                BigDecimal risk = entPrice.subtract(sl).abs();
                                if (risk.compareTo(entPrice.multiply(new BigDecimal("0.001"))) <= 0) {
                                    risk = entPrice.multiply(new BigDecimal("0.002"));
                                    sl = entPrice.subtract(risk);
                                }
                                activePosition = CommodityTradePosition.createLong(
                                        symbol, entPrice, sl, riskRewardRatio, lotSize, currBar.timestamp());
                                armed = false;
                                continue;
                            } else if ("SHORT".equals(armedSide) && currBar.low().compareTo(triggerPrice) <= 0) {
                                BigDecimal entPrice = triggerPrice;
                                BigDecimal sl = setupVwap;
                                BigDecimal risk = sl.subtract(entPrice).abs();
                                if (risk.compareTo(entPrice.multiply(new BigDecimal("0.001"))) <= 0) {
                                    risk = entPrice.multiply(new BigDecimal("0.002"));
                                    sl = entPrice.add(risk);
                                }
                                activePosition = CommodityTradePosition.createShort(
                                        symbol, entPrice, sl, riskRewardRatio, lotSize, currBar.timestamp());
                                armed = false;
                                continue;
                            }
                        }
                    }
                }

                // VWAP Crossover Check (15:30 to 22:30 IST)
                if (barTime.isAfter(LocalTime.of(15, 29)) && barTime.isBefore(LocalTime.of(22, 30)) && activePosition == null) {
                    if (bias == CommodityBias.BULLISH && prevBar.close().doubleValue() <= prevVwap && currBar.close().doubleValue() > currVwap) {
                        armed = true;
                        armedSide = "LONG";
                        triggerPrice = currBar.high();
                        setupVwap = BigDecimal.valueOf(currVwap);
                        armedIndex = i;
                    } else if (bias == CommodityBias.BEARISH && prevBar.close().doubleValue() >= prevVwap && currBar.close().doubleValue() < currVwap) {
                        armed = true;
                        armedSide = "SHORT";
                        triggerPrice = currBar.low();
                        setupVwap = BigDecimal.valueOf(currVwap);
                        armedIndex = i;
                    }
                }
            }
        }

        return trades;
    }

    private void printSingleReport(String title, List<CommodityTradePosition> trades) {
        if (trades.isEmpty()) return;

        long wins = trades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        long losses = trades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) <= 0).count();
        double wr = (double) wins / trades.size() * 100.0;

        BigDecimal totalPnl = trades.stream().map(CommodityTradePosition::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossProfit = trades.stream().map(CommodityTradePosition::pnl).filter(p -> p.compareTo(BigDecimal.ZERO) > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = trades.stream().map(CommodityTradePosition::pnl).filter(p -> p.compareTo(BigDecimal.ZERO) < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        double profitFactor = (grossLoss.compareTo(BigDecimal.ZERO) > 0)
                ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                : grossProfit.doubleValue();

        double avgWin = (wins > 0) ? grossProfit.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP).doubleValue() : 0.0;
        double avgLoss = (losses > 0) ? grossLoss.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP).doubleValue() : 0.0;

        System.out.println("\n" + "=".repeat(95));
        System.out.printf(" 📊 %s — PERFORMANCE (LAST 3 MONTHS - SHOONYA DATA)\n", title);
        System.out.println("=".repeat(95));
        System.out.printf(" Total Trades Executed   : %d\n", trades.size());
        System.out.printf(" Winning Trades          : %d (%.1f%%)\n", wins, wr);
        System.out.printf(" Losing Trades           : %d (%.1f%%)\n", losses, 100.0 - wr);
        System.out.printf(" Profit Factor           : %.2f\n", profitFactor);
        System.out.printf(" Net Realized P&L        : ₹%,.2f\n", totalPnl);
        System.out.printf(" Average Winning Trade   : ₹%,.2f\n", avgWin);
        System.out.printf(" Average Losing Trade    : ₹%,.2f\n", avgLoss);
        System.out.println("=".repeat(95));
    }

    private void printCombinedSummary(List<CommodityTradePosition> allTrades) {
        if (allTrades.isEmpty()) return;

        long wins = allTrades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        long losses = allTrades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) <= 0).count();
        double wr = (double) wins / allTrades.size() * 100.0;

        BigDecimal totalPnl = allTrades.stream().map(CommodityTradePosition::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossProfit = allTrades.stream().map(CommodityTradePosition::pnl).filter(p -> p.compareTo(BigDecimal.ZERO) > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = allTrades.stream().map(CommodityTradePosition::pnl).filter(p -> p.compareTo(BigDecimal.ZERO) < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();

        double profitFactor = (grossLoss.compareTo(BigDecimal.ZERO) > 0)
                ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
                : grossProfit.doubleValue();

        double avgWin = (wins > 0) ? grossProfit.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP).doubleValue() : 0.0;
        double avgLoss = (losses > 0) ? grossLoss.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP).doubleValue() : 0.0;

        System.out.println("\n" + "#".repeat(95));
        System.out.println(" 🏆 COMBINED 3-COMMODITY PORTFOLIO (SILVERM + CRUDEOILM + GOLDM) — LAST 3 MONTHS");
        System.out.println("#".repeat(95));
        System.out.printf(" Total Portfolio Trades  : %d\n", allTrades.size());
        System.out.printf(" Winning Trades          : %d (%.1f%%)\n", wins, wr);
        System.out.printf(" Losing Trades           : %d (%.1f%%)\n", losses, 100.0 - wr);
        System.out.printf(" Portfolio Profit Factor : %.2f\n", profitFactor);
        System.out.printf(" Total Net Realized P&L  : ₹%,.2f\n", totalPnl);
        System.out.printf(" Average Winning Trade   : ₹%,.2f\n", avgWin);
        System.out.printf(" Average Losing Trade    : ₹%,.2f\n", avgLoss);
        System.out.println("#".repeat(95));
    }
}

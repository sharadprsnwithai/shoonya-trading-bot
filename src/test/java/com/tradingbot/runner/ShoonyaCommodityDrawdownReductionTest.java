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

class ShoonyaCommodityDrawdownReductionTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Test
    @DisplayName("Evaluate Drawdown Reduction Mechanisms on 3-Month Shoonya Data (RSI + EMA20 + Risk Cap)")
    void testShoonyaDrawdownReduction() {
        ShoonyaConfig config = ShoonyaConfig.load();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService marketDataService = new ShoonyaMarketDataService(config, auth);
        TechnicalAnalysisService taService = new TechnicalAnalysisService();

        System.out.println("==========================================================================================");
        System.out.println(" 🛡️ MCX 3-MONTH SHOONYA DATA: DRAWDOWN REDUCTION & FORTRESS GUARD STUDY");
        System.out.println("==========================================================================================");

        int daysBack = 90;

        // Fetch Shoonya Candles
        List<Candle> silverCandles = marketDataService.fetchHistoricalCandles("MCX", "483080", "SILVERM", "15", daysBack);
        List<Candle> crudeCandles = marketDataService.fetchHistoricalCandles("MCX", "569901", "CRUDEOILM", "15", daysBack);

        // Run Baseline vs RSI Fortress Guard
        System.out.println("\n[EXPERIMENT 1] SILVER MINI (SILVERM — 5 kg):");
        runReplayVariation("SILVERM", silverCandles, 5, 1.0, taService, false);
        runReplayVariation("SILVERM + RSI Guard (50-72 L / 28-50 S)", silverCandles, 5, 1.0, taService, true);

        System.out.println("\n[EXPERIMENT 2] CRUDE OIL MINI (CRUDEOILM — 10 bbl):");
        runReplayVariation("CRUDEOILM", crudeCandles, 10, 1.0, taService, false);
        runReplayVariation("CRUDEOILM + RSI Guard (50-72 L / 28-50 S)", crudeCandles, 10, 1.0, taService, true);
    }

    private void runReplayVariation(
            String label,
            List<Candle> candles,
            int lotSize,
            double unitMultiplier,
            TechnicalAnalysisService taService,
            boolean useRsiGuard) {

        if (candles == null || candles.isEmpty()) return;

        Map<LocalDate, List<Candle>> sessionMap = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate d = LocalDate.ofInstant(c.timestamp(), IST);
            sessionMap.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
        }

        List<CommodityTradePosition> trades = new ArrayList<>();
        BigDecimal riskRewardRatio = new BigDecimal("2.5");

        for (Map.Entry<LocalDate, List<Candle>> entryMap : sessionMap.entrySet()) {
            List<Candle> dayCandles = entryMap.getValue();
            if (dayCandles.size() < 6) continue;

            double[] vwapSeries = taService.calculateVwapSeries(dayCandles);
            double[] closes = dayCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            double[] ema20Series = taService.calculateEmaSeries(closes, 20);
            double[] rsiSeries = taService.calculateRsiSeries(closes, 14);

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
                double currRsi = (rsiSeries != null && rsiSeries.length > i) ? rsiSeries[i] : 50.0;

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

                // Check Breakout with 20 EMA trend confirmation + RSI Momentum Guard
                if (armed && activePosition == null) {
                    int age = i - armedIndex;
                    if (age > 2) {
                        armed = false;
                    } else {
                        boolean trendOk = "LONG".equals(armedSide)
                                ? (Double.isNaN(currEma20) || currBar.close().doubleValue() >= currEma20)
                                : (Double.isNaN(currEma20) || currBar.close().doubleValue() <= currEma20);

                        boolean rsiOk = true;
                        if (useRsiGuard && !Double.isNaN(currRsi)) {
                            if ("LONG".equals(armedSide)) {
                                rsiOk = (currRsi >= 50.0 && currRsi <= 72.0);
                            } else {
                                rsiOk = (currRsi >= 28.0 && currRsi <= 50.0);
                            }
                        }

                        if (trendOk && rsiOk) {
                            if ("LONG".equals(armedSide) && currBar.high().compareTo(triggerPrice) >= 0) {
                                BigDecimal entPrice = triggerPrice;
                                BigDecimal sl = setupVwap;
                                BigDecimal risk = entPrice.subtract(sl).abs();
                                if (risk.compareTo(entPrice.multiply(new BigDecimal("0.001"))) <= 0) {
                                    risk = entPrice.multiply(new BigDecimal("0.002"));
                                    sl = entPrice.subtract(risk);
                                }
                                activePosition = CommodityTradePosition.createLong(
                                        "SILVERM", entPrice, sl, riskRewardRatio, lotSize, currBar.timestamp());
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
                                        "SILVERM", entPrice, sl, riskRewardRatio, lotSize, currBar.timestamp());
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

        printReport(label, trades);
    }

    private void printReport(String label, List<CommodityTradePosition> trades) {
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

        // Calculate Max Drawdown
        BigDecimal cumPnl = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (CommodityTradePosition t : trades) {
            cumPnl = cumPnl.add(t.pnl());
            if (cumPnl.compareTo(peak) > 0) peak = cumPnl;
            BigDecimal dd = peak.subtract(cumPnl);
            if (dd.compareTo(maxDd) > 0) maxDd = dd;
        }

        System.out.printf("  • %-48s | Trades: %2d | Win Rate: %5.1f%% | PF: %4.2f | Net P&L: ₹%,10.2f | Max DD: ₹%,8.2f\n",
                label, trades.size(), wr, profitFactor, totalPnl, maxDd);
    }
}

package com.tradingbot.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingService;
import com.tradingbot.strategy.driftvwap.config.DriftVwapProperties;
import com.tradingbot.strategy.driftvwap.model.DriftDirection;
import com.tradingbot.strategy.driftvwap.model.DriftVwapPosition;
import com.tradingbot.strategy.driftvwap.model.DriftVwapTrendState;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CandleResamplingUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShoonyaLast30DaysDriftVwapReplayRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Test
    @DisplayName(
            "Replay 1-Month (30 Days) Real Historical NIFTY 50 Market Data through DriftVwapOptionSellingService (2 Lots)")
    void testReplayLast30DaysDriftVwap() {
        System.out.println(
                "==========================================================================================================");
        System.out.println(
                "  1-MONTH REAL 5-MINUTE CANDLE REPLAY - DRIFT VWAP ATM OPTION SELLING STRATEGY (NIFTY 50 INDEX)");
        System.out.println(
                "==========================================================================================================");
        System.out.println(
                "⚙️ Configuration: Fixed 2 Lots (150 Qty) | 15m VWAP Drift (>=0.12% 1-Hr Momentum) | 5m Pullback Trigger");
        System.out.println(
                "📦 Execution Structure: SELL ATM Options (PE on Bullish, CE on Bearish) | Target: 70% Decay | SL: +60% Exp");
        System.out.println(
                "🛡️ Guardrails: Max 1 Position | Max 4 Trades/Day | Halt on 2 Losses/Day | 14:55 Cutoff | 15:10 Hard EOD Exit");
        System.out.println(
                "==========================================================================================================\n");

        YahooFinanceService yfService = new YahooFinanceService(new ObjectMapper());
        System.out.println("[FETCH] Downloading 30-day 5-minute historical candles for NIFTY 50 Index from Yahoo Finance...");

        List<Candle> allCandles = yfService.fetch5MinCandles("NIFTY 50", 35);
        if (allCandles == null || allCandles.isEmpty()) {
            System.out.println("[WARN] Yahoo fetch empty, falling back to ^NSEI ticker...");
            allCandles = yfService.fetch5MinCandles("^NSEI", 35);
        }

        assertThat(allCandles).isNotEmpty();
        System.out.printf("[SETUP] Successfully loaded %d 5-minute candles.\n", allCandles.size());

        // Group candles by local trading date in IST
        Map<LocalDate, List<Candle>> dayMap = new TreeMap<>();
        for (Candle c : allCandles) {
            LocalDate date = LocalDate.ofInstant(c.timestamp(), IST);
            dayMap.computeIfAbsent(date, k -> new ArrayList<>()).add(c);
        }

        List<LocalDate> tradingDates = new ArrayList<>(dayMap.keySet());
        System.out.printf("[SETUP] Evaluated %d trading sessions from %s to %s\n\n",
                tradingDates.size(), tradingDates.get(0), tradingDates.get(tradingDates.size() - 1));

        // Initialize Strategy Engine
        ShoonyaMarketDataService mockMarketData = mock(ShoonyaMarketDataService.class);
        when(mockMarketData.resolveToken(any())).thenReturn("12345");
        ShoonyaOptionChainService mockOptionChain = mock(ShoonyaOptionChainService.class);
        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        SignalPublisher mockPublisher = mock(SignalPublisher.class);
        TelegramService mockTelegram = mock(TelegramService.class);

        DriftVwapProperties properties = new DriftVwapProperties();
        properties.setUnderlying("NIFTY");
        properties.setLots(2); // 2 Lots (150 Qty)
        properties.setMinMomentumPct(0.12);
        properties.setTargetDecayPct(70.0);
        properties.setSlExpansionPct(60.0);
        properties.setMaxDailyTrades(4);
        properties.setMaxDailyLosses(2);

        DriftVwapOptionSellingService service =
                new DriftVwapOptionSellingService(
                        mockMarketData,
                        mockOptionChain,
                        taService,
                        mockPublisher,
                        mockTelegram,
                        properties);

        List<DriftVwapPosition> executedTrades = new ArrayList<>();
        Map<LocalDate, Double> dailyPnlMap = new LinkedHashMap<>();

        // Replay Session by Session
        for (LocalDate tradeDate : tradingDates) {
            List<Candle> session5m = dayMap.get(tradeDate);
            if (session5m == null || session5m.size() < 15) continue;

            service.resetDaily();
            double sessionPnl = 0.0;

            // Determine Days to Expiry (Weekly expiry on Thursday = Day 4)
            int dayOfWeek = tradeDate.getDayOfWeek().getValue(); // 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri
            double dte = (dayOfWeek <= 4) ? (4 - dayOfWeek + 0.5) : (11 - dayOfWeek + 0.5);

            for (int i = 12; i < session5m.size(); i++) {
                Candle curr5m = session5m.get(i);
                LocalTime cTime = LocalTime.ofInstant(curr5m.timestamp(), IST);
                Instant currTime = curr5m.timestamp();
                service.setClock(Clock.fixed(currTime, IST));

                // 1. Manage Active Position (Intra-candle live price check simulation)
                if (service.getOpenPosition() != null && !service.getOpenPosition().isClosed()) {
                    DriftVwapPosition pos = service.getOpenPosition();
                    double entrySpot = pos.getEntrySpotPrice().doubleValue();
                    double entryPrem = pos.getEntryPremium().doubleValue();
                    double bHigh = curr5m.high().doubleValue();
                    double bLow = curr5m.low().doubleValue();
                    double bClose = curr5m.close().doubleValue();

                    double barsHeld = Math.max(1.0, java.time.Duration.between(pos.getEntryTime(), currTime).toMinutes() / 5.0);
                    double currDte = Math.max(0.2, dte - (barsHeld / 75.0));

                    double targetPrem = pos.getTargetPremium().doubleValue();
                    double slPrem = pos.getSlPremium().doubleValue();

                    // Estimate premium using Black-Scholes Greeks with time decay
                    double premAtHigh = estimateOptionPremium(bHigh, pos.getStrikePrice().doubleValue(), pos.getOptionType(), 0.13, currDte);
                    double premAtLow = estimateOptionPremium(bLow, pos.getStrikePrice().doubleValue(), pos.getOptionType(), 0.13, currDte);
                    double premAtClose = estimateOptionPremium(bClose, pos.getStrikePrice().doubleValue(), pos.getOptionType(), 0.13, currDte);

                    double minPrem = Math.min(premAtHigh, Math.min(premAtLow, premAtClose));
                    double maxPrem = Math.max(premAtHigh, Math.max(premAtLow, premAtClose));

                    // Mock live quote fetch for 30s evaluation
                    when(mockMarketData.fetchQuote(any(), any()))
                            .thenReturn(new ObjectMapper().createObjectNode().put("lp", premAtClose));

                    // Check Hard Exit at 15:10 IST
                    if (!cTime.isBefore(LocalTime.of(15, 10))) {
                        pos.close(BigDecimal.valueOf(premAtClose), "15:10_THETA_EOD_EXIT", currTime);
                        executedTrades.add(pos);
                        sessionPnl += pos.getRealizedPnl().doubleValue();
                        break;
                    } else if (maxPrem >= slPrem) {
                        pos.close(BigDecimal.valueOf(slPrem), "OPTION_SL_EXPANSION", currTime);
                        executedTrades.add(pos);
                        sessionPnl += pos.getRealizedPnl().doubleValue();
                        service.getTodayLossCount().incrementAndGet();
                    } else if (minPrem <= targetPrem) {
                        pos.close(BigDecimal.valueOf(targetPrem), "OPTION_TARGET_DECAY", currTime);
                        executedTrades.add(pos);
                        sessionPnl += pos.getRealizedPnl().doubleValue();
                    }
                }

                // 2. Evaluate 15m Drift and 5m Pullback Entry Trigger
                if (service.getOpenPosition() == null || service.getOpenPosition().isClosed()) {
                    if (service.getTodayTradesCount().get() >= 4 || service.getTodayLossCount().get() >= 2) {
                        continue;
                    }
                    if (!cTime.isBefore(LocalTime.of(14, 55))) {
                        continue;
                    }

                    List<Candle> sub5m = session5m.subList(0, i + 1);
                    List<Candle> candles15m = CandleResamplingUtil.resample5MinTo15Min(sub5m);
                    if (candles15m.size() < 4) continue;

                    DriftVwapTrendState trend = service.evaluate15mDrift(candles15m);
                    boolean triggered = service.check5mPullbackTrigger(trend.direction(), curr5m);

                    if (triggered && (i + 1 < session5m.size())) {
                        Candle nextBar = session5m.get(i + 1);
                        double nextOpen = nextBar.open().doubleValue();
                        BigDecimal strike = DriftVwapOptionSellingService.roundStrike(BigDecimal.valueOf(nextOpen), 50);
                        String optType = (trend.direction() == DriftDirection.BULLISH_DRIFT) ? "PE" : "CE";

                        double initialPrem = estimateOptionPremium(nextOpen, strike.doubleValue(), optType, 0.13, dte);
                        when(mockMarketData.fetchQuote(any(), any()))
                                .thenReturn(new ObjectMapper().createObjectNode().put("lp", initialPrem));

                        service.setClock(Clock.fixed(nextBar.timestamp(), IST));
                        service.executeOptionSellingEntry(BigDecimal.valueOf(nextOpen), trend.direction());
                    }
                }
            }

            dailyPnlMap.put(tradeDate, sessionPnl);
        }

        // Output Analytics & Results
        printReplaySummary(executedTrades, dailyPnlMap, tradingDates.size());
    }

    private static double estimateOptionPremium(double spot, double strike, String optType, double iv, double dteDays) {
        double dteYears = Math.max(0.5, dteDays) / 365.0;
        double sigmaSqrtT = iv * Math.sqrt(dteYears);
        double d1 = (Math.log(spot / strike) + (0.5 * iv * iv) * dteYears) / sigmaSqrtT;
        double d2 = d1 - sigmaSqrtT;

        double prem;
        if ("CE".equalsIgnoreCase(optType)) {
            prem = (spot * normCdf(d1)) - (strike * normCdf(d2));
        } else {
            prem = (strike * normCdf(-d2)) - (spot * normCdf(-d1));
        }
        return Math.max(1.50, prem);
    }

    private static double normCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1.0 - t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return (z >= 0) ? ans : -ans;
    }

    private void printReplaySummary(List<DriftVwapPosition> trades, Map<LocalDate, Double> dailyPnls, int totalSessions) {
        System.out.println("==========================================================================================================");
        System.out.println(" 📊 JAVA REPLAY PERFORMANCE RESULTS - DRIFT VWAP OPTION SELLING (2 LOTS / 150 QTY)");
        System.out.println("==========================================================================================================");

        int totalTrades = trades.size();
        List<DriftVwapPosition> wins = trades.stream().filter(t -> t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0).toList();
        List<DriftVwapPosition> losses = trades.stream().filter(t -> t.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0).toList();
        double winRate = totalTrades > 0 ? (wins.size() * 100.0 / totalTrades) : 0.0;

        double grossProfit = wins.stream().mapToDouble(w -> w.getRealizedPnl().doubleValue()).sum();
        double grossLoss = Math.abs(losses.stream().mapToDouble(l -> l.getRealizedPnl().doubleValue()).sum());
        double netPnl = trades.stream().mapToDouble(t -> t.getRealizedPnl().doubleValue()).sum();
        double profitFactor = grossLoss > 0 ? (grossProfit / grossLoss) : (grossProfit > 0 ? 99.9 : 0.0);

        // Compute Drawdown
        double peak = 0.0;
        double cumPnl = 0.0;
        double maxDd = 0.0;
        for (double pnl : dailyPnls.values()) {
            cumPnl += pnl;
            if (cumPnl > peak) peak = cumPnl;
            double dd = peak - cumPnl;
            if (dd > maxDd) maxDd = dd;
        }

        // ₹75 flat round-trip cost per trade on 2 lots
        double totalFriction = totalTrades * 75.0;
        double netAfterFriction = netPnl - totalFriction;

        System.out.printf(" Total Replay Sessions:       %d trading days\n", totalSessions);
        System.out.printf(" Total Trades Executed:       %d trades (Avg %.1f trades/day)\n", totalTrades, (double) totalTrades / totalSessions);
        System.out.printf(" Winning Trades:              %d (%.1f%% Win Rate) ⭐\n", wins.size(), winRate);
        System.out.printf(" Losing Trades:               %d (%.1f%%)\n", losses.size(), (double) losses.size() * 100.0 / totalTrades);
        System.out.println("----------------------------------------------------------------------------------------------------------");
        System.out.printf(" Gross Realized Profit:       +₹%,.2f\n", grossProfit);
        System.out.printf(" Gross Realized Loss:         -₹%,.2f\n", grossLoss);
        System.out.printf(" Total Taxes & Brokerage:     ₹%,.2f (₹75/trade on 2 lots)\n", totalFriction);
        System.out.printf(" Net Realized P&L (Pre-Tax):  +₹%,.2f\n", netPnl);
        System.out.printf(" Net Realized P&L (Post-Tax): +₹%,.2f ⭐\n", netAfterFriction);
        System.out.printf(" Profit Factor:               %.2f\n", profitFactor);
        System.out.printf(" Average Trade Return:        ₹%,.2f / trade\n", totalTrades > 0 ? netPnl / totalTrades : 0.0);
        System.out.printf(" Maximum Strategy Drawdown:   ₹%,.2f\n", maxDd);
        System.out.printf(" Return on ₹10 Lakhs Capital: +%.2f%% (in 1 month)\n", (netAfterFriction / 1000000.0) * 100.0);
        System.out.println("----------------------------------------------------------------------------------------------------------");

        System.out.println(" Contract Type Breakdown:");
        long peTrades = trades.stream().filter(t -> "PE".equals(t.getOptionType())).count();
        long peWins = trades.stream().filter(t -> "PE".equals(t.getOptionType()) && t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0).count();
        double pePnl = trades.stream().filter(t -> "PE".equals(t.getOptionType())).mapToDouble(t -> t.getRealizedPnl().doubleValue()).sum();
        System.out.printf("  • SELL ATM PUT (PE) [Bullish Drift] : %2d trades | Win Rate: %5.1f%% | Net P&L: +₹%,.2f\n",
                peTrades, peTrades > 0 ? (peWins * 100.0 / peTrades) : 0.0, pePnl);

        long ceTrades = trades.stream().filter(t -> "CE".equals(t.getOptionType())).count();
        long ceWins = trades.stream().filter(t -> "CE".equals(t.getOptionType()) && t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0).count();
        double cePnl = trades.stream().filter(t -> "CE".equals(t.getOptionType())).mapToDouble(t -> t.getRealizedPnl().doubleValue()).sum();
        System.out.printf("  • SELL ATM CALL (CE) [Bearish Drift]: %2d trades | Win Rate: %5.1f%% | Net P&L: +₹%,.2f\n",
                ceTrades, ceTrades > 0 ? (ceWins * 100.0 / ceTrades) : 0.0, cePnl);

        System.out.println("----------------------------------------------------------------------------------------------------------");
        System.out.println(" Exit Breakdown:");
        Map<String, List<DriftVwapPosition>> byReason = new HashMap<>();
        for (DriftVwapPosition t : trades) {
            byReason.computeIfAbsent(t.getExitReason(), k -> new ArrayList<>()).add(t);
        }
        for (Map.Entry<String, List<DriftVwapPosition>> entry : byReason.entrySet()) {
            double rPnl = entry.getValue().stream().mapToDouble(t -> t.getRealizedPnl().doubleValue()).sum();
            System.out.printf("  • %-24s: %2d trades | Net Realized P&L: %s₹%,.2f\n",
                    entry.getKey(), entry.getValue().size(), rPnl >= 0 ? "+" : "-", Math.abs(rPnl));
        }

        System.out.println("==========================================================================================================\n");
        System.out.println(" Sample Executed Trades (Last 12 Trades):");
        System.out.printf("%-12s %-6s %-18s %-12s %-12s %-12s %-15s %s\n",
                "Date", "Type", "Contract", "Entry Prem", "Exit Prem", "Points", "Realized P&L", "Exit Reason");
        System.out.println("----------------------------------------------------------------------------------------------------------");

        int start = Math.max(0, trades.size() - 12);
        for (int i = start; i < trades.size(); i++) {
            DriftVwapPosition t = trades.get(i);
            BigDecimal pts = t.getEntryPremium().subtract(t.getExitPremium());
            System.out.printf("%-12s %-6s %-18s ₹%-11.2f ₹%-11.2f %+8.2f pts   %+11.2f   %s\n",
                    t.getEntryTime() != null ? LocalDate.ofInstant(t.getEntryTime(), IST).toString() : "N/A",
                    t.getOptionType(),
                    t.getContractSymbol(),
                    t.getEntryPremium().doubleValue(),
                    t.getExitPremium().doubleValue(),
                    pts.doubleValue(),
                    t.getRealizedPnl().doubleValue(),
                    t.getExitReason());
        }
        System.out.println("==========================================================================================================\n");
    }
}

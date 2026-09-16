package com.tradingbot.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class ShoonyaLive1MonthBacktestRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(IST);

    @Test
    void runOneMonthBacktestAgainstShoonya() {
        System.out.println(
                "================================================================================");
        System.out.println(
                " RUNNING 1-MONTH MULTI-INDICATOR OPTIONS STRATEGY ON NIFTY 50 (SHOONYA FEED)");
        System.out.println(
                "================================================================================");

        ShoonyaConfig config = ShoonyaConfig.load();
        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        ShoonyaAuthenticator authenticator =
                new ShoonyaAuthenticator(config, objectMapper, httpClient);
        ShoonyaMarketDataService marketDataService =
                new ShoonyaMarketDataService(config, authenticator, objectMapper, httpClient);
        TechnicalAnalysisService taService = new TechnicalAnalysisService();

        MultiIndicatorOptionsBacktestService backtestService =
                new MultiIndicatorOptionsBacktestService(marketDataService, taService);

        int daysBack = 30;

        System.out.println(
                "\n>>> EVALUATING NIFTY 50 (Token: 10576, Mode: OPTION_SELLING, 1 Lot = 65 Qty, Hedged: true)...");
        try {
            BacktestResult niftyResult =
                    backtestService.runBacktest(
                            daysBack,
                            "OPTION_SELLING",
                            1, // 1 Lot = 65 Qty
                            14, // RSI Period
                            1, // Max 1 trade per day
                            true, // Hedged Credit Spread (2% OTM)
                            2.0, // 2% OTM Hedge
                            true, // ADX Filter
                            22.0, // ADX >= 22.0
                            2.0, // 2% Spot SL
                            50.0 // 50% Take Profit
                            );
            printBacktestSummary("NIFTY 50 (1 Lot = 65 Qty)", niftyResult);
        } catch (Exception e) {
            System.err.println("Error running NIFTY 50 backtest: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void printBacktestSummary(String assetTitle, BacktestResult result) {
        if (result == null) {
            System.out.println("Null result returned for " + assetTitle);
            return;
        }

        System.out.println(
                "\n--------------------------------------------------------------------------------");
        System.out.println(" PERFORMANCE SUMMARY: " + assetTitle);
        System.out.println(
                "--------------------------------------------------------------------------------");
        System.out.printf("Strategy               : %s%n", result.strategyId());
        System.out.printf("Symbol                 : %s%n", result.symbol());
        System.out.printf("Days Tested            : %d%n", result.daysTested());
        System.out.printf("Total Trades Executed  : %d%n", result.totalTrades());
        System.out.printf("Winning Trades         : %d%n", result.winningTrades());
        System.out.printf("Losing Trades          : %d%n", result.losingTrades());
        System.out.printf("Win Rate               : %.2f%%%n", result.winRatePercent());
        System.out.printf("Total Points Captured  : %.2f pts%n", result.totalPointsCaptured());
        System.out.printf("Total Net PnL          : ₹%,.2f%n", result.netPnL());
        System.out.printf("Gross Profit           : ₹%,.2f%n", result.grossProfit());
        System.out.printf("Gross Loss             : ₹%,.2f%n", result.grossLoss());
        System.out.printf("Profit Factor          : %.2f%n", result.profitFactor());
        System.out.printf("Max Drawdown           : ₹%,.2f%n", result.maxDrawdown());

        if (result.trades() != null && !result.trades().isEmpty()) {
            System.out.println("\nTRADE-BY-TRADE LOG:");
            System.out.printf(
                    "%-4s | %-16s | %-16s | %-10s | %-10s | %-10s | %-10s | %-12s | %s%n",
                    "#",
                    "Entry Time",
                    "Exit Time",
                    "Action",
                    "Entry Px",
                    "Exit Px",
                    "Points",
                    "Net PnL (₹)",
                    "Exit Reason");
            System.out.println("-".repeat(125));

            for (BacktestTrade tr : result.trades()) {
                String entryStr = tr.entryTime() != null ? TIME_FMT.format(tr.entryTime()) : "-";
                String exitStr = tr.exitTime() != null ? TIME_FMT.format(tr.exitTime()) : "-";
                System.out.printf(
                        "%-4d | %-16s | %-16s | %-10s | %-10.2f | %-10.2f | %-10.2f | ₹%-11.2f |"
                                + " %s%n",
                        tr.tradeId(),
                        entryStr,
                        exitStr,
                        tr.action(),
                        tr.entryPrice(),
                        tr.exitPrice(),
                        tr.pnlPoints(),
                        tr.pnlAmount(),
                        tr.exitReason());
            }
        } else {
            System.out.println("No trades were triggered during this period.");
        }
        System.out.println(
                "--------------------------------------------------------------------------------\n");
    }
}

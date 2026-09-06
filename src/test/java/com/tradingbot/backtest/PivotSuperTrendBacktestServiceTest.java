package com.tradingbot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.impl.PivotSuperTrendOptionSellingStrategy;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PivotSuperTrendBacktestServiceTest {

    @Mock private ShoonyaMarketDataService marketDataService;

    @Mock private com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService;

    @Mock private com.tradingbot.telegram.TelegramService telegramService;

    @Test
    void testEmptyCandlesReturnsZeroResult() {
        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        PivotSuperTrendOptionSellingStrategy strategy =
                new PivotSuperTrendOptionSellingStrategy(
                        taService, optionChainService, telegramService);
        PivotSuperTrendBacktestService backtestService =
                new PivotSuperTrendBacktestService(marketDataService, strategy);

        BacktestResult result = backtestService.evaluateCandles(List.of());
        assertThat(result.totalTrades()).isZero();
        assertThat(result.netPnL()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testRunOneMonthBacktestWithHistoricalCandles() throws Exception {
        File dataFile = new File("scratch/nifty_5m_1mo.json");
        if (!dataFile.exists()) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(dataFile);
        JsonNode resultNode = root.path("chart").path("result").get(0);
        JsonNode timestamps = resultNode.path("timestamp");
        JsonNode quote = resultNode.path("indicators").path("quote").get(0);
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode closes = quote.path("close");
        JsonNode volumes = quote.path("volume");

        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            if (!closes.get(i).isNull()) {
                Instant t = Instant.ofEpochSecond(timestamps.get(i).asLong());
                BigDecimal o = BigDecimal.valueOf(opens.get(i).asDouble());
                BigDecimal h = BigDecimal.valueOf(highs.get(i).asDouble());
                BigDecimal l = BigDecimal.valueOf(lows.get(i).asDouble());
                BigDecimal c = BigDecimal.valueOf(closes.get(i).asDouble());
                long v = volumes.get(i).isNull() ? 0L : volumes.get(i).asLong();
                candles.add(new Candle("NIFTY50", "5m", t, o, h, l, c, v));
            }
        }

        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        PivotSuperTrendOptionSellingStrategy strategy =
                new PivotSuperTrendOptionSellingStrategy(
                        taService, optionChainService, telegramService);
        PivotSuperTrendBacktestService backtestService =
                new PivotSuperTrendBacktestService(marketDataService, strategy);

        BacktestResult result = backtestService.evaluateCandles(candles);
        assertThat(result).isNotNull();
        assertThat(result.strategyId()).isEqualTo(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        assertThat(result.daysTested()).isGreaterThanOrEqualTo(20);

        System.out.println(
                "=========================================================================");
        System.out.println("PIVOT SUPERTREND 1-MONTH BACKTEST EXECUTION RESULTS");
        System.out.println(
                "=========================================================================");
        System.out.println("Days Tested: " + result.daysTested());
        System.out.println("Total Trades: " + result.totalTrades());
        System.out.println("Winning Trades: " + result.winningTrades());
        System.out.println("Losing Trades: " + result.losingTrades());
        System.out.println("Win Rate: " + result.winRatePercent() + "%");
        System.out.println("Total PnL Points: " + result.totalPointsCaptured());
        System.out.println("Gross Profit: Rs. " + result.grossProfit());
        System.out.println("Gross Loss: Rs. " + result.grossLoss());
        System.out.println("Net Realized PnL: Rs. " + result.netPnL());
        System.out.println("Profit Factor: " + result.profitFactor());
        System.out.println("Max Drawdown: Rs. " + result.maxDrawdown());
        System.out.println(
                "-------------------------------------------------------------------------");
        System.out.println("EXECUTED TRADES LOG:");
        for (BacktestTrade trade : result.trades()) {
            System.out.printf(
                    "#%02d | %s | %s | Entry: %s @ %.2f | Exit: %s @ %.2f | Pts: %+.2f | PnL: Rs. %+.2f | Win: %s | Reason: %s%n",
                    trade.tradeId(),
                    trade.date(),
                    trade.symbol(),
                    trade.entryTime(),
                    trade.entryPrice(),
                    trade.exitTime(),
                    trade.exitPrice(),
                    trade.pnlPoints(),
                    trade.pnlAmount(),
                    trade.isWin() ? "WIN" : "LOSS",
                    trade.exitReason());
        }
        System.out.println(
                "=========================================================================");
    }
}

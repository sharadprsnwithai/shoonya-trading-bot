package com.tradingbot.service;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.BacktestTrade;
import com.tradingbot.backtest.TripleSuperTrendBacktestService;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Quantitative Basket Health Scoring Service for Dynamic Monthly Rebalancing. Evaluates all 30 F&O
 * instruments on trend quality, option buying profitability, volatility (ATR%), and whipsaw
 * frequency to determine the optimal Top 10 Curated Basket.
 */
@Service
public class BasketHealthScoringService {

    private static final Logger log = LoggerFactory.getLogger(BasketHealthScoringService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm").withZone(IST);

    private final TripleSuperTrendBacktestService backtestService;
    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;

    @Autowired
    public BasketHealthScoringService(
            TripleSuperTrendBacktestService backtestService,
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService) {
        this.backtestService = backtestService;
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
    }

    public record StockHealthScore(
            int rank,
            String symbol,
            String direction,
            double compositeScore,
            int totalTrades,
            double winRate,
            double profitFactor,
            BigDecimal netPnl,
            double avgAdx,
            double avgAtrPct,
            double whipsawRate,
            boolean isTop10) {}

    /** Evaluates and ranks all F&O instruments on recent hourly data. */
    public List<StockHealthScore> rankAllSymbols(int days) {
        List<String> symbols =
                StockFnoRegistry.getAllInstruments().keySet().stream()
                        .filter(s -> !s.equalsIgnoreCase("NIFTY") && !s.equalsIgnoreCase("NIFTY50"))
                        .distinct()
                        .toList();
        List<StockHealthScore> intermediate = new ArrayList<>();

        for (String symbol : symbols) {
            try {
                List<Candle> candles = marketDataService.fetchHourlyCandles(symbol, days);
                if (candles == null || candles.size() < 30) {
                    continue;
                }

                int size = candles.size();
                double[] high = new double[size];
                double[] low = new double[size];
                double[] close = new double[size];

                for (int i = 0; i < size; i++) {
                    Candle c = candles.get(i);
                    high[i] = c.high().doubleValue();
                    low[i] = c.low().doubleValue();
                    close[i] = c.close().doubleValue();
                }

                // 1. Run backtest with False Breakout filters in Option Buying Mode
                BacktestResult bt =
                        backtestService.evaluateCandles(
                                symbol, candles, 1, true, false, true, 22.0, 68.0, 32.0, 3, false,
                                true, 0.60, true, 0.60, 2.50, true, 50);

                // 2. Compute Average ADX
                double[] adxSeries = taService.calculateAdxSeries(high, low, close, 14);
                double adxSum = 0;
                int adxCount = 0;
                for (double a : adxSeries) {
                    if (!Double.isNaN(a) && a > 0) {
                        adxSum += a;
                        adxCount++;
                    }
                }
                double avgAdx = adxCount > 0 ? (adxSum / adxCount) : 0.0;

                // 3. Compute Normalized ATR% (ATR / Close * 100)
                double[] atrSeries = taService.calculateAtrSeries(high, low, close, 14);
                double atrPctSum = 0;
                int atrCount = 0;
                for (int i = 0; i < size; i++) {
                    if (!Double.isNaN(atrSeries[i]) && atrSeries[i] > 0 && close[i] > 0) {
                        atrPctSum += (atrSeries[i] / close[i]) * 100.0;
                        atrCount++;
                    }
                }
                double avgAtrPct = atrCount > 0 ? (atrPctSum / atrCount) : 0.0;

                // 4. Compute Whipsaw Rate (trades exited within 2 hours with a loss)
                int whipsawCount = 0;
                for (BacktestTrade trade : bt.trades()) {
                    if (!trade.isWin() && trade.entryTime() != null && trade.exitTime() != null) {
                        long durationSec =
                                trade.exitTime().getEpochSecond()
                                        - trade.entryTime().getEpochSecond();
                        if (durationSec <= 2 * 3600) {
                            whipsawCount++;
                        }
                    }
                }
                double whipsawRate =
                        bt.totalTrades() > 0
                                ? ((double) whipsawCount / bt.totalTrades()) * 100.0
                                : 0.0;

                // 5. Direction evaluation on latest candle (SuperTrend + 50 EMA)
                SuperTrendResult[] stSeries =
                        taService.calculateSuperTrendSeries(high, low, close, 7, 2.0);
                SuperTrendResult latestSt =
                        (stSeries != null && stSeries.length > 0) ? stSeries[size - 1] : null;
                double[] emaSeries = taService.calculateEmaSeries(close, 50);
                double latestEma =
                        (emaSeries != null && emaSeries.length > 0)
                                ? emaSeries[size - 1]
                                : Double.NaN;
                double latestClose = close[size - 1];

                String direction;
                boolean stBull = (latestSt != null && latestSt.isBullish());
                boolean emaBull = (!Double.isNaN(latestEma) && latestClose >= latestEma);

                if (stBull && emaBull) {
                    direction = "🟢 BULLISH (Buy CE)";
                } else if (!stBull && !emaBull) {
                    direction = "🔴 BEARISH (Buy PE)";
                } else if (stBull) {
                    direction = "🟢 BULLISH (Buy CE)";
                } else {
                    direction = "🔴 BEARISH (Buy PE)";
                }

                // 6. Composite Score (0 - 100)
                double pfScore = Math.min(35.0, Math.max(0.0, bt.profitFactor() * 17.5));
                double wrScore = Math.min(25.0, Math.max(0.0, (bt.winRatePercent() / 50.0) * 25.0));
                double adxScore = Math.min(20.0, Math.max(0.0, ((avgAdx - 15.0) / 15.0) * 20.0));
                double atrScore = Math.min(20.0, Math.max(0.0, (avgAtrPct / 2.5) * 20.0));
                double whipsawPenalty = Math.min(15.0, (whipsawRate / 50.0) * 15.0);

                double totalScore =
                        Math.max(
                                0.0,
                                Math.min(
                                        100.0,
                                        pfScore + wrScore + adxScore + atrScore - whipsawPenalty));
                double roundedScore = Math.round(totalScore * 10.0) / 10.0;

                intermediate.add(
                        new StockHealthScore(
                                0,
                                symbol,
                                direction,
                                roundedScore,
                                bt.totalTrades(),
                                bt.winRatePercent(),
                                bt.profitFactor(),
                                bt.netPnL(),
                                Math.round(avgAdx * 10.0) / 10.0,
                                Math.round(avgAtrPct * 100.0) / 100.0,
                                Math.round(whipsawRate * 10.0) / 10.0,
                                false));
            } catch (Exception e) {
                log.warn(
                        "[BASKET-SCORING] Failed to evaluate symbol {}: {}",
                        symbol,
                        e.getMessage());
            }
        }

        // Sort descending by composite score
        intermediate.sort(Comparator.comparingDouble(StockHealthScore::compositeScore).reversed());

        List<StockHealthScore> finalRanked = new ArrayList<>();
        for (int i = 0; i < intermediate.size(); i++) {
            StockHealthScore s = intermediate.get(i);
            boolean isTop10 = (i < 10);
            finalRanked.add(
                    new StockHealthScore(
                            i + 1,
                            s.symbol(),
                            s.direction(),
                            s.compositeScore(),
                            s.totalTrades(),
                            s.winRate(),
                            s.profitFactor(),
                            s.netPnl(),
                            s.avgAdx(),
                            s.avgAtrPct(),
                            s.whipsawRate(),
                            isTop10));
        }

        return finalRanked;
    }

    /** Dispatches a Telegram notification report with the top 10 recommended symbols. */
    public boolean sendRebalanceTelegramReport(List<StockHealthScore> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔄 *[MONTHLY F&O BASKET REBALANCE REPORT]* 🔄\n\n");
        sb.append("📊 *Strategy:* Triple SuperTrend Option Buying (False Breakout Suite)\n");
        sb.append("🕒 *Generated:* ").append(TIME_FMT.format(Instant.now())).append(" IST\n\n");
        sb.append("🏆 *Recommended Top 10 Curated Basket with Directions:*\n");

        List<String> top10Symbols = new ArrayList<>();
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            StockHealthScore s = ranked.get(i);
            top10Symbols.add(s.symbol());
            String pnlSign = s.netPnl().signum() >= 0 ? "+" : "";
            sb.append(
                    String.format(
                            "%d. *%s* ➔ %s (Score: `%.1f`)\n   • WR: %.1f%% | PF: %.2f | P&L: *%s₹%s* | ADX: %.1f\n",
                            s.rank(),
                            s.symbol(),
                            s.direction(),
                            s.compositeScore(),
                            s.winRate(),
                            s.profitFactor(),
                            pnlSign,
                            s.netPnl().setScale(0, RoundingMode.HALF_UP),
                            s.avgAdx()));
        }

        sb.append("\n💡 *Curated Basket Symbols:* `")
                .append(String.join("`, `", top10Symbols))
                .append("`\n\n");

        sb.append("🛡️ *Selection Criteria:*\n");
        sb.append("• 35% Option Buying Profit Factor & P&L\n");
        sb.append("• 25% Win Rate with False Breakout Suite\n");
        sb.append("• 20% ADX Directional Trend Velocity\n");
        sb.append("• 20% Normalized ATR% Expansion\n");
        sb.append("• Penalty for Quick Whipsaw Reversals\n\n");
        sb.append("🤖 *Bot Auto-Tracking:* Active on 1st of every month @ 09:00 IST");

        telegramService.sendAsync(sb.toString());
        return true;
    }
}

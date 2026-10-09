package com.tradingbot.strategy.commodity.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Standardized performance metrics for commodity VWAP strategy runs, computed net of transaction
 * costs and persistable as JSON so backtest results are comparable across runs.
 *
 * @param runLabel descriptive label for the run (e.g. "1-month SILVERM+CRUDEOILM production rules")
 * @param generatedAt when the metrics were computed
 * @param totalTrades number of closed trades evaluated
 * @param winningTrades trades with positive net PnL
 * @param winRatePct winning trades as a percentage
 * @param grossProfit sum of positive net PnL
 * @param grossLoss absolute sum of negative net PnL
 * @param profitFactor grossProfit / grossLoss (grossProfit when no losses)
 * @param grossPnl total PnL before costs
 * @param totalCosts total round-trip costs across trades
 * @param netPnl grossPnl minus totalCosts
 * @param maxDrawdownInr largest peak-to-trough drawdown of the cumulative net PnL curve
 * @param returnOnMaxDd netPnl / maxDrawdownInr
 */
public record CommodityBacktestMetrics(
        String runLabel,
        Instant generatedAt,
        int totalTrades,
        int winningTrades,
        double winRatePct,
        double grossProfit,
        double grossLoss,
        double profitFactor,
        double grossPnl,
        double totalCosts,
        double netPnl,
        double maxDrawdownInr,
        double returnOnMaxDd) {

    /**
     * Computes metrics over closed trades. Trades are internally sorted by exit time (falling back
     * to entry time) so the drawdown curve is chronological.
     */
    public static CommodityBacktestMetrics compute(
            String runLabel, List<CommodityTradePosition> trades, CommodityCostModel costModel) {
        List<CommodityTradePosition> closed =
                trades == null
                        ? List.of()
                        : trades.stream()
                                .filter(t -> t != null && t.isClosed())
                                .sorted(
                                        Comparator.comparing(
                                                        CommodityTradePosition::exitTime,
                                                        Comparator.nullsLast(
                                                                Comparator.naturalOrder()))
                                                .thenComparing(
                                                        CommodityTradePosition::entryTime,
                                                        Comparator.nullsLast(
                                                                Comparator.naturalOrder())))
                                .toList();

        int total = closed.size();
        int wins = 0;
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        double grossPnl = 0.0;
        double totalCosts = 0.0;
        double cumulative = 0.0;
        double peak = 0.0;
        double maxDd = 0.0;

        for (CommodityTradePosition trade : closed) {
            double cost = costModel != null ? costModel.roundTripCost(trade).doubleValue() : 0.0;
            double net = round2(trade.pnl().doubleValue() - cost);
            totalCosts += cost;
            grossPnl += trade.pnl().doubleValue();

            if (net > 0) {
                wins++;
                grossProfit += net;
            } else {
                grossLoss += -net;
            }

            cumulative += net;
            peak = Math.max(peak, cumulative);
            maxDd = Math.max(maxDd, peak - cumulative);
        }

        double netPnl = round2(grossPnl - totalCosts);
        double profitFactor = grossLoss > 0.0 ? round2(grossProfit / grossLoss) : grossProfit;
        double winRate = total > 0 ? round2(100.0 * wins / total) : 0.0;
        double returnOnDd = maxDd > 0.0 ? round2(netPnl / maxDd) : 0.0;

        return new CommodityBacktestMetrics(
                runLabel,
                Instant.now(),
                total,
                wins,
                winRate,
                round2(grossProfit),
                round2(grossLoss),
                profitFactor,
                round2(grossPnl),
                round2(totalCosts),
                netPnl,
                round2(maxDd),
                returnOnDd);
    }

    /** Writes the metrics record as pretty-printed JSON, creating the directory if needed. */
    public void writeJson(Path directory, String fileName) throws IOException {
        Files.createDirectories(directory);
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(directory.resolve(fileName).toFile(), this);
    }

    /** Human-readable single-line summary (also used in test report output). */
    public String summaryLine() {
        return String.format(
                "Trades=%d | WinRate=%.1f%% | PF=%.2f | Gross=Rs%,.2f | Costs=Rs%,.2f | Net=Rs%,.2f | MaxDD=Rs%,.2f | Net/DD=%.2fx",
                totalTrades,
                winRatePct,
                profitFactor,
                grossPnl,
                totalCosts,
                netPnl,
                maxDrawdownInr,
                returnOnMaxDd);
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

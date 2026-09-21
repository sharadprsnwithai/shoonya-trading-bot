package com.tradingbot.strategy.rsihighway.service;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates macro market breadth and leadership strength across the Nifty 500 universe and enforces
 * the market regime gate before permitting new long swing entries.
 */
@Service
public class RsiHighwayMarketBreadthService {

    private static final Logger log = LoggerFactory.getLogger(RsiHighwayMarketBreadthService.class);
    private static final int ROLLING_52W_BARS = 250;
    private static final double NEAR_HIGH_THRESHOLD = 0.98; // Within 2% of 52-week high

    /**
     * Evaluates market breadth across all stocks and benchmark index candles.
     *
     * @param universeDailyCandles Map of symbol -> chronological daily candles
     * @param indexDailyCandles Chronological daily candles for broader index (e.g. NIFTY MIDCAP 150
     *     / SMALLCAP 250)
     * @param minLeadersThreshold Minimum number of stocks near 52W high required (e.g. 5)
     * @param maxIndexDrawdownPct Maximum allowed drawdown from index 52W high (e.g. 0.20 for 20%)
     * @return MarketBreadthSnapshot
     */
    public MarketBreadthSnapshot evaluateBreadth(
            Map<String, List<Candle>> universeDailyCandles,
            List<Candle> indexDailyCandles,
            int minLeadersThreshold,
            double maxIndexDrawdownPct) {

        int totalScanned = 0;
        List<String> leaders = new ArrayList<>();

        if (universeDailyCandles != null) {
            for (Map.Entry<String, List<Candle>> entry : universeDailyCandles.entrySet()) {
                String sym = entry.getKey();
                List<Candle> candles = entry.getValue();
                if (candles == null || candles.isEmpty()) continue;

                totalScanned++;
                if (isNear52WeekHigh(candles)) {
                    leaders.add(sym);
                }
            }
        }

        // Evaluate index drawdown over trailing 52-week window (250 trading bars)
        double indexDrawdown = 0.0;
        if (indexDailyCandles != null && !indexDailyCandles.isEmpty()) {
            int indexSize = indexDailyCandles.size();
            int indexWindow = Math.min(indexSize, ROLLING_52W_BARS);
            double indexHigh = 0.0;
            for (int i = indexSize - indexWindow; i < indexSize; i++) {
                indexHigh = Math.max(indexHigh, indexDailyCandles.get(i).high().doubleValue());
            }
            double latestIndexClose = indexDailyCandles.get(indexSize - 1).close().doubleValue();
            if (indexHigh > 0) {
                indexDrawdown = (indexHigh - latestIndexClose) / indexHigh;
            }
        }

        boolean open = true;
        StringBuilder reason = new StringBuilder();

        if (indexDrawdown > maxIndexDrawdownPct) {
            open = false;
            reason.append(
                    String.format(
                            "Index Drawdown (%.1f%%) exceeds max limit (%.1f%%). ",
                            indexDrawdown * 100, maxIndexDrawdownPct * 100));
        }

        if (leaders.size() < minLeadersThreshold) {
            open = false;
            reason.append(
                    String.format(
                            "Insufficient 52-Week High Leaders (%d < %d required). ",
                            leaders.size(), minLeadersThreshold));
        }

        if (open) {
            reason.append(
                    String.format(
                            "Healthy Momentum Regime: %d leaders near 52-week highs, Index Drawdown %.1f%%.",
                            leaders.size(), indexDrawdown * 100));
        }

        log.info(
                "[MARKET-BREADTH] Evaluated {} stocks -> {} leaders. Highway Open: {}. Reason: {}",
                totalScanned,
                leaders.size(),
                open,
                reason);

        return new MarketBreadthSnapshot(
                open,
                totalScanned,
                leaders.size(),
                Collections.unmodifiableList(leaders),
                indexDrawdown,
                reason.toString().trim(),
                Instant.now());
    }

    private boolean isNear52WeekHigh(List<Candle> candles) {
        int size = candles.size();
        int window = Math.min(size, ROLLING_52W_BARS);
        double maxHigh = 0.0;

        for (int i = size - window; i < size; i++) {
            maxHigh = Math.max(maxHigh, candles.get(i).high().doubleValue());
        }

        if (maxHigh <= 0) return false;
        double currentClose = candles.get(size - 1).close().doubleValue();
        return currentClose >= (NEAR_HIGH_THRESHOLD * maxHigh);
    }
}

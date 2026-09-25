package com.tradingbot.service;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes the 09:25 IST Market Sentiment evaluation, Sectoral Index ranking, and candidate stock
 * filtering for the Lowest Volume Reversal & Continuation Strategy.
 */
@Service
public class LowestVolumeReversalScanner {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeReversalScanner.class);

    public record SectorRankResult(String sectorName, double pctChange, int stockCount) {}

    /**
     * Evaluates Nifty 50 constituent performance at 09:25 IST. Declines > Advances -> SHORT
     * (Bearish) Advances > Declines -> LONG (Bullish)
     */
    public LowestVolumeDirection evaluateMarketSentiment(List<StockQuoteSnapshot> nifty50Quotes) {
        if (nifty50Quotes == null || nifty50Quotes.isEmpty()) {
            log.warn("[LVR-SCANNER] No NIFTY 50 quotes provided for market sentiment evaluation.");
            return LowestVolumeDirection.NONE;
        }

        int advances = 0;
        int declines = 0;

        for (StockQuoteSnapshot q : nifty50Quotes) {
            if (q.pctChange() > 0.0) {
                advances++;
            } else if (q.pctChange() < 0.0) {
                declines++;
            }
        }

        log.info(
                "[LVR-SCANNER] NIFTY 50 Market Sentiment: Advances={}, Declines={}, Total={}",
                advances,
                declines,
                nifty50Quotes.size());

        if (declines > advances) {
            return LowestVolumeDirection.SHORT;
        } else if (advances > declines) {
            return LowestVolumeDirection.LONG;
        } else {
            return LowestVolumeDirection.NONE;
        }
    }

    /**
     * Ranks the 11 NSE Sectoral groups based on average % change of constituents. Bearish (SHORT)
     * -> Top Loser Sector first (ascending pctChange). Bullish (LONG) -> Top Gainer Sector first
     * (descending pctChange).
     */
    public List<SectorRankResult> rankSectors(
            Map<String, List<StockQuoteSnapshot>> sectorQuotes, LowestVolumeDirection sentiment) {
        if (sectorQuotes == null || sectorQuotes.isEmpty()) {
            return Collections.emptyList();
        }

        List<SectorRankResult> results = new ArrayList<>();

        for (Map.Entry<String, List<StockQuoteSnapshot>> entry : sectorQuotes.entrySet()) {
            String sector = entry.getKey();
            List<StockQuoteSnapshot> quotes = entry.getValue();

            if (quotes == null || quotes.isEmpty()) {
                continue;
            }

            double avgPctChange =
                    quotes.stream()
                            .mapToDouble(StockQuoteSnapshot::pctChange)
                            .average()
                            .orElse(0.0);

            results.add(new SectorRankResult(sector, avgPctChange, quotes.size()));
        }

        if (sentiment == LowestVolumeDirection.SHORT) {
            results.sort(Comparator.comparingDouble(SectorRankResult::pctChange));
        } else {
            results.sort(Comparator.comparingDouble(SectorRankResult::pctChange).reversed());
        }

        return results;
    }

    /**
     * Filters candidate stocks within the selected sector: 1. For LONG: Strictly positive % change
     * (0.0% < pctChange <= 5.0%), excluding circuit locked/exhausted stocks. 2. For SHORT: Strictly
     * negative % change (-5.0% <= pctChange < 0.0%), excluding circuit locked/exhausted stocks. 3.
     * Selects top 2-3 stocks in the direction of the trend (or all if <= 3).
     */
    public List<String> filterCandidateStocks(
            List<StockQuoteSnapshot> sectorStockQuotes, LowestVolumeDirection sentiment) {
        if (sectorStockQuotes == null
                || sectorStockQuotes.isEmpty()
                || sentiment == LowestVolumeDirection.NONE) {
            return Collections.emptyList();
        }

        List<StockQuoteSnapshot> eligible;
        if (sentiment == LowestVolumeDirection.LONG) {
            eligible =
                    new ArrayList<>(
                            sectorStockQuotes.stream()
                                    .filter(q -> q.pctChange() > 0.0) // Must be positive/green for
                                    // LONG
                                    .filter(q -> q.pctChange() <= 5.0) // Not exhausted (> 5%)
                                    .toList());
            // Most positive pctChange first
            eligible.sort(Comparator.comparingDouble(StockQuoteSnapshot::pctChange).reversed());
        } else if (sentiment == LowestVolumeDirection.SHORT) {
            eligible =
                    new ArrayList<>(
                            sectorStockQuotes.stream()
                                    .filter(
                                            q ->
                                                    q.pctChange()
                                                            < 0.0) // Must be negative/red for SHORT
                                    .filter(q -> q.pctChange() >= -5.0) // Not exhausted (< -5%)
                                    .toList());
            // Most negative pctChange first
            eligible.sort(Comparator.comparingDouble(StockQuoteSnapshot::pctChange));
        } else {
            return Collections.emptyList();
        }

        if (eligible.size() <= 3) {
            return eligible.stream().map(StockQuoteSnapshot::symbol).toList();
        }

        return eligible.stream().limit(3).map(StockQuoteSnapshot::symbol).toList();
    }
}

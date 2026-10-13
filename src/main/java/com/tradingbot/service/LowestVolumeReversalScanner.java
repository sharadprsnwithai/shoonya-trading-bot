package com.tradingbot.service;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Selects NSE OI-spurt candidates for the LVR watchlist (09:25 IST phase-2 selection).
     *
     * <p>Input must already be ranked by % OI change descending (NSE's own ordering) and free of
     * index underlyings. A candidate is only eligible when a live quote exists for it (needed for
     * the price-alignment band and for trading). Alignment: LONG requires {@code 0 < pctChange <=
     * 5}, SHORT requires {@code -5 <= pctChange < 0} (NONE/null sentiment accepts everything). When
     * fewer than {@code topN} symbols fall inside the band, the remaining slots are filled from the
     * top-ranked unfiltered candidates so the OI leg always yields {@code topN} picks.
     *
     * @param rankedItems NSE OI spurts ranked by avgInOi descending
     * @param quotes live quotes keyed by symbol (candidates without a quote are skipped)
     * @param sentiment market sentiment driving the alignment band
     * @param topN number of candidates to return
     * @param excludeSymbols symbols to skip (already active/exhausted); may be null
     * @return selected symbols in rank order, size {@code <= topN}
     */
    public List<String> selectOiSpurtCandidates(
            List<com.tradingbot.model.NseOiSpurtItem> rankedItems,
            Map<String, StockQuoteSnapshot> quotes,
            LowestVolumeDirection sentiment,
            int topN,
            Set<String> excludeSymbols) {
        if (rankedItems == null || rankedItems.isEmpty() || topN <= 0) {
            return Collections.emptyList();
        }
        if (quotes == null || quotes.isEmpty()) {
            return Collections.emptyList();
        }

        // Eligible: unique, non-excluded symbols that carry a live quote.
        List<com.tradingbot.model.NseOiSpurtItem> eligible = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (com.tradingbot.model.NseOiSpurtItem item : rankedItems) {
            if (item == null || item.symbol() == null || item.symbol().isBlank()) {
                continue;
            }
            String sym = item.symbol();
            if (!seen.add(sym)) {
                continue;
            }
            if (excludeSymbols != null && excludeSymbols.contains(sym)) {
                continue;
            }
            if (!quotes.containsKey(sym)) {
                continue;
            }
            eligible.add(item);
        }
        if (eligible.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> aligned = new ArrayList<>();
        for (com.tradingbot.model.NseOiSpurtItem item : eligible) {
            double pct = quotes.get(item.symbol()).pctChange();
            if (isWithinSentimentBand(pct, sentiment)) {
                aligned.add(item.symbol());
            }
            if (aligned.size() >= topN) {
                break;
            }
        }

        List<String> selected =
                new ArrayList<>(aligned.size() >= topN ? aligned.subList(0, topN) : aligned);
        if (selected.size() < topN) {
            // Decision 4: relax the band — fill remaining slots from the ranked list.
            for (com.tradingbot.model.NseOiSpurtItem item : eligible) {
                if (selected.size() >= topN) {
                    break;
                }
                if (!selected.contains(item.symbol())) {
                    selected.add(item.symbol());
                }
            }
        }

        log.info(
                "[LVR-SCANNER] NSE OI spurt selection (sentiment={}, topN={}): {} aligned of {}"
                        + " eligible ({} ranked) -> {}",
                sentiment,
                topN,
                aligned.size(),
                eligible.size(),
                rankedItems.size(),
                selected);
        return selected;
    }

    /**
     * Sentiment price-alignment band shared with {@link #filterCandidateStocks}: LONG is a modest
     * gainer, SHORT a modest loser; NONE/null sentiment imposes no band.
     */
    private boolean isWithinSentimentBand(double pctChange, LowestVolumeDirection sentiment) {
        if (sentiment == null || sentiment == LowestVolumeDirection.NONE) {
            return true;
        }
        if (sentiment == LowestVolumeDirection.LONG) {
            return pctChange > 0.0 && pctChange <= 5.0;
        }
        return pctChange < 0.0 && pctChange >= -5.0;
    }

    /**
     * Evaluates Nifty 50 constituent performance at 09:25 IST. Uses default minimum breadth
     * requirement of 56% (e.g. at least 28 of 50 stocks aligned).
     */
    public LowestVolumeDirection evaluateMarketSentiment(List<StockQuoteSnapshot> nifty50Quotes) {
        return evaluateMarketSentiment(nifty50Quotes, 56.0);
    }

    /**
     * Evaluates Nifty 50 constituent performance at 09:25 IST with custom breadth percentage.
     * Declines >= minRequired -> SHORT (Bearish) Advances >= minRequired -> LONG (Bullish)
     * Otherwise -> NONE (Neutral / Mixed)
     */
    public LowestVolumeDirection evaluateMarketSentiment(
            List<StockQuoteSnapshot> nifty50Quotes, double minBreadthPct) {
        if (nifty50Quotes == null || nifty50Quotes.isEmpty()) {
            log.warn("[LVR-SCANNER] No NIFTY 50 quotes provided for market sentiment evaluation.");
            return LowestVolumeDirection.NONE;
        }

        int advances = 0;
        int declines = 0;
        int total = nifty50Quotes.size();

        for (StockQuoteSnapshot q : nifty50Quotes) {
            if (q.pctChange() > 0.0) {
                advances++;
            } else if (q.pctChange() < 0.0) {
                declines++;
            }
        }

        double advPct = (double) advances / total * 100.0;
        double decPct = (double) declines / total * 100.0;

        log.info(
                "[LVR-SCANNER] NIFTY 50 Market Sentiment: Advances={} ({}%), Declines={} ({}%), Total={}",
                advances,
                String.format(java.util.Locale.US, "%.1f", advPct),
                declines,
                String.format(java.util.Locale.US, "%.1f", decPct),
                total);

        int minRequired = (int) Math.ceil(total * (minBreadthPct / 100.0));

        if (advances >= minRequired && advances > declines) {
            return LowestVolumeDirection.LONG;
        } else if (declines >= minRequired && declines > advances) {
            return LowestVolumeDirection.SHORT;
        } else {
            log.info(
                    "[LVR-SCANNER] Market sentiment is NEUTRAL/MIXED (Advances={}, Declines={}, required={}).",
                    advances,
                    declines,
                    minRequired);
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

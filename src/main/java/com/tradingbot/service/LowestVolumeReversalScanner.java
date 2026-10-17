package com.tradingbot.service;

import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.util.BotLocale;
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

    /**
     * Default per-sector stock cap for {@link #filterCandidateStocks(List, LowestVolumeDirection)}.
     */
    private static final int DEFAULT_STOCKS_PER_SECTOR = 3;

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
                String.format(BotLocale.INSTANCE, "%.1f", advPct),
                declines,
                String.format(BotLocale.INSTANCE, "%.1f", decPct),
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

        return filterCandidateStocks(sectorStockQuotes, sentiment, DEFAULT_STOCKS_PER_SECTOR);
    }

    /**
     * Same filter as {@link #filterCandidateStocks(List, LowestVolumeDirection)} with an explicit
     * per-sector stock cap. Selection is direction-scoped rather than index-scoped: the caller
     * passes {@code LONG} for a bullish sector and {@code SHORT} for a bearish one, so both sides
     * of the market can contribute candidates in the same session.
     *
     * <p>Rules, unchanged from the original: LONG requires {@code 0 < pctChange <= 5.0} (green but
     * not exhausted), SHORT requires {@code -5.0 <= pctChange < 0}; each keeps the most extreme
     * movers first.
     *
     * @param sectorStockQuotes quotes of one sectoral index's constituents
     * @param direction side this sector contributes to; {@code NONE}/null yields nothing
     * @param maxStocks maximum symbols to return
     * @return selected symbols in rank order, size {@code <= maxStocks}
     */
    public List<String> filterCandidateStocks(
            List<StockQuoteSnapshot> sectorStockQuotes,
            LowestVolumeDirection direction,
            int maxStocks) {
        if (sectorStockQuotes == null
                || sectorStockQuotes.isEmpty()
                || direction == null
                || direction == LowestVolumeDirection.NONE
                || maxStocks <= 0) {
            return Collections.emptyList();
        }

        List<StockQuoteSnapshot> eligible;
        if (direction == LowestVolumeDirection.LONG) {
            eligible =
                    sectorStockQuotes.stream()
                            .filter(q -> q.pctChange() > 0.0) // Must be positive/green for LONG
                            .filter(q -> q.pctChange() <= 5.0) // Not exhausted (> 5%)
                            // Most positive pctChange first
                            .sorted(
                                    Comparator.comparingDouble(StockQuoteSnapshot::pctChange)
                                            .reversed())
                            .toList();
        } else {
            eligible =
                    sectorStockQuotes.stream()
                            .filter(q -> q.pctChange() < 0.0) // Must be negative/red for SHORT
                            .filter(q -> q.pctChange() >= -5.0) // Not exhausted (< -5%)
                            // Most negative pctChange first
                            .sorted(Comparator.comparingDouble(StockQuoteSnapshot::pctChange))
                            .toList();
        }

        if (eligible.size() <= maxStocks) {
            return eligible.stream().map(StockQuoteSnapshot::symbol).toList();
        }

        return eligible.stream().limit(maxStocks).map(StockQuoteSnapshot::symbol).toList();
    }

    /**
     * Ranks sectoral groups purely by average % change, strongest first, with no directional input.
     * The caller takes the head for the bullish side and the (sign-filtered, re-sorted) tail for
     * the bearish side, so both sides are available in one session.
     *
     * @param sectorQuotes quotes grouped by sectoral index; may be null or empty
     * @return sectors sorted by average % change descending
     */
    public List<SectorRankResult> rankSectorsByStrength(
            Map<String, List<StockQuoteSnapshot>> sectorQuotes) {
        if (sectorQuotes == null || sectorQuotes.isEmpty()) {
            return Collections.emptyList();
        }

        List<SectorRankResult> results = new ArrayList<>();
        for (Map.Entry<String, List<StockQuoteSnapshot>> entry : sectorQuotes.entrySet()) {
            List<StockQuoteSnapshot> quotes = entry.getValue();
            if (quotes == null || quotes.isEmpty()) {
                continue;
            }
            double avgPctChange =
                    quotes.stream()
                            .mapToDouble(StockQuoteSnapshot::pctChange)
                            .average()
                            .orElse(0.0);
            results.add(new SectorRankResult(entry.getKey(), avgPctChange, quotes.size()));
        }

        results.sort(Comparator.comparingDouble(SectorRankResult::pctChange).reversed());
        return results;
    }

    /**
     * Detects the 3-day contracting-triangle ("squeeze") pattern over the last three <b>completed
     * </b> daily bars.
     *
     * <p>The bars are passed in ascending chronological order, so the last element is yesterday and
     * today's in-progress bar must already have been stripped by the caller. Naming the three bars
     * {@code d3, d2, d1} (oldest to newest), the pattern holds when highs strictly descend and lows
     * strictly rise:
     *
     * <pre>
     *   H(d3) &gt; H(d2) &gt; H(d1)   lower highs
     *   L(d3) &lt; L(d2) &lt; L(d1)   higher lows
     * </pre>
     *
     * <p>Both chains are required — one-sided contraction is a trend, not a triangle. Inequalities
     * are strict so a flat or tied bar disqualifies the pattern. Fails closed: fewer than three
     * usable bars, or any null high/low, is not a triangle rather than an error.
     *
     * @param pastDailyAscending completed daily bars, oldest first; may be null
     * @return true when the last three bars form the pattern
     */
    public static boolean isTrianglePattern(List<Candle> pastDailyAscending) {
        List<Candle> last3 = lastThreeCompleted(pastDailyAscending);
        if (last3 == null) {
            return false;
        }
        Candle d3 = last3.get(0);
        Candle d2 = last3.get(1);
        Candle d1 = last3.get(2);
        if (!hasHighAndLow(d3) || !hasHighAndLow(d2) || !hasHighAndLow(d1)) {
            return false;
        }

        boolean lowerHighs =
                d3.high().compareTo(d2.high()) > 0 && d2.high().compareTo(d1.high()) > 0;
        boolean higherLows = d3.low().compareTo(d2.low()) < 0 && d2.low().compareTo(d1.low()) < 0;

        return lowerHighs && higherLows;
    }

    /**
     * Measures how far a candidate's range has contracted: the newest bar's range divided by the
     * oldest bar's range across the same three completed bars used by {@link
     * #isTrianglePattern(List)}. Used to rank triangle picks — a smaller ratio means a tighter
     * squeeze and therefore a more informative pattern.
     *
     * @param pastDailyAscending completed daily bars, oldest first; may be null
     * @return squeeze ratio in {@code (0, ~1+]}, or {@code NaN} when it cannot be computed
     */
    public static double squeezeRatio(List<Candle> pastDailyAscending) {
        List<Candle> last3 = lastThreeCompleted(pastDailyAscending);
        if (last3 == null) {
            return Double.NaN;
        }
        Candle d3 = last3.get(0);
        Candle d1 = last3.get(2);
        if (!hasHighAndLow(d3) || !hasHighAndLow(d1)) {
            return Double.NaN;
        }

        double outerRange = d3.high().subtract(d3.low()).doubleValue();
        if (outerRange <= 0.0) {
            return Double.NaN;
        }
        double innerRange = d1.high().subtract(d1.low()).doubleValue();
        return innerRange / outerRange;
    }

    /**
     * @param pastDailyAscending completed daily bars, oldest first
     * @return the final three bars, or {@code null} when fewer than three are available
     */
    private static List<Candle> lastThreeCompleted(List<Candle> pastDailyAscending) {
        if (pastDailyAscending == null || pastDailyAscending.size() < 3) {
            return null;
        }
        int n = pastDailyAscending.size();
        return List.of(
                pastDailyAscending.get(n - 3),
                pastDailyAscending.get(n - 2),
                pastDailyAscending.get(n - 1));
    }

    /**
     * @param candle bar to validate; may be null
     * @return true when the bar carries both a high and a low
     */
    private static boolean hasHighAndLow(Candle candle) {
        return candle != null && candle.high() != null && candle.low() != null;
    }
}

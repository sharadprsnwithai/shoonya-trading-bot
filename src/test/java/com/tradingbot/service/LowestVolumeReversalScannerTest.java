package com.tradingbot.service;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumeReversalScannerTest {

    private final LowestVolumeReversalScanner scanner = new LowestVolumeReversalScanner();

    @Test
    @DisplayName("Should detect Bearish sentiment when Declines > Advances")
    void testBearishSentiment() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        new StockQuoteSnapshot("TCS", 3900.0, 4000.0, 3950.0, -2.5),
                        new StockQuoteSnapshot("INFY", 1800.0, 1850.0, 1820.0, -2.7),
                        new StockQuoteSnapshot("RELIANCE", 2900.0, 2920.0, 2910.0, -0.68),
                        new StockQuoteSnapshot("HDFCBANK", 1600.0, 1590.0, 1595.0, 0.63));
        LowestVolumeDirection dir = scanner.evaluateMarketSentiment(quotes);
        assertEquals(LowestVolumeDirection.SHORT, dir);
    }

    @Test
    @DisplayName("Should detect Bullish sentiment when Advances > Declines")
    void testBullishSentiment() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        new StockQuoteSnapshot("TCS", 4100.0, 4000.0, 4050.0, 2.5),
                        new StockQuoteSnapshot("INFY", 1900.0, 1850.0, 1860.0, 2.7),
                        new StockQuoteSnapshot("RELIANCE", 2950.0, 2920.0, 2930.0, 1.0),
                        new StockQuoteSnapshot("HDFCBANK", 1550.0, 1590.0, 1580.0, -2.5));
        LowestVolumeDirection dir = scanner.evaluateMarketSentiment(quotes);
        assertEquals(LowestVolumeDirection.LONG, dir);
    }

    @Test
    @DisplayName("Should return NONE when market breadth is mixed / neutral")
    void testNeutralMixedSentiment() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        new StockQuoteSnapshot("TCS", 4100.0, 4000.0, 4050.0, 2.5),
                        new StockQuoteSnapshot("INFY", 1900.0, 1850.0, 1860.0, 2.7),
                        new StockQuoteSnapshot("RELIANCE", 2900.0, 2920.0, 2910.0, -1.0),
                        new StockQuoteSnapshot("HDFCBANK", 1550.0, 1590.0, 1580.0, -2.5));
        // 2 advances, 2 declines = 50% breadth (below 56% threshold)
        LowestVolumeDirection dir = scanner.evaluateMarketSentiment(quotes, 56.0);
        assertEquals(LowestVolumeDirection.NONE, dir);
    }

    @Test
    @DisplayName("Should rank sectors and select Top Loser sector during Bearish sentiment")
    void testRankSectorsBearish() {
        Map<String, List<StockQuoteSnapshot>> sectorData =
                Map.of(
                        "NIFTY MEDIA",
                                List.of(
                                        new StockQuoteSnapshot(
                                                "PVRINOX", 1500.0, 1600.0, 1550.0, -4.75)),
                        "NIFTY IT",
                                List.of(
                                        new StockQuoteSnapshot(
                                                "INFY", 1800.0, 1850.0, 1820.0, -2.7)),
                        "NIFTY PHARMA",
                                List.of(
                                        new StockQuoteSnapshot(
                                                "SUNPHARMA", 1700.0, 1680.0, 1690.0, 1.2)));
        var ranked = scanner.rankSectors(sectorData, LowestVolumeDirection.SHORT);
        assertFalse(ranked.isEmpty());
        assertEquals("NIFTY MEDIA", ranked.get(0).sectorName());
        assertEquals(-4.75, ranked.get(0).pctChange(), 0.001);
    }

    @Test
    @DisplayName("Should rank sectors and select Top Gainer sector during Bullish sentiment")
    void testRankSectorsBullish() {
        Map<String, List<StockQuoteSnapshot>> sectorData =
                Map.of(
                        "NIFTY MEDIA",
                                List.of(
                                        new StockQuoteSnapshot(
                                                "PVRINOX", 1500.0, 1600.0, 1550.0, -4.75)),
                        "NIFTY IT",
                                List.of(
                                        new StockQuoteSnapshot(
                                                "INFY", 1800.0, 1850.0, 1820.0, -2.7)),
                        "NIFTY PHARMA",
                                List.of(
                                        new StockQuoteSnapshot(
                                                "SUNPHARMA", 1700.0, 1680.0, 1690.0, 1.2)));
        var ranked = scanner.rankSectors(sectorData, LowestVolumeDirection.LONG);
        assertFalse(ranked.isEmpty());
        assertEquals("NIFTY PHARMA", ranked.get(0).sectorName());
        assertEquals(1.2, ranked.get(0).pctChange(), 0.001);
    }

    @Test
    @DisplayName(
            "Should filter out overextended stocks (> 5%), circuit locked stocks, and wrong-direction stocks")
    void testFilterCandidateStocks() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        new StockQuoteSnapshot("PVRINOX", 1550.0, 1600.0, 1580.0, -3.12),
                        new StockQuoteSnapshot("SUNTV", 780.0, 800.0, 790.0, -2.50),
                        new StockQuoteSnapshot("GREEN_STOCK", 102.0, 100.0, 101.0, 2.0),
                        new StockQuoteSnapshot("OVEREXTENDED", 90.0, 100.0, 95.0, -10.0),
                        new StockQuoteSnapshot("TOO_FAR", 94.0, 100.0, 96.0, -6.0));
        List<String> candidates =
                scanner.filterCandidateStocks(quotes, LowestVolumeDirection.SHORT);
        assertEquals(2, candidates.size());
        assertTrue(candidates.contains("PVRINOX"));
        assertTrue(candidates.contains("SUNTV"));
        assertFalse(
                candidates.contains("GREEN_STOCK")); // Positive stock must not be chosen for SHORT
        assertFalse(candidates.contains("OVEREXTENDED"));
        assertFalse(candidates.contains("TOO_FAR"));
    }

    @Test
    @DisplayName(
            "LONG sentiment must only select positive stocks and exclude negative/overextended stocks")
    void testFilterCandidateStocksBullish() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        new StockQuoteSnapshot("SUNPHARMA", 1900.0, 1850.0, 1860.0, 2.70),
                        new StockQuoteSnapshot("CIPLA", 1530.0, 1500.0, 1510.0, 2.00),
                        new StockQuoteSnapshot("RED_STOCK", 1480.0, 1500.0, 1490.0, -1.33),
                        new StockQuoteSnapshot("EXHAUSTED_GAIN", 1600.0, 1500.0, 1550.0, 6.67));
        List<String> candidates = scanner.filterCandidateStocks(quotes, LowestVolumeDirection.LONG);
        assertEquals(2, candidates.size());
        assertEquals("SUNPHARMA", candidates.get(0)); // 2.70%
        assertEquals("CIPLA", candidates.get(1)); // 2.00%
        assertFalse(candidates.contains("RED_STOCK"));
        assertFalse(candidates.contains("EXHAUSTED_GAIN"));
    }

    // ------------------------------------------------------------------
    // selectOiSpurtCandidates (09:25 IST NSE OI spurts phase-2 selection)
    // ------------------------------------------------------------------

    /** Builds a ranked NSE OI spurt item for the given symbol. */
    private com.tradingbot.model.NseOiSpurtItem oi(String symbol, double avgInOi) {
        return new com.tradingbot.model.NseOiSpurtItem(symbol, 1000L, 900L, 100L, avgInOi, 100.0);
    }

    /** Builds a quote snapshot whose pctChange is the given value. */
    private StockQuoteSnapshot quote(String symbol, double pctChange) {
        return new StockQuoteSnapshot(
                symbol, 100.0 * (1 + pctChange / 100), 100.0, 100.5, pctChange);
    }

    @Test
    @DisplayName(
            "selectOiSpurtCandidates picks top-N in-band candidates ranked by OI, others excluded")
    void testSelectOiSpurtCandidatesAppliesSentimentBand() {
        var items = List.of(oi("AAA", 48.7), oi("BBB", 25.0), oi("CCC", 20.0), oi("DDD", 15.0));
        var quotes =
                Map.of(
                        "AAA",
                        quote("AAA", 2.0), // in band (0,5]
                        "BBB",
                        quote("BBB", -1.0), // wrong side
                        "CCC",
                        quote("CCC", 7.0), // outside 5% cap
                        "DDD",
                        quote("DDD", 3.0)); // in band

        List<String> selected =
                scanner.selectOiSpurtCandidates(
                        items, quotes, LowestVolumeDirection.LONG, 2, java.util.Set.of());

        assertEquals(List.of("AAA", "DDD"), selected);
    }

    @Test
    @DisplayName("selectOiSpurtCandidates relaxes the band to always fill topN slots")
    void testSelectOiSpurtCandidatesRelaxedFillReachesTopN() {
        var items = List.of(oi("AAA", 48.7), oi("BBB", 25.0), oi("CCC", 20.0), oi("DDD", 15.0));
        var quotes =
                Map.of(
                        "AAA",
                        quote("AAA", 2.0), // only in-band candidate
                        "BBB",
                        quote("BBB", -1.0),
                        "CCC",
                        quote("CCC", 7.0),
                        "DDD",
                        quote("DDD", -3.0));

        List<String> selected =
                scanner.selectOiSpurtCandidates(
                        items, quotes, LowestVolumeDirection.LONG, 3, java.util.Set.of());

        // AAA in-band, then top-ranked unfiltered BBB + CCC (D is last in rank order).
        assertEquals(List.of("AAA", "BBB", "CCC"), selected);
    }

    @Test
    @DisplayName("selectOiSpurtCandidates skips candidates without quotes and excluded symbols")
    void testSelectOiSpurtCandidatesSkipsUnquotedAndExcluded() {
        var items = List.of(oi("AAA", 48.7), oi("BBB", 25.0), oi("CCC", 20.0), oi("DDD", 15.0));
        // BBB has no quote; DDD is excluded (already active/exhausted).
        var quotes =
                Map.of(
                        "AAA",
                        quote("AAA", 2.0),
                        "CCC",
                        quote("CCC", -1.5),
                        "DDD",
                        quote("DDD", -3.0));

        List<String> selected =
                scanner.selectOiSpurtCandidates(
                        items, quotes, LowestVolumeDirection.SHORT, 3, java.util.Set.of("DDD"));

        // CCC is the only SHORT-aligned eligible pick; AAA fills the relaxed slot
        // (BBB has no quote, DDD is excluded).
        assertEquals(List.of("CCC", "AAA"), selected);
    }

    @Test
    @DisplayName("selectOiSpurtCandidates returns empty for null/empty inputs or non-positive topN")
    void testSelectOiSpurtCandidatesEdgeCases() {
        var items = List.of(oi("AAA", 48.7));
        var quotes = Map.of("AAA", quote("AAA", 2.0));

        assertTrue(
                scanner.selectOiSpurtCandidates(null, quotes, LowestVolumeDirection.LONG, 3, null)
                        .isEmpty());
        assertTrue(
                scanner.selectOiSpurtCandidates(
                                List.of(), quotes, LowestVolumeDirection.LONG, 3, null)
                        .isEmpty());
        assertTrue(
                scanner.selectOiSpurtCandidates(items, null, LowestVolumeDirection.LONG, 3, null)
                        .isEmpty());
        assertTrue(
                scanner.selectOiSpurtCandidates(
                                items, Map.of(), LowestVolumeDirection.LONG, 3, null)
                        .isEmpty());
        assertTrue(
                scanner.selectOiSpurtCandidates(items, quotes, LowestVolumeDirection.LONG, 0, null)
                        .isEmpty());
    }

    @Test
    @DisplayName("selectOiSpurtCandidates with NONE sentiment selects pure rank order")
    void testSelectOiSpurtCandidatesNoneSentimentUsesAbsoluteRanking() {
        var items = List.of(oi("AAA", 48.7), oi("BBB", 25.0), oi("CCC", 20.0));
        var quotes =
                Map.of(
                        "AAA",
                        quote("AAA", -2.0),
                        "BBB",
                        quote("BBB", 4.5),
                        "CCC",
                        quote("CCC", 9.0));

        List<String> selected =
                scanner.selectOiSpurtCandidates(
                        items, quotes, LowestVolumeDirection.NONE, 2, java.util.Set.of());

        assertEquals(List.of("AAA", "BBB"), selected);
    }

    /** Builds a completed daily bar; only high/low matter to the triangle scan. */
    private static Candle daily(String symbol, int dayOffset, double high, double low) {
        return Candle.ofDaily(
                symbol,
                Instant.parse("2026-10-01T00:00:00Z").plusSeconds(dayOffset * 86_400L),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                1000L);
    }

    @Test
    @DisplayName("isTrianglePattern: strict lower highs AND higher lows over the last 3 bars")
    void testTrianglePatternDetectsBothSidedContraction() {
        List<Candle> bars =
                List.of(
                        daily("INFY", 0, 110, 90),
                        daily("INFY", 1, 106, 93),
                        daily("INFY", 2, 102, 96));
        assertTrue(LowestVolumeReversalScanner.isTrianglePattern(bars));
    }

    @Test
    @DisplayName("isTrianglePattern: one-sided contraction is a trend, not a triangle")
    void testTrianglePatternRejectsOneSidedContraction() {
        // Lower highs but flat lows — a plain downtrend, which must not be picked up.
        List<Candle> downtrend =
                List.of(
                        daily("INFY", 0, 110, 95),
                        daily("INFY", 1, 106, 95),
                        daily("INFY", 2, 102, 95));
        assertFalse(LowestVolumeReversalScanner.isTrianglePattern(downtrend));

        // Rising highs with flat highs rejected symmetrically: the HIGH chain must descend.
        List<Candle> upOnly =
                List.of(
                        daily("INFY", 0, 100, 90),
                        daily("INFY", 1, 105, 93),
                        daily("INFY", 2, 110, 96));
        assertFalse(LowestVolumeReversalScanner.isTrianglePattern(upOnly));
    }

    @Test
    @DisplayName("isTrianglePattern: a tied bar disqualifies — the inequalities are strict")
    void testTrianglePatternRejectsTiedBar() {
        List<Candle> tiedHighs =
                List.of(
                        daily("INFY", 0, 110, 90),
                        daily("INFY", 1, 110, 93),
                        daily("INFY", 2, 102, 96));
        assertFalse(LowestVolumeReversalScanner.isTrianglePattern(tiedHighs));

        List<Candle> tiedLows =
                List.of(
                        daily("INFY", 0, 110, 90),
                        daily("INFY", 1, 106, 90),
                        daily("INFY", 2, 102, 96));
        assertFalse(LowestVolumeReversalScanner.isTrianglePattern(tiedLows));
    }

    @Test
    @DisplayName("isTrianglePattern: fewer than three completed bars fails closed")
    void testTrianglePatternFailsClosedOnShortHistory() {
        assertFalse(LowestVolumeReversalScanner.isTrianglePattern(null));
        assertFalse(LowestVolumeReversalScanner.isTrianglePattern(List.of()));
        assertFalse(
                LowestVolumeReversalScanner.isTrianglePattern(
                        List.of(daily("INFY", 0, 110, 90), daily("INFY", 1, 106, 93))));
    }

    @Test
    @DisplayName("isTrianglePattern: only the final three bars are examined")
    void testTrianglePatternUsesOnlyLastThreeBars() {
        // An expanding first pair must be ignored — the pattern is defined on D-3, D-2, D-1.
        List<Candle> bars =
                List.of(
                        daily("INFY", 0, 200, 10),
                        daily("INFY", 1, 500, 5),
                        daily("INFY", 2, 110, 90),
                        daily("INFY", 3, 106, 93),
                        daily("INFY", 4, 102, 96));
        assertTrue(LowestVolumeReversalScanner.isTrianglePattern(bars));
    }

    @Test
    @DisplayName("squeezeRatio: newest range over oldest range, NaN when it cannot be measured")
    void testSqueezeRatioMeasuresContraction() {
        List<Candle> bars =
                List.of(
                        daily("INFY", 0, 110, 90), // outer range 20
                        daily("INFY", 1, 106, 93),
                        daily("INFY", 2, 102, 96)); // inner range 6
        assertEquals(0.3, LowestVolumeReversalScanner.squeezeRatio(bars), 1e-9);

        // A tighter squeeze scores lower, which is why triangle picks are ranked by it.
        List<Candle> tighter =
                List.of(
                        daily("INFY", 0, 110, 90),
                        daily("INFY", 1, 106, 93),
                        daily("INFY", 2, 101, 99)); // inner range 2
        assertEquals(0.1, LowestVolumeReversalScanner.squeezeRatio(tighter), 1e-9);

        // A zero-width outer bar makes the ratio undefined rather than infinite.
        List<Candle> degenerate =
                List.of(
                        daily("INFY", 0, 100, 100),
                        daily("INFY", 1, 106, 93),
                        daily("INFY", 2, 101, 99));
        assertEquals(Double.NaN, LowestVolumeReversalScanner.squeezeRatio(degenerate));
        assertEquals(Double.NaN, LowestVolumeReversalScanner.squeezeRatio(List.of()));
    }

    @Test
    @DisplayName("rankSectorsByStrength: strongest first, both sides retained, no direction filter")
    void testRankSectorsByStrengthKeepsBothSides() {
        Map<String, List<StockQuoteSnapshot>> sectorData =
                Map.of(
                        "NIFTY MEDIA", List.of(quote("PVRINOX", -4.75)),
                        "NIFTY IT", List.of(quote("INFY", 3.2)),
                        "NIFTY PHARMA", List.of(quote("SUNPHARMA", 1.2)));

        var ranked = scanner.rankSectorsByStrength(sectorData);

        // Descending by average move, with the loser still present so a bearish side exists.
        assertEquals(3, ranked.size());
        assertEquals("NIFTY IT", ranked.get(0).sectorName());
        assertEquals("NIFTY PHARMA", ranked.get(1).sectorName());
        assertEquals("NIFTY MEDIA", ranked.get(2).sectorName());
        assertTrue(ranked.get(0).pctChange() > 0.0);
        assertTrue(ranked.get(2).pctChange() < 0.0);
    }

    @Test
    @DisplayName("filterCandidateStocks: maxStocks caps the pick and keeps best-first order")
    void testFilterCandidateStocksHonoursMaxStocks() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        quote("A", 4.9),
                        quote("B", 2.0),
                        quote("C", 6.5), // already exhausted (> 5%) — never picked
                        quote("D", -1.0), // not green — never picked on the LONG side
                        quote("E", 3.0));

        assertEquals(
                List.of("A", "E"),
                scanner.filterCandidateStocks(quotes, LowestVolumeDirection.LONG, 2));

        // A side with no eligible stocks yields nothing rather than a forced pick.
        assertTrue(
                scanner.filterCandidateStocks(
                                List.of(quote("D", -1.0), quote("C", 6.5)),
                                LowestVolumeDirection.LONG,
                                3)
                        .isEmpty());
        // NONE is the two-sided/unresolved state and can never be passed a side.
        assertTrue(scanner.filterCandidateStocks(quotes, LowestVolumeDirection.NONE, 3).isEmpty());
    }
}

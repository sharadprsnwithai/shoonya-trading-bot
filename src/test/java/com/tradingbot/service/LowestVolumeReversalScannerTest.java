package com.tradingbot.service;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
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

    @Test
    @DisplayName("Should rank stocks by absolute OI % change descending and exclude indices")
    void testScanOiSpurtsRankingAndFiltering() {
        Map<String, StockQuoteSnapshot> quotes = new java.util.HashMap<>();

        // Add candidate quotes: symbol, ltp, prevClose, open, pctChange, volume, vwap,
        // openInterest, prevDayOpenInterest
        quotes.put(
                "MCDOWELL-N",
                new StockQuoteSnapshot(
                        "MCDOWELL-N",
                        1200.0,
                        1170.0,
                        1175.0,
                        2.5,
                        50000,
                        1195.0,
                        110000,
                        100000)); // +10.0%
        quotes.put(
                "RADICO",
                new StockQuoteSnapshot(
                        "RADICO", 1800.0, 1745.0, 1750.0, 3.1, 40000, 1790.0, 105500,
                        100000)); // +5.5%
        quotes.put(
                "RVNL",
                new StockQuoteSnapshot(
                        "RVNL", 400.0, 406.0, 405.0, -1.5, 80000, 402.0, 104000, 100000)); // +4.0%
        quotes.put(
                "NIFTY 50",
                new StockQuoteSnapshot(
                        "NIFTY 50",
                        25000.0,
                        24875.0,
                        24900.0,
                        0.5,
                        1000000,
                        24980.0,
                        5000000,
                        4000000)); // +25.0% (Index - must be excluded)
        quotes.put(
                "TCS",
                new StockQuoteSnapshot(
                        "TCS", 4200.0, 4195.0, 4200.0, 0.1, 10000, 4205.0, 101000,
                        100000)); // +1.0%

        List<String> topStocks = scanner.scanOiSpurts(quotes, 3);

        assertEquals(3, topStocks.size());
        assertEquals("MCDOWELL-N", topStocks.get(0));
        assertEquals("RADICO", topStocks.get(1));
        assertEquals("RVNL", topStocks.get(2));
        assertFalse(topStocks.contains("NIFTY 50"));
    }

    @Test
    @DisplayName("Should handle null, empty and invalid OI inputs safely")
    void testScanOiSpurtsEdgeCases() {
        assertTrue(scanner.scanOiSpurts(null, 5).isEmpty());
        assertTrue(scanner.scanOiSpurts(Map.of(), 5).isEmpty());

        Map<String, StockQuoteSnapshot> quotes =
                Map.of(
                        "INVALID_OI",
                        new StockQuoteSnapshot(
                                "INVALID_OI", 100.0, 100.0, 100.0, 0.0, 100, 100.0, 0, 0));
        assertTrue(scanner.scanOiSpurts(quotes, 5).isEmpty());
    }
}

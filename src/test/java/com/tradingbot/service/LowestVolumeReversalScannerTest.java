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
    @DisplayName("Should filter out overextended stocks (> 5%) and circuit locked stocks")
    void testFilterCandidateStocks() {
        List<StockQuoteSnapshot> quotes =
                List.of(
                        new StockQuoteSnapshot("PVRINOX", 1550.0, 1600.0, 1580.0, -3.12),
                        new StockQuoteSnapshot("SUNTV", 780.0, 800.0, 790.0, -2.50),
                        new StockQuoteSnapshot("OVEREXTENDED", 90.0, 100.0, 95.0, -10.0),
                        new StockQuoteSnapshot("TOO_FAR", 94.0, 100.0, 96.0, -6.0));
        List<String> candidates =
                scanner.filterCandidateStocks(quotes, LowestVolumeDirection.SHORT);
        assertEquals(2, candidates.size());
        assertTrue(candidates.contains("PVRINOX"));
        assertTrue(candidates.contains("SUNTV"));
        assertFalse(candidates.contains("OVEREXTENDED"));
        assertFalse(candidates.contains("TOO_FAR"));
    }
}

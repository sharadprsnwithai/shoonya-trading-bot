package com.tradingbot.strategy.breakoutvwap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.NseBanPeriodClient;
import com.tradingbot.marketdata.NseOiSpurtClient;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.feed.ShoonyaFeedClient;
import com.tradingbot.model.Candle;
import com.tradingbot.model.NseOiSpurtItem;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.model.BvsBucket;
import com.tradingbot.strategy.breakoutvwap.model.BvsUniverse;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Bucket selection, ranking, ban exclusion, and degradation contract for the BVS universe scan. */
class BvsUniverseScannerTest {

    private ShoonyaFeedClient feedClient;
    private ShoonyaMarketDataService marketDataService;
    private NseOiSpurtClient oiSpurtClient;
    private NseBanPeriodClient banPeriodClient;
    private HistoricalOhlcCacheService ohlcCacheService;
    private MockedStatic<StockFnoRegistry> registry;

    @BeforeEach
    void setUp() {
        feedClient = mock(ShoonyaFeedClient.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        oiSpurtClient = mock(NseOiSpurtClient.class);
        banPeriodClient = mock(NseBanPeriodClient.class);
        ohlcCacheService = mock(HistoricalOhlcCacheService.class);
        registry = mockStatic(StockFnoRegistry.class);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    private BvsUniverseScanner scanner() {
        return new BvsUniverseScanner(
                new BvsProperties(),
                feedClient,
                marketDataService,
                oiSpurtClient,
                banPeriodClient,
                ohlcCacheService);
    }

    private static StockQuoteSnapshot quote(String symbol, double pctChange) {
        return new StockQuoteSnapshot(symbol, 100.0 + pctChange, 100.0, 100.0, pctChange);
    }

    private void stubUniverse(String... symbols) {
        Map<String, StockFnoRegistry.InstrumentInfo> instruments = new LinkedHashMap<>();
        for (String s : symbols) {
            instruments.put(s, null);
        }
        registry.when(StockFnoRegistry::getAllInstruments).thenReturn(instruments);
    }

    private static Candle daily(String date, double high, double low) {
        return Candle.ofDaily(
                "X",
                Instant.parse(date + "T03:45:00Z"),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                1000L);
    }

    private static List<Candle> contractionSeries() {
        // oldest-first: each session a lower high and a higher low than the one before
        return List.of(
                daily("2026-10-16", 100, 90),
                daily("2026-10-19", 99, 91),
                daily("2026-10-20", 98, 92),
                daily("2026-10-21", 97, 93));
    }

    private static List<Candle> threeSessionSeries() {
        return List.of(
                daily("2026-10-19", 100, 90),
                daily("2026-10-20", 99, 91),
                daily("2026-10-21", 98, 92));
    }

    private void stubQuotes(Map<String, Double> pctBySymbol) {
        Map<String, StockQuoteSnapshot> quotes = new LinkedHashMap<>();
        pctBySymbol.forEach((sym, pct) -> quotes.put(sym, quote(sym, pct)));
        when(feedClient.snapshots(any(Collection.class), any(Duration.class))).thenReturn(quotes);
    }

    @Test
    @DisplayName("topByPctChange ranks gainers descending and losers ascending")
    void topByPctChangeRanksGainersAndLosers() {
        Collection<StockQuoteSnapshot> quotes =
                List.of(quote("A", 4.0), quote("B", 1.0), quote("C", -2.0), quote("D", -5.0));

        List<String> gainers = BvsUniverseScanner.topByPctChange(quotes, 2, true);
        List<String> losers = BvsUniverseScanner.topByPctChange(quotes, 2, false);

        assertThat(gainers).containsExactly("A", "B");
        assertThat(losers).containsExactly("D", "C");
    }

    @Test
    @DisplayName("three-day contraction needs four sessions and both-sided narrowing")
    void isThreeDayContractionRequiresFourSessions() {
        assertThat(BvsUniverseScanner.isThreeDayContraction(threeSessionSeries())).isFalse();
        assertThat(BvsUniverseScanner.isThreeDayContraction(contractionSeries())).isTrue();
        // one transition with a higher high breaks it
        List<Candle> higherHigh =
                List.of(
                        daily("2026-10-16", 100, 90),
                        daily("2026-10-19", 101, 91),
                        daily("2026-10-20", 100, 92),
                        daily("2026-10-21", 99, 93));
        assertThat(BvsUniverseScanner.isThreeDayContraction(higherHigh)).isFalse();
        // one transition with a lower low breaks it
        List<Candle> lowerLow =
                List.of(
                        daily("2026-10-16", 100, 90),
                        daily("2026-10-19", 99, 91),
                        daily("2026-10-20", 98, 90),
                        daily("2026-10-21", 97, 93));
        assertThat(BvsUniverseScanner.isThreeDayContraction(lowerLow)).isFalse();
    }

    @Test
    @DisplayName("scan builds twelve stocks across four buckets")
    void scanBuildsTwelveStocksAcrossFourBuckets() {
        stubUniverse(
                "A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4", "O1", "O2", "O3", "S1", "S2", "S3");
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("A1", 4.0);
        pct.put("A2", 3.0);
        pct.put("A3", 2.0);
        pct.put("A4", 1.0);
        pct.put("B1", -1.0);
        pct.put("B2", -2.0);
        pct.put("B3", -3.0);
        pct.put("B4", -4.0);
        pct.put("O1", 0.0);
        pct.put("O2", 0.0);
        pct.put("O3", 0.0);
        pct.put("S1", 0.0);
        pct.put("S2", 0.0);
        pct.put("S3", 0.0);
        stubQuotes(pct);
        when(oiSpurtClient.fetchTopUnderlyings(anyInt()))
                .thenReturn(List.of(oiItem("O1"), oiItem("O2"), oiItem("O3"), oiItem("O4")));
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of());
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(true);
        when(ohlcCacheService.getDailyCandles(any()))
                .thenAnswer(
                        inv -> {
                            String sym = inv.getArgument(0);
                            return sym.startsWith("S") ? contractionSeries() : threeSessionSeries();
                        });

        BvsUniverse universe = scanner().scan();

        assertThat(universe.byBucket().get(BvsBucket.GAINERS)).containsExactly("A1", "A2", "A3");
        assertThat(universe.byBucket().get(BvsBucket.LOSERS)).containsExactly("B4", "B3", "B2");
        assertThat(universe.byBucket().get(BvsBucket.OI_SPURT)).containsExactly("O1", "O2", "O3");
        assertThat(universe.byBucket().get(BvsBucket.SQUEEZE)).containsExactly("S1", "S2", "S3");
        assertThat(universe.allSymbols()).hasSize(12).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a symbol in two buckets appears once and the second bucket backfills")
    void scanDedupesAcrossBucketsAndBackfills() {
        stubUniverse("G1", "G2", "G3", "G4", "L1", "L2", "L3", "L4", "O1", "O2", "O3", "O4");
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("G1", 5.0);
        pct.put("G2", 4.0);
        pct.put("G3", 3.0);
        pct.put("G4", 2.0);
        pct.put("L1", -1.0);
        pct.put("L2", -2.0);
        pct.put("L3", -3.0);
        pct.put("L4", -4.0);
        pct.put("O1", 0.0);
        pct.put("O2", 0.0);
        pct.put("O3", 0.0);
        pct.put("O4", 0.0);
        stubQuotes(pct);
        // G1 tops both the gainer ranking and the OI-spurt list
        when(oiSpurtClient.fetchTopUnderlyings(anyInt()))
                .thenReturn(List.of(oiItem("G1"), oiItem("O2"), oiItem("O3"), oiItem("O4")));
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of());
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(true);
        when(ohlcCacheService.getDailyCandles(any())).thenReturn(threeSessionSeries());

        BvsUniverse universe = scanner().scan();

        assertThat(universe.byBucket().get(BvsBucket.GAINERS)).containsExactly("G1", "G2", "G3");
        assertThat(universe.byBucket().get(BvsBucket.OI_SPURT)).containsExactly("O2", "O3", "O4");
        assertThat(universe.allSymbols()).filteredOn("G1"::equals).hasSize(1);
    }

    @Test
    @DisplayName("ban-listed stocks are excluded and backfilled from the next rank")
    void scanExcludesBanListedStocks() {
        stubUniverse("G1", "G2", "G3", "G4", "L1", "L2", "L3", "L4", "O1", "O2", "O3");
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("G1", 5.0);
        pct.put("G2", 4.0);
        pct.put("G3", 3.0);
        pct.put("G4", 2.0);
        pct.put("L1", -1.0);
        pct.put("L2", -2.0);
        pct.put("L3", -3.0);
        pct.put("L4", -4.0);
        pct.put("O1", 0.0);
        pct.put("O2", 0.0);
        pct.put("O3", 0.0);
        stubQuotes(pct);
        when(oiSpurtClient.fetchTopUnderlyings(anyInt()))
                .thenReturn(List.of(oiItem("O1"), oiItem("O2"), oiItem("O3")));
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of("G3"));
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(true);
        when(ohlcCacheService.getDailyCandles(any())).thenReturn(threeSessionSeries());

        BvsUniverse universe = scanner().scan();

        assertThat(universe.allSymbols()).doesNotContain("G3");
        assertThat(universe.byBucket().get(BvsBucket.GAINERS)).containsExactly("G1", "G2", "G4");
    }

    @Test
    @DisplayName("a failed ban fetch stands the strategy down for the day")
    void scannerStandsDownWhenBanFetchFails() {
        stubUniverse("G1", "G2", "G3");
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of());
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(false);

        BvsUniverse universe = scanner().scan();

        assertThat(universe.allSymbols()).isEmpty();
    }

    @Test
    @DisplayName("index symbols never enter a bucket")
    void scanSkipsIndexSymbols() {
        stubUniverse("NIFTY", "G1", "G2", "G3", "L1", "L2", "L3");
        registry.when(() -> StockFnoRegistry.isIndex("NIFTY")).thenReturn(true);
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("NIFTY", 9.0);
        pct.put("G1", 5.0);
        pct.put("G2", 4.0);
        pct.put("G3", 3.0);
        pct.put("L1", -1.0);
        pct.put("L2", -2.0);
        pct.put("L3", -3.0);
        stubQuotes(pct);
        when(oiSpurtClient.fetchTopUnderlyings(anyInt())).thenReturn(List.of());
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of());
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(true);
        when(ohlcCacheService.getDailyCandles(any())).thenReturn(threeSessionSeries());

        BvsUniverse universe = scanner().scan();

        assertThat(universe.allSymbols()).doesNotContain("NIFTY");
    }

    @Test
    @DisplayName("an empty OI-spurt list degrades that bucket while the rest proceed")
    void degradesToSurvivingBucketsWhenOiFails() {
        stubUniverse("A1", "A2", "A3", "A4", "B1", "B2", "B3", "B4", "S1", "S2", "S3");
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("A1", 4.0);
        pct.put("A2", 3.0);
        pct.put("A3", 2.0);
        pct.put("A4", 1.0);
        pct.put("B1", -1.0);
        pct.put("B2", -2.0);
        pct.put("B3", -3.0);
        pct.put("B4", -4.0);
        pct.put("S1", 0.0);
        pct.put("S2", 0.0);
        pct.put("S3", 0.0);
        stubQuotes(pct);
        when(oiSpurtClient.fetchTopUnderlyings(anyInt())).thenReturn(List.of());
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of());
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(true);
        when(ohlcCacheService.getDailyCandles(any()))
                .thenAnswer(
                        inv -> {
                            String sym = inv.getArgument(0);
                            return sym.startsWith("S") ? contractionSeries() : threeSessionSeries();
                        });

        BvsUniverse universe = scanner().scan();

        assertThat(universe.allSymbols()).hasSize(9);
        assertThat(universe.byBucket().getOrDefault(BvsBucket.OI_SPURT, List.of())).isEmpty();
    }

    @Test
    @DisplayName(
            "a staged prefetch list is consumed once, then the scanner falls back to its own fetch")
    void scanConsumesStagedOiListOnce() {
        stubUniverse("G1", "G2", "G3", "L1", "L2", "L3", "O1", "O2", "O3");
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("G1", 5.0);
        pct.put("G2", 4.0);
        pct.put("G3", 3.0);
        pct.put("L1", -1.0);
        pct.put("L2", -2.0);
        pct.put("L3", -3.0);
        pct.put("O1", 0.0);
        pct.put("O2", 0.0);
        pct.put("O3", 0.0);
        stubQuotes(pct);
        when(banPeriodClient.fetchBanList()).thenReturn(Set.of());
        when(banPeriodClient.isLastFetchSuccessful()).thenReturn(true);
        when(ohlcCacheService.getDailyCandles(any())).thenReturn(threeSessionSeries());
        // nothing stubbed for fetchTopUnderlyings — a staged list must prevent the call
        BvsUniverseScanner uut = scanner();
        uut.stageOiItems(List.of(oiItem("O1"), oiItem("O2"), oiItem("O3")));

        BvsUniverse stagedScan = uut.scan();

        verify(oiSpurtClient, never()).fetchTopUnderlyings(anyInt());
        assertThat(stagedScan.byBucket().get(BvsBucket.OI_SPURT)).containsExactly("O1", "O2", "O3");

        // second scan: the staged list was consumed once -> falls back to the (empty) own fetch
        BvsUniverse fallbackScan = uut.scan();

        verify(oiSpurtClient).fetchTopUnderlyings(anyInt());
        assertThat(fallbackScan.byBucket().getOrDefault(BvsBucket.OI_SPURT, List.of())).isEmpty();
    }

    private static NseOiSpurtItem oiItem(String symbol) {
        return new NseOiSpurtItem(symbol, 100L, 90L, 10L, 5.0, 100.0);
    }
}

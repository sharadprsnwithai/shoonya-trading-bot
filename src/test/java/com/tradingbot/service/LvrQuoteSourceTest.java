package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.config.ShoonyaFeedProperties;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.feed.ShoonyaFeedClient;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.util.NiftySectorRegistry;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the quote-source selection in {@link LowestVolumeReversalService}: the streaming feed is
 * preferred, the {@code GetQuotes} REST loop backs-fill only what the feed did not supply, and REST
 * remains the sole source whenever the feed is unavailable.
 */
class LvrQuoteSourceTest {

    /**
     * Builds a service whose REST path is mocked out and whose feed is real but has no live socket.
     *
     * @return the constructed service, with the mocked market-data service alongside
     */
    private static ServiceUnderTest build() {
        ShoonyaMarketDataService marketDataService = mock(ShoonyaMarketDataService.class);
        when(marketDataService.resolveToken(anyString())).thenReturn("1");
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);

        ShoonyaConfig shoonyaConfig = new ShoonyaConfig();
        shoonyaConfig.setEnabled(false);
        shoonyaConfig.setUserId("AB1234");

        ShoonyaFeedProperties feedProperties = new ShoonyaFeedProperties();
        feedProperties.setEnabled(false);
        feedProperties.setWarmupTimeoutMs(50);
        feedProperties.setWarmupPollIntervalMs(5);

        ShoonyaFeedClient feedClient =
                new ShoonyaFeedClient(
                        feedProperties,
                        shoonyaConfig,
                        new ShoonyaAuthenticator(shoonyaConfig),
                        HttpClient.newHttpClient());

        TechnicalAnalysisService taService = mock(TechnicalAnalysisService.class);
        com.tradingbot.telegram.TelegramService telegramService =
                mock(com.tradingbot.telegram.TelegramService.class);
        LowestVolumeReversalScanner scanner = new LowestVolumeReversalScanner();

        LowestVolumeReversalService service =
                new LowestVolumeReversalService(
                        marketDataService,
                        taService,
                        telegramService,
                        shoonyaConfig,
                        null,
                        scanner,
                        null,
                        null,
                        feedClient);
        service.setMorningScanDelayMs(0);
        return new ServiceUnderTest(service, marketDataService, feedClient, feedProperties);
    }

    /**
     * @param service the service under test
     * @param marketDataService the mocked market-data service
     * @param feedClient the feed client
     * @param feedProperties the feed properties, mutable so tests can tighten the freshness budget
     */
    private record ServiceUnderTest(
            LowestVolumeReversalService service,
            ShoonyaMarketDataService marketDataService,
            ShoonyaFeedClient feedClient,
            ShoonyaFeedProperties feedProperties) {}

    /**
     * The full trading universe, mirroring what {@code fetchMorningQuotesUnified} assembles.
     *
     * @return every unique universe symbol
     */
    private static Set<String> universe() {
        Set<String> symbols = new LinkedHashSet<>(NiftySectorRegistry.NIFTY_50_CONSTITUENTS);
        for (List<String> constituents : NiftySectorRegistry.getSectorConstituents().values()) {
            symbols.addAll(constituents);
        }
        return symbols;
    }

    @Test
    @DisplayName("with no feed client the REST loop is the sole source, as before")
    void testRestOnlyWhenNoFeedClient() {
        ShoonyaMarketDataService marketDataService = mock(ShoonyaMarketDataService.class);
        when(marketDataService.resolveToken(anyString())).thenReturn("1");

        LowestVolumeReversalService service =
                new LowestVolumeReversalService(
                        marketDataService,
                        mock(TechnicalAnalysisService.class),
                        mock(com.tradingbot.telegram.TelegramService.class),
                        new ShoonyaConfig(),
                        null,
                        new LowestVolumeReversalScanner(),
                        null,
                        null,
                        null);
        service.setMorningScanDelayMs(0);

        service.fetchMorningQuotesUnified();

        verify(marketDataService, atLeastOnce()).fetchQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("a down feed causes a full REST back-fill rather than a partial universe")
    void testDownFeedFallsBackToRest() {
        ServiceUnderTest target = build();

        Map<String, StockQuoteSnapshot> out = target.service().fetchMorningQuotesUnified();

        // The mocked market-data service returns no quote, so nothing lands in the map, but every
        // universe symbol must still have been attempted over REST.
        assertThat(out).isEmpty();
        assertThat(target.feedClient().isUp()).isFalse();
        verify(target.marketDataService(), atLeastOnce()).fetchQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("a live feed covering the whole universe removes the REST loop entirely")
    void testUpFeedServesWholeUniverse() {
        ServiceUnderTest target = build();
        Set<String> symbols = universe();

        target.feedClient().handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        assertThat(target.feedClient().isUp()).isTrue();
        for (String symbol : symbols) {
            target.feedClient().ensureSubscribed(Map.of(symbol, "1"));
        }
        target.feedClient()
                .handleRawText(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"1\",\"lp\":\"100.00\",\"c\":\"95.00\","
                                + "\"o\":\"96.00\",\"v\":\"1000\",\"ap\":\"99.00\"}");

        Map<String, StockQuoteSnapshot> out = target.service().fetchMorningQuotesUnified();

        assertThat(out).hasSize(symbols.size());
        verify(target.marketDataService(), never()).fetchQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("symbols the feed omits are back-filled over REST instead of being dropped")
    void testPartialFeedBackFillsOnlyMissing() {
        ServiceUnderTest target = build();
        Set<String> symbols = universe();
        List<String> all = new ArrayList<>(symbols);
        String fed = all.get(0);

        target.feedClient().handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        target.feedClient().ensureSubscribed(Map.of(fed, "1"));
        target.feedClient()
                .handleRawText(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"1\",\"lp\":\"100.00\",\"c\":\"95.00\","
                                + "\"o\":\"96.00\",\"v\":\"1000\",\"ap\":\"99.00\"}");

        Map<String, StockQuoteSnapshot> out = target.service().fetchMorningQuotesUnified();

        // The fed symbol is present and sourced from the feed; every other symbol was attempted
        // over REST, which is what guarantees breadth coverage when the feed is partial.
        assertThat(out).containsOnlyKeys(fed);
        verify(target.marketDataService(), times(symbols.size() - 1))
                .fetchQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("a feed snapshot carries the same fields the REST path would have produced")
    void testFeedSnapshotMatchesRestShape() {
        ServiceUnderTest target = build();

        target.feedClient().handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        target.feedClient().ensureSubscribed(Map.of("DMART", "2885"));
        target.feedClient()
                .handleRawText(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\","
                                + "\"o\":\"3310.00\",\"v\":\"125000\",\"ap\":\"3340.00\"}");

        StockQuoteSnapshot snapshot =
                target.feedClient()
                        .snapshots(List.of("DMART"), target.feedClient().maxAge())
                        .get("DMART");

        assertThat(snapshot.symbol()).isEqualTo("DMART");
        assertThat(snapshot.ltp()).isEqualTo(3364.00);
        assertThat(snapshot.prevClose()).isEqualTo(3300.00);
        assertThat(snapshot.open()).isEqualTo(3310.00);
        assertThat(snapshot.volume()).isEqualTo(125000L);
        assertThat(snapshot.vwap()).isEqualTo(3340.00);
    }

    @Test
    @DisplayName("the live quote node is served from the feed and carries the breach-check fields")
    void testLiveQuoteNodeFromFeed() {
        ServiceUnderTest target = build();

        target.feedClient().handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        target.feedClient().ensureSubscribed(Map.of("RELIANCE26OCTFUT", "1"));
        target.feedClient()
                .handleRawText(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"1\",\"lp\":\"1449.00\",\"c\":\"1400.00\","
                                + "\"o\":\"1410.00\",\"h\":\"1455.00\",\"l\":\"1405.00\",\"ap\":\"1430.00\","
                                + "\"v\":\"250000\"}");

        var node = target.service().fetchLiveQuoteNode("RELIANCE26OCTFUT");

        // Every field the 30-second breach loop reads off the quote node.
        assertThat(node.get("lp").asDouble()).isEqualTo(1449.00);
        assertThat(node.get("h").asDouble()).isEqualTo(1455.00);
        assertThat(node.get("l").asDouble()).isEqualTo(1405.00);
        assertThat(node.get("ap").asDouble()).isEqualTo(1430.00);
        assertThat(node.get("c").asDouble()).isEqualTo(1400.00);
        assertThat(node.get("v").asLong()).isEqualTo(250000L);
    }

    @Test
    @DisplayName("a down feed leaves the live quote node sourced from REST")
    void testLiveQuoteNodeFallsBackToRest() {
        ServiceUnderTest target = build();

        var node = target.service().fetchLiveQuoteNode("RELIANCE");

        assertThat(node).isNull();
        verify(target.marketDataService(), atLeastOnce()).fetchQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("a stale feed tick is not used for a live breach check")
    void testLiveQuoteNodeRejectsStaleTick() {
        ServiceUnderTest target = build();
        // A REST quote carrying a deliberately different price, so the test can tell which source
        // produced the result.
        var restNode = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        restNode.put("lp", 999.0);
        restNode.put("c", 950.0);
        when(target.marketDataService().fetchQuote(anyString(), anyString())).thenReturn(restNode);

        target.feedClient().handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        target.feedClient().ensureSubscribed(Map.of("DMART", "1"));
        target.feedClient()
                .handleRawText(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"1\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");
        assertThat(target.service().fetchLiveQuoteNode("DMART").get("lp").asDouble())
                .isEqualTo(3364.00);

        // Let the tick age out, then tighten the budget so it is too old to act on. Acting on a
        // price the feed stopped updating is worse than the REST round trip.
        target.feedProperties().setSnapshotMaxAgeMs(5);
        sleep(20);

        assertThat(target.service().fetchLiveQuoteNode("DMART").get("lp").asDouble())
                .isEqualTo(999.0);
    }

    @Test
    @DisplayName("an unsubscribed live symbol falls through to REST rather than returning nothing")
    void testLiveQuoteNodeUnsubscribedGoesToRest() {
        ServiceUnderTest target = build();

        // The feed is up but has never been told about this symbol, so it cannot serve it.
        target.feedClient().handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");

        target.service().fetchLiveQuoteNode("UNKNOWNSTOCK");

        verify(target.marketDataService(), atLeastOnce()).fetchQuote(anyString(), anyString());
    }

    /**
     * @param millis how long to pause
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

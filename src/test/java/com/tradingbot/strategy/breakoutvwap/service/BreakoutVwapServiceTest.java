package com.tradingbot.strategy.breakoutvwap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.NseOiSpurtClient;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.feed.ShoonyaFeedClient;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import com.tradingbot.strategy.breakoutvwap.model.BvsBucket;
import com.tradingbot.strategy.breakoutvwap.model.BvsPosition;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetup;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetupState;
import com.tradingbot.strategy.breakoutvwap.model.BvsUniverse;
import com.tradingbot.strategy.breakoutvwap.persistence.BvsDailyState;
import com.tradingbot.strategy.breakoutvwap.persistence.BvsPositionSnapshot;
import com.tradingbot.strategy.breakoutvwap.persistence.BvsStateStore;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.NseTradingCalendarUtil;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Entry, 2.5R partial with breakeven stop, 10-EMA runner exit, cutoff and hard-exit contract. */
class BreakoutVwapServiceTest {

    private static final Instant CLOCK_INSTANT =
            ZonedDateTime.of(2026, 10, 22, 11, 0, 0, 0, NseTradingCalendarUtil.IST_ZONE)
                    .toInstant();

    private ShoonyaMarketDataService marketDataService;
    private BvsUniverseScanner scanner;
    private ShoonyaFeedClient feedClient;
    private TelegramService telegramService;
    private SignalPublisher signalPublisher;
    private MockedStatic<StockFnoRegistry> registry;
    private BvsProperties properties;
    private BreakoutVwapService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(CLOCK_INSTANT, NseTradingCalendarUtil.IST_ZONE);
        marketDataService = mock(ShoonyaMarketDataService.class);
        scanner = mock(BvsUniverseScanner.class);
        feedClient = mock(ShoonyaFeedClient.class);
        telegramService = mock(TelegramService.class);
        signalPublisher = mock(SignalPublisher.class);
        when(signalPublisher.publish(any())).thenReturn(true);
        registry = mockStatic(StockFnoRegistry.class);
        registry.when(() -> StockFnoRegistry.getLotSize(anyString())).thenReturn(25);
        properties = new BvsProperties();
        service =
                new BreakoutVwapService(
                        properties,
                        new TechnicalAnalysisService(),
                        marketDataService,
                        mock(NseOiSpurtClient.class),
                        scanner,
                        feedClient,
                        telegramService,
                        signalPublisher);
        service.setClock(clock);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    // --- helpers ---

    private static Instant ist(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 22, hour, minute, 0, 0, NseTradingCalendarUtil.IST_ZONE)
                .toInstant();
    }

    private static Candle candleAt(int index, double high, double low, double close) {
        Instant start = ist(9, 15).plusSeconds(300L * index);
        return Candle.of5m(
                "X",
                start,
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                5000L);
    }

    private static Candle dailyBar(String isoDate, double high, double low) {
        return Candle.ofDaily(
                "X",
                Instant.parse(isoDate + "T03:45:00Z"),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                1000L);
    }

    private void stubPrices(Map<String, Double> ltpBySymbol) {
        Map<String, StockQuoteSnapshot> quotes = new LinkedHashMap<>();
        ltpBySymbol.forEach(
                (sym, ltp) -> quotes.put(sym, new StockQuoteSnapshot(sym, ltp, ltp, ltp, 0.0)));
        when(feedClient.snapshots(any(Collection.class), any(Duration.class))).thenReturn(quotes);
    }

    private BvsSetup armedLongSetup(String symbol) {
        BvsSetup setup = new BvsSetup(symbol);
        setup.setState(BvsSetupState.TRIGGER_ARMED);
        setup.setBias(BvsBias.LONG);
        setup.setSessionRangeHigh(25000.0);
        setup.setSessionRangeLow(24800.0);
        setup.setPdh(25100.0);
        setup.setPdl(24700.0);
        setup.setTriggerHigh(25050.0);
        setup.setTriggerLow(24990.0);
        setup.setTriggerArmedAt(clock.instant().minusSeconds(60));
        setup.setEntryConsumed(false);
        service.setups.put(symbol, setup);
        return setup;
    }

    private BvsPosition putLongPosition(
            String symbol, double entry, double stop, double r, double target, int total) {
        BvsPosition p = new BvsPosition();
        p.setSymbol(symbol);
        p.setBrokerTradingSymbol(symbol + "23OCTFUT");
        p.setToken("4321");
        p.setDirection(BvsBias.LONG);
        p.setEntryPrice(entry);
        p.setStopPrice(stop);
        p.setRValue(r);
        p.setTargetPrice(target);
        p.setTotalQuantity(total);
        p.setRemainingQuantity(total);
        p.setPartialBooked(false);
        p.setEntryTime(clock.instant());
        p.setTradeId("BVS-TEST01");
        service.openPositions.put(symbol, p);
        return p;
    }

    private List<TradeSignal> signalsPublished() {
        ArgumentCaptor<TradeSignal> captor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalPublisher, org.mockito.Mockito.atLeastOnce()).publish(captor.capture());
        return captor.getAllValues();
    }

    // --- pure decision tests ---

    @Test
    @DisplayName("R and 2.5R target math per direction")
    void rAndTargetMath() {
        double longR = BreakoutVwapService.rFor(BvsBias.LONG, 25000.0, 24960.0);
        assertThat(longR).isEqualTo(40.0);
        assertThat(BreakoutVwapService.targetFor(BvsBias.LONG, 25000.0, longR, 2.5))
                .isEqualTo(25100.0);

        double shortR = BreakoutVwapService.rFor(BvsBias.SHORT, 24900.0, 24940.0);
        assertThat(shortR).isEqualTo(40.0);
        assertThat(BreakoutVwapService.targetFor(BvsBias.SHORT, 24900.0, shortR, 2.5))
                .isEqualTo(24800.0);
    }

    @Test
    @DisplayName("entry window expires after five minutes")
    void entryWindowExpiresAfterFiveMinutes() {
        Instant armed = ist(11, 5);
        assertThat(BreakoutVwapService.withinEntryWindow(armed, ist(11, 10), 5)).isTrue();
        assertThat(BreakoutVwapService.withinEntryWindow(armed, ist(11, 11), 5)).isFalse();
    }

    @Test
    @DisplayName("runner exits only when a candle closes across the 10 EMA against the bias")
    void runnerExitOnEmaCross() {
        assertThat(BreakoutVwapService.isRunnerExit(BvsBias.LONG, 24990.0, 25000.0)).isTrue();
        assertThat(BreakoutVwapService.isRunnerExit(BvsBias.LONG, 25010.0, 25000.0)).isFalse();
        assertThat(BreakoutVwapService.isRunnerExit(BvsBias.SHORT, 25010.0, 25000.0)).isTrue();
        assertThat(BreakoutVwapService.isRunnerExit(BvsBias.SHORT, 24990.0, 25000.0)).isFalse();
        assertThat(BreakoutVwapService.isRunnerExit(BvsBias.NONE, 24990.0, 25000.0)).isFalse();
    }

    @Test
    @DisplayName("stop hit compares price against the stop per direction")
    void stopHitCheck() {
        assertThat(BreakoutVwapService.isStopHit(BvsBias.LONG, 24959.0, 24960.0)).isTrue();
        assertThat(BreakoutVwapService.isStopHit(BvsBias.LONG, 24961.0, 24960.0)).isFalse();
        assertThat(BreakoutVwapService.isStopHit(BvsBias.SHORT, 24961.0, 24960.0)).isTrue();
        assertThat(BreakoutVwapService.isStopHit(BvsBias.SHORT, 24959.0, 24960.0)).isFalse();
    }

    // --- orchestration tests ---

    @Test
    @DisplayName("breach inside the window publishes ENTRY_LONG with the trigger-low stop")
    void entryPublishesSignalAndSetsStop() {
        properties.setDynamicPositionSizing(false);
        armedLongSetup("RELIANCE");
        when(marketDataService.resolveFuturesContract("RELIANCE"))
                .thenReturn(
                        new ShoonyaMarketDataService.FuturesContract("RELIANCE23OCTFUT", "4321"));
        stubPrices(Map.of("RELIANCE", 25060.0));

        service.evaluateLivePriceActions();

        List<TradeSignal> signals = signalsPublished();
        assertThat(signals).hasSize(1);
        TradeSignal entry = signals.get(0);
        assertThat(entry.strategyId()).isEqualTo(BreakoutVwapService.STRATEGY_ID);
        assertThat(entry.action()).isEqualTo(SignalAction.ENTRY_LONG);
        assertThat(entry.tradingSymbol()).isEqualTo("RELIANCE23OCTFUT");
        assertThat(entry.stopLoss()).isEqualByComparingTo(BigDecimal.valueOf(24990.0));
        assertThat(entry.baseQuantity()).isEqualTo(25);
        assertThat(entry.metadata())
                .containsEntry("positionSide", "LONG")
                .containsEntry("exchange", "NFO");
        assertThat(service.openPositions).containsKey("RELIANCE");
        assertThat(service.setups.get("RELIANCE").isEntryConsumed()).isTrue();
    }

    @Test
    @DisplayName("no entry after the 14:30 cutoff")
    void entryBlockedAfterCutoff() {
        clock =
                Clock.fixed(
                        ZonedDateTime.of(
                                        2026, 10, 22, 14, 31, 0, 0, NseTradingCalendarUtil.IST_ZONE)
                                .toInstant(),
                        NseTradingCalendarUtil.IST_ZONE);
        service.setClock(clock);
        armedLongSetup("RELIANCE");
        when(marketDataService.resolveFuturesContract("RELIANCE"))
                .thenReturn(
                        new ShoonyaMarketDataService.FuturesContract("RELIANCE23OCTFUT", "4321"));
        stubPrices(Map.of("RELIANCE", 25060.0));

        service.evaluateLivePriceActions();

        verify(signalPublisher, never()).publish(any());
        assertThat(service.openPositions).isEmpty();
    }

    @Test
    @DisplayName("dynamic sizing scales with risk and caps at double the default lots")
    void dynamicSizingRespectsRiskAndDoubleLotsCap() {
        BvsSetup wide = armedLongSetup("RELIANCE");
        wide.setTriggerLow(24990.0);
        when(marketDataService.resolveFuturesContract("RELIANCE"))
                .thenReturn(
                        new ShoonyaMarketDataService.FuturesContract("RELIANCE23OCTFUT", "4321"));
        // R = 70: 5000 / (70*25) = 2.86, capped at lots(1)*2 = 2 -> 2 lots = 50
        service.executeEntry("RELIANCE", wide, 25060.0);
        assertThat(service.openPositions.get("RELIANCE").getTotalQuantity()).isEqualTo(50);

        BvsSetup tight = new BvsSetup("TCS");
        tight.setState(BvsSetupState.TRIGGER_ARMED);
        tight.setBias(BvsBias.LONG);
        tight.setTriggerHigh(30050.0);
        tight.setTriggerLow(24990.0);
        // R = 5010: 5000 / (5010*25) = 0.04 -> floor to 0, min lots 1 = 25
        service.executeEntry("TCS", tight, 30000.0);
        assertThat(service.openPositions.get("TCS").getTotalQuantity()).isEqualTo(25);
    }

    @Test
    @DisplayName("touching 2.5R books half and moves the stop to breakeven")
    void partialAtTargetMovesStopToBreakeven() {
        putLongPosition("RELIANCE", 25000.0, 24960.0, 40.0, 25100.0, 25);
        stubPrices(Map.of("RELIANCE", 25101.0));

        service.evaluateLivePriceActions();

        List<TradeSignal> signals = signalsPublished();
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).action()).isEqualTo(SignalAction.PARTIAL_EXIT_LONG);
        assertThat(signals.get(0).baseQuantity()).isEqualTo(13);
        assertThat(signals.get(0).metadata()).containsEntry("partialExitRatio", 0.5);
        assertThat(signals.get(1).action()).isEqualTo(SignalAction.UPDATE_STOP_LOSS);
        assertThat(signals.get(1).stopLoss()).isEqualByComparingTo(BigDecimal.valueOf(25000.0));

        BvsPosition p = service.openPositions.get("RELIANCE");
        assertThat(p.isPartialBooked()).isTrue();
        assertThat(p.getRemainingQuantity()).isEqualTo(12);
        assertThat(p.getStopPrice()).isEqualTo(25000.0);

        // a second tick above target must not book the partial twice
        service.evaluateLivePriceActions();
        verify(signalPublisher, org.mockito.Mockito.times(2)).publish(any());
    }

    @Test
    @DisplayName("stop hit exits the full remainder and moves the trade to history")
    void stopHitExitsFullRemaining() {
        putLongPosition("RELIANCE", 25000.0, 24960.0, 40.0, 25100.0, 25);
        stubPrices(Map.of("RELIANCE", 24950.0));

        service.evaluateLivePriceActions();

        List<TradeSignal> signals = signalsPublished();
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).action()).isEqualTo(SignalAction.EXIT_LONG);
        assertThat(signals.get(0).baseQuantity()).isEqualTo(25);
        assertThat(service.openPositions).isEmpty();
        assertThat(service.tradeHistory).hasSize(1);
    }

    @Test
    @DisplayName("runner exits on a 5m candle close across the 10 EMA")
    void runnerExitOnFiveMinuteClose() {
        putLongPosition("RELIANCE", 25000.0, 24960.0, 40.0, 25100.0, 25);
        service.openPositions.get("RELIANCE").setPartialBooked(true);
        service.openPositions.get("RELIANCE").setRemainingQuantity(12);
        BvsSetup setup = new BvsSetup("RELIANCE");
        setup.setState(BvsSetupState.BIAS_ARMED);
        setup.setBias(BvsBias.LONG);
        setup.setSessionRangeHigh(25000.0);
        setup.setSessionRangeLow(24800.0);
        setup.setPdh(25100.0);
        setup.setPdl(24700.0);
        service.setups.put("RELIANCE", setup);

        // flat series around 25000 with a last close of 24990 — EMA10 stays above 24990
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candles.add(candleAt(i, 25005, 24995, 25000));
        }
        candles.add(candleAt(11, 25005, 24985, 24990));
        when(marketDataService.fetch5MinCandles("RELIANCE", 5)).thenReturn(candles);

        service.runCycle();

        List<TradeSignal> signals = signalsPublished();
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).action()).isEqualTo(SignalAction.EXIT_LONG);
        assertThat(signals.get(0).reason()).isEqualTo("EMA10_CROSS");
        assertThat(service.openPositions).isEmpty();
        assertThat(service.tradeHistory).hasSize(1);
    }

    @Test
    @DisplayName("hard exit at 15:00 squares off everything")
    void hardExitSquaresEverything() {
        putLongPosition("RELIANCE", 25000.0, 24960.0, 40.0, 25100.0, 25);
        putLongPosition("TCS", 4000.0, 3980.0, 20.0, 4050.0, 25);
        stubPrices(Map.of("RELIANCE", 25040.0, "TCS", 4010.0));

        service.executeHardExit(LocalTime.of(15, 0));

        List<TradeSignal> signals = signalsPublished();
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).action()).isEqualTo(SignalAction.SQUARE_OFF);
        assertThat(signals.get(1).action()).isEqualTo(SignalAction.SQUARE_OFF);
        assertThat(service.openPositions).isEmpty();
        assertThat(service.tradeHistory).hasSize(2);
    }

    @Test
    @DisplayName("hard exit still squares off when the feed returns no snapshot map")
    void hardExitSurvivesNullQuoteMap() {
        putLongPosition("RELIANCE", 25000.0, 24960.0, 40.0, 25100.0, 25);
        when(feedClient.snapshots(any(Collection.class), any(Duration.class))).thenReturn(null);

        service.executeHardExit(LocalTime.of(15, 0));

        assertThat(service.openPositions).isEmpty();
        assertThat(signalsPublished()).hasSize(1);
    }

    @Test
    @DisplayName("range and confluence use only today's completed bars")
    void cycleIgnoresPriorDayAndFormingCandles() {
        BvsSetup setup = new BvsSetup("RELIANCE");
        service.setups.put("RELIANCE", setup);
        // yesterday's six in-window bars carry a wildly wider range — must be ignored
        List<Candle> candles = new ArrayList<>();
        Instant yesterdayBase =
                ZonedDateTime.of(2026, 10, 21, 9, 30, 0, 0, NseTradingCalendarUtil.IST_ZONE)
                        .toInstant();
        for (int i = 0; i < 6; i++) {
            candles.add(
                    Candle.of5m(
                            "X",
                            yesterdayBase.plusSeconds(300L * i),
                            BigDecimal.valueOf(25000),
                            BigDecimal.valueOf(30000),
                            BigDecimal.valueOf(20000),
                            BigDecimal.valueOf(25000),
                            5000L));
        }
        // today's six completed in-window bars (09:30..09:55): narrow range
        for (int i = 3; i <= 8; i++) {
            candles.add(candleAt(i, 25020, 24980, 25000));
        }
        // today's forming bar (opens at the clock's 11:00) with an extreme close
        candles.add(
                Candle.of5m(
                        "X",
                        ist(11, 0),
                        BigDecimal.valueOf(25500),
                        BigDecimal.valueOf(25500),
                        BigDecimal.valueOf(25000),
                        BigDecimal.valueOf(25500),
                        5000L));
        when(marketDataService.fetch5MinCandles("RELIANCE", 5)).thenReturn(candles);
        when(marketDataService.fetchDailyCandles("RELIANCE", 10))
                .thenReturn(List.of(dailyBar("2026-10-21", 25100, 24700)));

        service.runCycle();

        assertThat(setup.getSessionRangeHigh()).isEqualTo(25020.0);
        assertThat(setup.getSessionRangeLow()).isEqualTo(24980.0);
        // the forming bar's close must not latch a bias
        assertThat(setup.getBias()).isEqualTo(BvsBias.NONE);
        assertThat(setup.getState()).isEqualTo(BvsSetupState.AWAITING_CONFLUENCE);
    }

    @Test
    @DisplayName("expired window does not enter; the next VWAP-cross candle re-arms")
    void expiredTriggerRearmsAndWaitsForNextVwapCandle() {
        BvsSetup setup = armedLongSetup("RELIANCE");
        setup.setTriggerArmedAt(clock.instant().minusSeconds(600));
        stubPrices(Map.of("RELIANCE", 25060.0));

        service.evaluateLivePriceActions();

        verify(signalPublisher, never()).publish(any());
        assertThat(setup.getState()).isEqualTo(BvsSetupState.BIAS_ARMED);
        assertThat(setup.isEntryConsumed()).isTrue();

        // next 5m cycle: a VWAP-cross candle re-arms with fresh levels
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candles.add(candleAt(i, 24910, 24890, 24900));
        }
        candles.add(candleAt(11, 25020, 24980, 25010));
        when(marketDataService.fetch5MinCandles("RELIANCE", 5)).thenReturn(candles);

        service.runCycle();

        assertThat(setup.getState()).isEqualTo(BvsSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerHigh()).isEqualTo(25020.0);
        assertThat(setup.getTriggerLow()).isEqualTo(24980.0);
        assertThat(setup.isEntryConsumed()).isFalse();
        assertThat(setup.getTriggerArmedAt()).isEqualTo(ist(10, 15));
    }

    @Test
    @DisplayName("no bias means no trigger even on a VWAP-cross candle")
    void noBiasNoTrigger() {
        BvsSetup setup = new BvsSetup("RELIANCE");
        setup.setState(BvsSetupState.AWAITING_CONFLUENCE);
        setup.setSessionRangeHigh(25000.0);
        setup.setSessionRangeLow(24800.0);
        setup.setPdh(25100.0);
        setup.setPdl(24700.0);
        service.setups.put("RELIANCE", setup);
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candles.add(candleAt(i, 24910, 24890, 24900));
        }
        candles.add(candleAt(11, 25020, 24980, 25010));
        when(marketDataService.fetch5MinCandles("RELIANCE", 5)).thenReturn(candles);

        service.runCycle();

        assertThat(setup.getState()).isEqualTo(BvsSetupState.AWAITING_CONFLUENCE);
        assertThat(setup.getTriggerHigh()).isNull();
        assertThat(setup.getBias()).isEqualTo(BvsBias.NONE);
    }

    @Test
    @DisplayName("opposite confluence cancels the armed trigger")
    void biasFlipCancelsArmedTrigger() {
        BvsSetup setup = armedLongSetup("RELIANCE");
        setup.setTriggerArmedAt(clock.instant().minusSeconds(30));
        // closes fall below both lows; VWAP sits under the last close so no SHORT trigger arms
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candles.add(candleAt(i, 24610, 24590, 24600));
        }
        candles.add(candleAt(11, 24660, 24595, 24650));
        when(marketDataService.fetch5MinCandles("RELIANCE", 5)).thenReturn(candles);

        service.runCycle();

        assertThat(setup.getBias()).isEqualTo(BvsBias.SHORT);
        assertThat(setup.getState()).isEqualTo(BvsSetupState.BIAS_ARMED);
        assertThat(setup.getTriggerHigh()).isNull();
        assertThat(setup.getTriggerLow()).isNull();

        // stale LONG window can no longer produce an entry
        stubPrices(Map.of("RELIANCE", 24600.0));
        service.evaluateLivePriceActions();
        verify(signalPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("one open position per stock blocks a second entry")
    void oneOpenPositionPerStock() {
        armedLongSetup("RELIANCE");
        putLongPosition("RELIANCE", 25000.0, 24960.0, 40.0, 25100.0, 25);
        stubPrices(Map.of("RELIANCE", 25060.0));

        service.evaluateLivePriceActions();

        verify(signalPublisher, never()).publish(any());
        assertThat(service.openPositions).hasSize(1);
    }

    // --- scan tests ---

    @Test
    @DisplayName("runScan builds setups with previous-day levels")
    void runScanBuildsSetupsWithPreviousDayLevels() {
        when(scanner.scan())
                .thenReturn(new BvsUniverse(Map.of(BvsBucket.GAINERS, List.of("RELIANCE"))));
        when(marketDataService.fetchDailyCandles("RELIANCE", 10))
                .thenReturn(
                        List.of(
                                dailyBar("2026-10-21", 25120.0, 24780.0),
                                dailyBar("2026-10-22", 25090.0, 24900.0)));

        service.runScan();

        BvsSetup setup = service.setups.get("RELIANCE");
        assertThat(setup).isNotNull();
        assertThat(setup.getPdh()).isEqualTo(25120.0);
        assertThat(setup.getPdl()).isEqualTo(24780.0);
        assertThat(setup.getState()).isEqualTo(BvsSetupState.AWAITING_RANGE);
        verify(telegramService, never()).sendTextMessage(anyString());
    }

    @Test
    @DisplayName("runScan stands down with a Telegram notice on an empty universe")
    void runScanStandsDownOnEmptyUniverse() {
        when(scanner.scan()).thenReturn(BvsUniverse.empty());

        service.runScan();

        assertThat(service.setups).isEmpty();
        verify(telegramService)
                .sendTextMessage(org.mockito.ArgumentMatchers.contains("stood down"));
    }

    // --- persistence restore tests ---

    @TempDir Path tempDir;

    private BvsPositionSnapshot partialBookedSnapshot(String symbol) {
        BvsPositionSnapshot snap = new BvsPositionSnapshot();
        snap.symbol = symbol;
        snap.brokerTradingSymbol = symbol + "23OCTFUT";
        snap.token = "4321";
        snap.direction = BvsBias.LONG;
        snap.entryPrice = 25000.0;
        snap.stopPrice = 25000.0;
        snap.rValue = 40.0;
        snap.targetPrice = 25100.0;
        snap.totalQuantity = 25;
        snap.remainingQuantity = 12;
        snap.partialBooked = true;
        snap.entryTime = Instant.parse("2026-10-22T05:40:00Z");
        snap.tradeId = "BVS-RESTORE";
        return snap;
    }

    private Path saveRestoreState(LocalDate date, BvsPositionSnapshot position) throws Exception {
        BvsDailyState state = new BvsDailyState();
        state.date = date;
        state.savedAt = Instant.parse("2026-10-22T05:45:00Z");
        state.openPositions.add(position);
        Path file = tempDir.resolve("bvs-restore.json");
        BvsStateStore.save(file, state);
        properties.setStateFile(file.toString());
        properties.setStatePersistenceEnabled(true);
        return file;
    }

    @Test
    @DisplayName("a restored partial-booked position is not partially booked again")
    void restoredPartialBookedPositionDoesNotRepartial() throws Exception {
        saveRestoreState(LocalDate.now(clock), partialBookedSnapshot("RELIANCE"));

        service.restorePersistedState();

        assertThat(service.openPositions).containsKey("RELIANCE");
        assertThat(service.openPositions.get("RELIANCE").isPartialBooked()).isTrue();

        // price beyond target again — the partial must not fire a second time
        stubPrices(Map.of("RELIANCE", 25101.0));
        service.evaluateLivePriceActions();

        verify(signalPublisher, never()).publish(any());
        assertThat(service.openPositions).containsKey("RELIANCE");
    }

    @Test
    @DisplayName("a snapshot dated before today is ignored on restore")
    void restoreSkipsStaleDate() throws Exception {
        saveRestoreState(LocalDate.now(clock).minusDays(1), partialBookedSnapshot("RELIANCE"));

        service.restorePersistedState();

        assertThat(service.openPositions).isEmpty();
        assertThat(service.setups).isEmpty();
    }
}

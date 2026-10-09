package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.config.CarExecutionGate;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.GttExecutionGateway;
import com.tradingbot.strategy.car.gtt.GttFill;
import com.tradingbot.strategy.car.gtt.LiveGttTrigger;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.CarPortfolioState;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the reconciliation half of the weekly routine: duplicate handling, fill detection, and the
 * CAR-negative cancellation rule from spec section 3.1.
 */
class CarGttReconciliationTest {

    private static final Instant MONDAY = Instant.parse("2026-09-21T04:00:00Z");

    @TempDir Path tempDir;

    private CarWeeklyGttService service;
    private HistoricalOhlcCacheService ohlcService;
    private GttExecutionGateway gateway;
    private TelegramService telegramService;
    private CarWeeklyProperties props;

    @BeforeEach
    void setUp() {
        ohlcService = mock(HistoricalOhlcCacheService.class);
        gateway = mock(GttExecutionGateway.class);
        telegramService = mock(TelegramService.class);
        when(gateway.getBrokerName()).thenReturn("ZERODHA");

        CarExecutionGate gate = mock(CarExecutionGate.class);
        when(gate.isLiveWithLogging()).thenReturn(true);

        props = new CarWeeklyProperties();
        props.setTotalCapital(1000000.0);
        props.setNumParts(40);
        props.setStateFilePath(tempDir.resolve("car_state.json").toString());
        props.setTelegramAlerts(false);

        service =
                new CarWeeklyGttService(
                        props,
                        new CarCalculator(10, 252),
                        new CarWeeklyTriggerGenerator(new BigDecimal("0.10")),
                        ohlcService,
                        List.of(gateway),
                        telegramService,
                        gate);
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    void testBuyAndSellTriggerOnTheSameSymbolAreBothKept() {
        CarPortfolioStateFixture fx = holdingWithBothTriggers();
        when(gateway.listActiveGtts())
                .thenReturn(
                        List.of(
                                live("100", "RELIANCE", "BUY", "2500.00", 10, "active"),
                                live("200", "RELIANCE", "SELL", "2657.00", 50, "active")));

        service.runSundayWeeklyRoutine(true);

        // Collapsing both into one symbol group would cancel the sell target and silently remove
        // the only exit for this position.
        verify(gateway, never()).cancelGtt(any());
        assertEquals("100", fx.state().getGttOrders().get("RELIANCE").gttId());
        assertEquals("200", fx.state().getGttOrders().get("RELIANCE:SELL_TARGET").gttId());
    }

    @Test
    void testIdenticalDuplicateBuyTriggerIsCancelled() {
        CarPortfolioStateFixture fx = holdingWithBothTriggers();
        when(gateway.listActiveGtts())
                .thenReturn(
                        List.of(
                                live("100", "RELIANCE", "BUY", "2500.00", 10, "active"),
                                live("101", "RELIANCE", "BUY", "2500.00", 10, "active"),
                                live("200", "RELIANCE", "SELL", "2657.00", 50, "active")));

        service.runSundayWeeklyRoutine(true);

        verify(gateway, times(1)).cancelGtt("101");
        verify(gateway, never()).cancelGtt("100");
        verify(gateway, never()).cancelGtt("200");
        assertEquals("100", fx.state().getGttOrders().get("RELIANCE").gttId());
    }

    @Test
    void testForeignTriggerWithDifferentPriceIsLeftAlone() {
        CarPortfolioStateFixture fx = holdingWithBothTriggers();
        when(gateway.listActiveGtts())
                .thenReturn(
                        List.of(
                                live("100", "RELIANCE", "BUY", "2500.00", 10, "active"),
                                // A manually placed, differently sized trigger on the same symbol
                                live("999", "RELIANCE", "BUY", "2450.00", 7, "active"),
                                live("200", "RELIANCE", "SELL", "2657.00", 50, "active")));

        service.runSundayWeeklyRoutine(true);

        verify(gateway, never()).cancelGtt(any());
        assertEquals("100", fx.state().getGttOrders().get("RELIANCE").gttId());
    }

    @Test
    void testMissingStateEntryIsRecoveredByAdoptingTheLiveTrigger() {
        // State file lost, broker still has the sell target.
        service.getPortfolioState().addFill("RELIANCE", 50, new BigDecimal("2500.00"));
        when(gateway.listActiveGtts())
                .thenReturn(List.of(live("777", "RELIANCE", "SELL", "2657.00", 50, "active")));

        service.runSundayWeeklyRoutine(true);

        CarGttOrder adopted =
                service.getPortfolioState().getGttOrders().get("RELIANCE:SELL_TARGET");
        assertNotNull(adopted, "live trigger must be re-adopted instead of duplicated");
        assertEquals("777", adopted.gttId());
        assertEquals(50, adopted.quantity());
        // Already armed on the broker - placement must not be repeated.
        verify(gateway, never()).placeGtt(any());
    }

    @Test
    void testAdoptedTriggerReservesRealCapitalNotAPlaceholder() {
        service.getPortfolioState().addFill("RELIANCE", 50, new BigDecimal("2500.00"));
        int before = service.getPortfolioState().getAvailableUnits();
        when(gateway.listActiveGtts())
                .thenReturn(List.of(live("777", "RELIANCE", "SELL", "2657.00", 50, "active")));

        service.runSundayWeeklyRoutine(true);

        // The holding still occupies its capital; the adopted SELL order is not a BUY so it must
        // not consume an extra unit either.
        assertEquals(before, service.getPortfolioState().getAvailableUnits());
    }

    // ---------------------------------------------------------------- fills

    @Test
    void testTriggeredBuyCreatesHoldingAndReleasesItsReservation() {
        var state = service.getPortfolioState();
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("100", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));
        // Pending buy reserves 10 x 2500.10 = 25,001 -> 2 units of the 40.
        assertEquals(38, state.getAvailableUnits());

        when(gateway.getGttStatus("100")).thenReturn(GttStatus.TRIGGERED);
        when(gateway.getTriggerFill("100")).thenReturn(new GttFill(10, new BigDecimal("2490.00")));

        service.runSundayWeeklyRoutine(true);

        assertNull(state.getGttOrders().get("RELIANCE"), "buy entry must be consumed");
        assertNotNull(state.getHolding("RELIANCE"));
        assertEquals(10, state.getHolding("RELIANCE").totalQuantity());
        assertEquals(new BigDecimal("2490.00"), state.getHolding("RELIANCE").averageBuyPrice());
        // 10 x 2490 = 24,900 -> 1 unit now, versus the 2 the pending order used to reserve.
        assertEquals(39, state.getAvailableUnits());
    }

    @Test
    void testTriggeredSellClosesHoldingAndCompoundsCapital() {
        CarPortfolioStateFixture fx = holdingWithBothTriggers();
        when(gateway.getGttStatus("200")).thenReturn(GttStatus.TRIGGERED);
        when(gateway.getTriggerFill("200")).thenReturn(new GttFill(50, new BigDecimal("2657.00")));

        service.runSundayWeeklyRoutine(true);

        assertNull(fx.state().getHoldings().get("RELIANCE"));
        assertNull(fx.state().getGttOrders().get("RELIANCE:SELL_TARGET"));
        assertTrue(fx.state().getRealizedPnL().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(fx.state().getTotalCapital().compareTo(new BigDecimal("1000000.0")) > 0);
    }

    @Test
    void testCancelledTriggerReleasesItsReservedUnits() {
        CarPortfolioState state = service.getPortfolioState();
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("GTT_1", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));
        int withReservation = state.getAvailableUnits();
        assertTrue(withReservation < 40);

        when(gateway.getGttStatus("GTT_1")).thenReturn(GttStatus.EXPIRED);
        service.runSundayWeeklyRoutine(true);

        assertNull(state.getGttOrders().get("RELIANCE"));
        assertEquals(40, state.getAvailableUnits());
    }

    @Test
    void testUnknownStatusNeverDestroysState() {
        CarPortfolioState state = service.getPortfolioState();
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("GTT_1", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));
        when(gateway.getGttStatus("GTT_1")).thenReturn(null);

        service.runSundayWeeklyRoutine(true);

        assertNotNull(state.getGttOrders().get("RELIANCE"));
    }

    // ---------------------------------------------------------------- spec 3.1

    @Test
    void testBuyTriggerIsCancelledWhenSymbolIsNoLongerCarPositive() {
        CarPortfolioState state = service.getPortfolioState();
        state.addFill("RELIANCE", 50, new BigDecimal("2500.00")); // guarantees universe membership
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("GTT_1", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));

        // Flat closes -> cumulative average never rises -> not CAR-positive, but evaluated.
        when(ohlcService.getDailyCandles(any())).thenReturn(flatCandles("RELIANCE"));

        service.runSundayWeeklyRoutine(true);

        verify(gateway, times(1)).cancelGtt("GTT_1");
        assertNull(state.getGttOrders().get("RELIANCE"));
    }

    @Test
    void testBuyTriggerSurvivesWhenSymbolCannotBeEvaluated() {
        CarPortfolioState state = service.getPortfolioState();
        state.addFill("RELIANCE", 50, new BigDecimal("2500.00"));
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("GTT_1", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));

        // Data outage: no candles for any symbol -> nothing may be cancelled.
        when(ohlcService.getDailyCandles(any())).thenReturn(null);

        service.runSundayWeeklyRoutine(true);

        verify(gateway, never()).cancelGtt(any());
        assertNotNull(state.getGttOrders().get("RELIANCE"));
    }

    @Test
    void testBuyTriggerIsKeptWhileSymbolStaysCarPositive() {
        CarPortfolioState state = service.getPortfolioState();
        state.addFill("RELIANCE", 50, new BigDecimal("2500.00"));
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("GTT_1", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));

        when(ohlcService.getDailyCandles(any())).thenReturn(risingCandles("RELIANCE"));

        service.runSundayWeeklyRoutine(true);

        verify(gateway, never()).cancelGtt("GTT_1");
        assertNotNull(state.getGttOrders().get("RELIANCE"));
    }

    // ---------------------------------------------------------------- helpers

    private CarPortfolioStateFixture holdingWithBothTriggers() {
        var state = service.getPortfolioState();
        state.addFill("RELIANCE", 50, new BigDecimal("2500.00"));
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        order("100", "RELIANCE", GttOrderType.BUY, "2500.00", "2500.10", 10));
        state.getGttOrders()
                .put(
                        "RELIANCE:SELL_TARGET",
                        order(
                                "200",
                                "RELIANCE",
                                GttOrderType.SELL_TARGET,
                                "2657.00",
                                "2657.00",
                                50));
        return new CarPortfolioStateFixture(state);
    }

    private static CarGttOrder order(
            String id, String symbol, GttOrderType type, String trigger, String limit, int qty) {
        return new CarGttOrder(
                id,
                "ZERODHA",
                symbol,
                type,
                new BigDecimal(trigger),
                new BigDecimal(limit),
                qty,
                GttStatus.PENDING,
                LocalDate.now(),
                Instant.now());
    }

    private static LiveGttTrigger live(
            String id,
            String symbol,
            String transactionType,
            String triggerValue,
            int quantity,
            String status) {
        return new LiveGttTrigger(
                id,
                symbol,
                transactionType,
                new BigDecimal(triggerValue),
                quantity,
                status,
                Instant.parse("2026-09-28T04:00:00Z"));
    }

    /** Identical flat sessions (chronological) -> cumulative average never rises. */
    private static List<Candle> flatCandles(String symbol) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candles.add(
                    Candle.ofDaily(
                            symbol,
                            MONDAY.minus(19 - i, ChronoUnit.DAYS),
                            new BigDecimal("100"),
                            new BigDecimal("100"),
                            new BigDecimal("100"),
                            new BigDecimal("100"),
                            1000));
        }
        return candles;
    }

    /**
     * Chronological candles whose cumulative average rises for far longer than ten sessions: the
     * 52-week high close sits first, then a pullback that grinds steadily higher.
     */
    private static List<Candle> risingCandles(String symbol) {
        List<Candle> candles = new ArrayList<>();
        int total = 40;
        for (int i = 0; i < total; i++) {
            // Index 0 is the OLDEST session (daily candles are chronological).
            BigDecimal close =
                    i == 0
                            ? new BigDecimal("139") // 52-week high, anchor for the CAR window
                            : new BigDecimal("99").add(BigDecimal.valueOf(i));
            candles.add(
                    Candle.ofDaily(
                            symbol,
                            MONDAY.minus(total - 1L - i, ChronoUnit.DAYS),
                            close,
                            close.add(BigDecimal.ONE),
                            close.subtract(BigDecimal.ONE),
                            close,
                            1000));
        }
        return candles;
    }

    private record CarPortfolioStateFixture(
            com.tradingbot.strategy.car.model.CarPortfolioState state) {}
}

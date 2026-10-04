package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.strategy.car.config.CarExecutionGate;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.ZerodhaKiteGttGateway;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CarWeeklyGttServiceTest {

    @TempDir Path tempDir;

    private CarWeeklyGttService service;
    private CarCalculator calculator;
    private CarWeeklyTriggerGenerator triggerGenerator;
    private HistoricalOhlcCacheService ohlcService;
    private ZerodhaKiteGttGateway gttGateway;
    private TelegramService telegramService;
    private CarExecutionGate executionGate;
    private CarWeeklyProperties props;

    @BeforeEach
    void setUp() {
        calculator = new CarCalculator(10, 252);
        triggerGenerator = new CarWeeklyTriggerGenerator(new BigDecimal("0.10"));
        ohlcService = mock(HistoricalOhlcCacheService.class);
        gttGateway = mock(ZerodhaKiteGttGateway.class);
        telegramService = mock(TelegramService.class);
        executionGate = mock(CarExecutionGate.class);
        when(executionGate.isLiveWithLogging()).thenReturn(true);
        when(gttGateway.getBrokerName()).thenReturn("ZERODHA");

        props = new CarWeeklyProperties();
        props.setTotalCapital(1000000.0);
        props.setNumParts(40);
        props.setStateFilePath(tempDir.resolve("car_state.json").toString());
        props.setTelegramAlerts(false);

        service =
                new CarWeeklyGttService(
                        props,
                        calculator,
                        triggerGenerator,
                        ohlcService,
                        List.of(gttGateway),
                        telegramService,
                        executionGate);
    }

    @Test
    void testSundayRoutineComputesUnitAndScansUniverse() {
        assertEquals(new BigDecimal("25000.00"), service.getPortfolioState().getUnitSize());
        assertEquals(40, service.getPortfolioState().getAvailableUnits());
    }

    @Test
    void testManageSellTargetGttsPlacesSellGttOnGateways() {
        when(gttGateway.placeGtt(any())).thenReturn("GTT_SELL_999");

        // Add an active holding for RELIANCE (50 shares @ avg 2500, target 2657.00)
        service.getPortfolioState().addFill("RELIANCE", 50, BigDecimal.valueOf(2500.0));

        service.runSundayWeeklyRoutine(true);

        // Verify exactly one SELL GTT was placed at the +6.28% target - "at least once" would
        // not catch a second placement on the same or another gateway.
        verify(gttGateway, times(1))
                .placeGtt(
                        argThat(
                                req ->
                                        req.type()
                                                        == com.tradingbot.strategy.car.model
                                                                .GttOrderType.SELL_TARGET
                                                && "RELIANCE".equals(req.symbol())
                                                && req.quantity() == 50
                                                && req.triggerPrice()
                                                                .compareTo(
                                                                        BigDecimal.valueOf(2657.00))
                                                        == 0));
    }

    @Test
    void testRunsOncePerTradingWeekUnlessForced() {
        service.getPortfolioState().addFill("RELIANCE", 50, BigDecimal.valueOf(2500.0));
        when(gttGateway.placeGtt(any())).thenReturn("GTT_SELL_999");

        service.runSundayWeeklyRoutine();
        verify(gttGateway, times(1)).listActiveGtts();
        verify(gttGateway, times(1)).placeGtt(any());
        assertNotNull(service.getPortfolioState().getLastRunWeek());

        // Cron/startup/manual re-trigger in the same ISO week must be a no-op.
        service.runSundayWeeklyRoutine();
        verify(gttGateway, times(1)).listActiveGtts();
        verify(gttGateway, times(1)).placeGtt(any());

        // Explicit force reruns, but the already-correct sell target is still not duplicated.
        service.runSundayWeeklyRoutine(true);
        verify(gttGateway, times(2)).listActiveGtts();
        verify(gttGateway, times(1)).placeGtt(any());
    }

    @Test
    void testDoesNotPlaceOnGatewayThatIsNotConfigured() {
        // The configured broker is ZERODHA; a second registered gateway must never be used.
        var otherGateway = mock(ZerodhaKiteGttGateway.class);
        when(otherGateway.getBrokerName()).thenReturn("SHOONYA");

        CarWeeklyGttService twoGatewayService =
                new CarWeeklyGttService(
                        props,
                        calculator,
                        triggerGenerator,
                        ohlcService,
                        List.of(gttGateway, otherGateway),
                        telegramService,
                        executionGate);
        twoGatewayService.getPortfolioState().addFill("RELIANCE", 50, BigDecimal.valueOf(2500.0));
        when(gttGateway.placeGtt(any())).thenReturn("GTT_SELL_999");

        twoGatewayService.runSundayWeeklyRoutine(true);

        verify(otherGateway, never()).placeGtt(any());
        verify(otherGateway, never()).modifyGtt(any(), any());
        verify(otherGateway, never()).cancelGtt(any());
        verify(gttGateway, times(1)).placeGtt(any());
    }

    @Test
    void testPaperModeRoutesToLocalWatcherAndNeverTouchesBroker() {
        when(executionGate.isLiveWithLogging()).thenReturn(false);
        var localWatcher = mock(com.tradingbot.strategy.car.gtt.LocalGttWatcherGateway.class);
        when(localWatcher.getBrokerName()).thenReturn("SHOONYA");
        when(localWatcher.placeGtt(any())).thenReturn("LOCAL_GTT_ABCDEF12");

        CarWeeklyGttService paperService =
                new CarWeeklyGttService(
                        props,
                        calculator,
                        triggerGenerator,
                        ohlcService,
                        List.of(gttGateway, localWatcher),
                        telegramService,
                        executionGate);
        paperService.getPortfolioState().addFill("RELIANCE", 50, BigDecimal.valueOf(2500.0));

        paperService.runSundayWeeklyRoutine(true);

        verify(gttGateway, never()).placeGtt(any());
        verify(gttGateway, never()).listActiveGtts();
        verify(localWatcher, times(1)).placeGtt(any());
    }

    @Test
    void testFailsClosedWhenConfiguredBrokerIsNotRegistered() {
        when(gttGateway.getBrokerName()).thenReturn("NOT_A_BROKER");
        service.getPortfolioState().addFill("RELIANCE", 50, BigDecimal.valueOf(2500.0));

        service.runSundayWeeklyRoutine(true);

        verify(gttGateway, never()).placeGtt(any());
        verify(gttGateway, never()).listActiveGtts();
        assertNull(service.getPortfolioState().getLastRunWeek());
    }
}

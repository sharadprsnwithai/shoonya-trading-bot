package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.ZerodhaKiteGttGateway;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarWeeklyGttServiceTest {

    private CarWeeklyGttService service;
    private CarCalculator calculator;
    private CarWeeklyTriggerGenerator triggerGenerator;
    private HistoricalOhlcCacheService ohlcService;
    private ZerodhaKiteGttGateway gttGateway;
    private TelegramService telegramService;

    @BeforeEach
    void setUp() {
        calculator = new CarCalculator(10, 252);
        triggerGenerator = new CarWeeklyTriggerGenerator(new BigDecimal("0.10"));
        ohlcService = mock(HistoricalOhlcCacheService.class);
        gttGateway = mock(ZerodhaKiteGttGateway.class);
        telegramService = mock(TelegramService.class);

        CarWeeklyProperties props = new CarWeeklyProperties();
        props.setTotalCapital(1000000.0);
        props.setNumParts(40);

        service =
                new CarWeeklyGttService(
                        props,
                        calculator,
                        triggerGenerator,
                        ohlcService,
                        List.of(gttGateway),
                        telegramService);
    }

    @Test
    void testSundayRoutineComputesUnitAndScansUniverse() {
        assertEquals(new BigDecimal("25000.00"), service.getPortfolioState().getUnitSize());
        assertEquals(40, service.getPortfolioState().getAvailableUnits());
    }
}

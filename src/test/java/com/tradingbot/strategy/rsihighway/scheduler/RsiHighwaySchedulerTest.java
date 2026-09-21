package com.tradingbot.strategy.rsihighway.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsiHighwaySchedulerTest {

    private RsiHighwaySwingService swingService;
    private RsiHighwayConfig config;
    private RsiHighwayScheduler scheduler;

    @BeforeEach
    void setUp() {
        swingService = mock(RsiHighwaySwingService.class);
        config = new RsiHighwayConfig();
        scheduler = new RsiHighwayScheduler(swingService, config);
    }

    @Test
    void testRunDailyEodScanTriggersSwingService() {
        scheduler.runDailyEodScan();
        verify(swingService, times(1)).evaluateEodScan();
    }

    @Test
    void testRunMorningPlungeCheckTriggersSwingService() {
        scheduler.runMorningPlungeCheck();
        verify(swingService, times(1)).evaluateMorningPlungeCheck();
    }
}

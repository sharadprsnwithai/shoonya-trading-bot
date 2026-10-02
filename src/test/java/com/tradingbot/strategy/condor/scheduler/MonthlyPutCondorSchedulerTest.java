package com.tradingbot.strategy.condor.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.service.MonthlyPutCondorService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MonthlyPutCondorSchedulerTest {

    private MonthlyPutCondorService condorService;
    private ShoonyaMarketDataService marketDataService;
    private MonthlyPutCondorProperties properties;
    private MonthlyPutCondorScheduler scheduler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        condorService = Mockito.mock(MonthlyPutCondorService.class);
        marketDataService = Mockito.mock(ShoonyaMarketDataService.class);
        properties = new MonthlyPutCondorProperties();
        properties.setEnabled(true);
        objectMapper = new ObjectMapper();

        ObjectNode quoteNode = objectMapper.createObjectNode();
        quoteNode.put("lp", "25000.0");
        when(marketDataService.fetchQuote(any(), any())).thenReturn(quoteNode);
        when(marketDataService.resolveToken("NIFTY")).thenReturn("26000");

        scheduler = new MonthlyPutCondorScheduler(condorService, marketDataService, properties);
    }

    @Test
    @DisplayName("Morning entry job invokes evaluateAndEnterCycle when state is IDLE")
    void testMorningEntryJobIdle() {
        when(condorService.getActivePosition()).thenReturn(null);

        scheduler.executeMorningCycleEntry();

        verify(condorService, times(1)).evaluateAndEnterCycle(any());
    }

    @Test
    @DisplayName("Active tick poll job invokes onMarketTick when position is active")
    void testActiveTickPollJob() {
        PutCondorPosition pos = new PutCondorPosition();
        pos.setState(PutCondorState.CONDOR_ACTIVE);
        when(condorService.getActivePosition()).thenReturn(pos);

        scheduler.pollActiveMarketTicks();

        verify(condorService, times(1)).onMarketTick(any(), any());
    }
}

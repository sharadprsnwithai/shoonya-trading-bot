package com.tradingbot.positional.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.model.PositionalTrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShoonyaPositionalExecutionServiceTest {

    private ShoonyaOrderService orderService;
    private ShoonyaOptionChainService optionChainService;
    private PositionalStrategyConfig config;
    private ShoonyaConfig shoonyaConfig;
    private ShoonyaPositionalExecutionService executionService;

    @BeforeEach
    void setUp() {
        orderService = mock(ShoonyaOrderService.class);
        optionChainService = mock(ShoonyaOptionChainService.class);
        config = new PositionalStrategyConfig();
        shoonyaConfig = mock(ShoonyaConfig.class);

        executionService =
                new ShoonyaPositionalExecutionService(
                        orderService, optionChainService, config, shoonyaConfig);
    }

    @Test
    void testFindMonthlyAtmStrike() {
        BigDecimal strike =
                executionService.findMonthlyAtmStrike("NIFTY 50", BigDecimal.valueOf(23443.2));
        assertEquals(BigDecimal.valueOf(23450.0).setScale(2), strike.setScale(2));

        BigDecimal peStrike =
                executionService.findMonthlyAtmStrike("NIFTY 50", BigDecimal.valueOf(23420.0));
        assertEquals(BigDecimal.valueOf(23400.0).setScale(2), peStrike.setScale(2));
    }

    @Test
    void testPaperModeSpreadEntryAndExitExecution() {
        PositionalTrade stagedTrade =
                new PositionalTrade(
                        "POS_1",
                        "NIFTY 50",
                        "BULL_PUT_SPREAD",
                        "PE",
                        BigDecimal.valueOf(24000),
                        "PE",
                        BigDecimal.valueOf(23700),
                        "27-FEB-2025",
                        LocalDate.now().minusDays(4),
                        BigDecimal.valueOf(24011.6),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(23891.6),
                        BigDecimal.valueOf(24572.7),
                        10,
                        650,
                        ExecutionMode.PAPER,
                        "STAGED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        PositionalTrade filled = executionService.executeEntry(stagedTrade);
        assertNotNull(filled);
        assertEquals("OPEN", filled.status());
        assertTrue(filled.sellEntryPremium().doubleValue() > 0);
        assertTrue(filled.buyHedgeEntryPremium().doubleValue() > 0);
        assertTrue(filled.netCredit().doubleValue() > 0);

        // Test Exit Execution
        PositionalTrade closed =
                executionService.executeExit(
                        filled, BigDecimal.valueOf(24572.7), "TARGET_OPPOSITE_BAND");
        assertNotNull(closed);
        assertEquals("CLOSED", closed.status());
        assertEquals("TARGET_OPPOSITE_BAND", closed.exitReason());
        assertNotNull(closed.pnl());
        assertTrue(closed.pnl().doubleValue() > 0);
    }
}

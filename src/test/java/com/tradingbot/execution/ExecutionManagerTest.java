package com.tradingbot.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.order.ShoonyaOrderService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionManagerTest {

    @Mock private ShoonyaOrderService orderService;

    @Mock private ShoonyaOptionChainService optionChainService;

    @Mock private com.tradingbot.telegram.TelegramService telegramService;

    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        executionManager = new ExecutionManager(orderService, optionChainService, telegramService);
    }

    private OptionChainResponse createMockChain(BigDecimal atmStrike) {
        OptionContract atmPe =
                new OptionContract(
                        "NIFTY29SEP26P24000",
                        "74001",
                        "PE",
                        atmStrike,
                        new BigDecimal("100.00"),
                        50000L,
                        10000L,
                        new BigDecimal("99.00"),
                        new BigDecimal("101.00"),
                        new BigDecimal("95.00"));
        OptionContract hedgePe =
                new OptionContract(
                        "NIFTY29SEP26P23850",
                        "74002",
                        "PE",
                        atmStrike.subtract(BigDecimal.valueOf(150)),
                        new BigDecimal("5.00"),
                        20000L,
                        5000L,
                        new BigDecimal("4.80"),
                        new BigDecimal("5.20"),
                        new BigDecimal("4.50"));

        OptionStrike atmStrikeObj = new OptionStrike(atmStrike, true, null, atmPe);
        OptionStrike hedgeStrikeObj =
                new OptionStrike(atmStrike.subtract(BigDecimal.valueOf(150)), false, null, hedgePe);

        return new OptionChainResponse(
                "NIFTY",
                atmStrike,
                atmStrike,
                "NIFTY29SEP26F",
                2,
                50000L,
                70000L,
                1.4,
                List.of(hedgeStrikeObj, atmStrikeObj));
    }

    @Test
    void testExecuteDirectionalOptionSellingSetsHardSlLmt() {
        BigDecimal atm = new BigDecimal("24000");
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean()))
                .thenReturn(createMockChain(atm));

        ActiveSpreadPosition pos =
                executionManager.executeDirectionalOptionSelling(
                        "TEST_STRATEGY", "NIFTY", "PE", atm, 65, true);

        assertThat(pos).isNotNull();
        assertThat(pos.optionType()).isEqualTo("PE");
        assertThat(pos.shortSymbol()).isEqualTo("NIFTY29SEP2624000PE");
        assertThat(pos.hedgeSymbol()).isEqualTo("NIFTY29SEP2623850PE");
        assertThat(pos.shortEntryPremium()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(pos.hedgeEntryPremium()).isEqualByComparingTo(new BigDecimal("5.00"));
        // SL Trigger = 100 * 1.40 = 140.00
        assertThat(pos.slTriggerPrice()).isEqualByComparingTo(new BigDecimal("140.00"));
        // SL Limit = 140 * 1.03 = 144.20
        assertThat(pos.slLimitPrice()).isEqualByComparingTo(new BigDecimal("144.20"));
        assertThat(pos.isClosed()).isFalse();

        // Close position with live quote from option chain
        ActiveSpreadPosition closed =
                executionManager.closeSpreadPosition(pos.tradeId(), "SUPERTREND_FLIP");
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.realizedPnl()).isNotNull();
        // Short: sold @ 100, exited @ 100 -> PnL 0; Hedge: bought @ 5, exited @ 5 -> PnL 0 -> Total
        // realized PnL = 0
        assertThat(closed.realizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testCloseSpreadPositionWithExplicitExitPrices() {
        BigDecimal atm = new BigDecimal("24000");
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean()))
                .thenReturn(createMockChain(atm));

        ActiveSpreadPosition pos =
                executionManager.executeDirectionalOptionSelling(
                        "TEST_STRATEGY", "NIFTY", "PE", atm, 65, true);

        // Explicit short exit at 20.00 (gain of 80/share), hedge exit at 1.00 (loss of 4/share)
        // Quantity = 65. Net PnL = (100 - 20) * 65 + (1 - 5) * 65 = 80*65 - 4*65 = 5200 - 260 =
        // 4940
        ActiveSpreadPosition closed =
                executionManager.closeSpreadPosition(
                        pos.tradeId(),
                        "TARGET_HIT",
                        new BigDecimal("20.00"),
                        new BigDecimal("1.00"));
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.realizedPnl()).isEqualByComparingTo(new BigDecimal("4940.00"));
    }

    @Test
    void testExecutionModeToggle() {
        assertThat(executionManager.getExecutionMode()).isEqualTo(ExecutionMode.PAPER);

        executionManager.setExecutionMode(ExecutionMode.LIVE);
        assertThat(executionManager.getExecutionMode()).isEqualTo(ExecutionMode.LIVE);

        executionManager.setExecutionMode(ExecutionMode.PAPER);
        assertThat(executionManager.getExecutionMode()).isEqualTo(ExecutionMode.PAPER);
    }

    @Test
    void testLiveMode_AbortsWhenHedgeOrderFails() {
        executionManager.setExecutionMode(ExecutionMode.LIVE);
        BigDecimal atm = new BigDecimal("24000");
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean()))
                .thenReturn(createMockChain(atm));

        // Mock hedge order failure
        when(orderService.placeOrder(any()))
                .thenReturn(
                        com.tradingbot.model.order.OrderResponse.failure(
                                null, "Margin Insufficient for Hedge"));

        ActiveSpreadPosition pos =
                executionManager.executeDirectionalOptionSelling(
                        "TEST_STRATEGY", "NIFTY", "PE", atm, 65, true);

        assertThat(pos).isNull();
        assertThat(executionManager.getOpenPositions()).isEmpty();
    }

    @Test
    void testLiveMode_RollsBackHedgeWhenShortFails() {
        executionManager.setExecutionMode(ExecutionMode.LIVE);
        BigDecimal atm = new BigDecimal("24000");
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean()))
                .thenReturn(createMockChain(atm));

        // 1. Hedge succeeds, 2. Short fails, 3. Rollback hedge succeeds
        when(orderService.placeOrder(any()))
                .thenReturn(
                        com.tradingbot.model.order.OrderResponse.success(
                                "HEDGE_ORD_1", null, "Success"))
                .thenReturn(
                        com.tradingbot.model.order.OrderResponse.failure(
                                null, "RMS: Short selling disabled"))
                .thenReturn(
                        com.tradingbot.model.order.OrderResponse.success(
                                "ROLLBACK_ORD_1", null, "Rollback success"));

        ActiveSpreadPosition pos =
                executionManager.executeDirectionalOptionSelling(
                        "TEST_STRATEGY", "NIFTY", "PE", atm, 65, true);

        assertThat(pos).isNull();
        assertThat(executionManager.getOpenPositions()).isEmpty();
    }
}

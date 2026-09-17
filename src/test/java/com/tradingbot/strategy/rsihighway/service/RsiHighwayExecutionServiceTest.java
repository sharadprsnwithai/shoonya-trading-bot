package com.tradingbot.strategy.rsihighway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayPosition;
import com.tradingbot.strategy.rsihighway.model.RsiHighwaySignal;
import com.tradingbot.strategy.rsihighway.model.RsiHighwaySignalType;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayTranche;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsiHighwayExecutionServiceTest {

    private ShoonyaOrderService orderService;
    private RsiHighwayConfig config;
    private RsiHighwayExecutionService executionService;

    @BeforeEach
    void setUp() {
        orderService = mock(ShoonyaOrderService.class);
        config = new RsiHighwayConfig();
        config.setPaperTrading(true);
        config.setRiskPerTradePercent(1.0);
        config.setMaxCapitalPerStockPercent(10.0);
        config.setPaperCapital(1000000.0);
        executionService = new RsiHighwayExecutionService(orderService, config);
    }

    @Test
    void testPositionSizingFormulaCapitalCapped() {
        // Account Capital: 1,000,000
        // RPT: 1.0% = 10,000 max risk
        // Max Capital per Stock: 10% = 100,000 max allocation
        // Entry: 1000, SL: 950 (Risk = 50/share) -> By Risk: 10000 / 50 = 200 shares -> Cost = 200,000 (exceeds 100,000 cap)
        // Expected shares capped by 100,000 / 1000 = 100 shares
        int qty = executionService.calculatePositionSize(1000.0, 950.0, 1000000.0, 1.0, 10.0);
        assertThat(qty).isEqualTo(100);
    }

    @Test
    void testPositionSizingRiskConstrained() {
        // Entry: 1000, SL: 800 (Risk = 200/share)
        // By Risk: 10000 / 200 = 50 shares -> Cost = 50,000 (below 100,000 cap)
        int qty = executionService.calculatePositionSize(1000.0, 800.0, 1000000.0, 1.0, 10.0);
        assertThat(qty).isEqualTo(50);
    }

    @Test
    void testPositionSizingZeroWhenCapitalInsufficient() {
        // Account capital = 5,000. Max allocation 10% = 500. Stock price = 10,000.
        // Cannot afford even 1 share within budget -> returns 0
        int qty = executionService.calculatePositionSize(10000.0, 9500.0, 5000.0, 1.0, 10.0);
        assertThat(qty).isEqualTo(0);
    }

    @Test
    void testExecuteEntrySignalReturnsEmptyWhenCapitalInsufficient() {
        RsiHighwaySignal signal = new RsiHighwaySignal(
                "MRF",
                RsiHighwaySignalType.INITIAL_ENTRY,
                120000.0,
                115000.0,
                1,
                65.0,
                62.0,
                52.0,
                20.0,
                PriceActionPattern.BULLISH_ENGULFING,
                "Initial Setup",
                Instant.now()
        );

        // Account capital 10,000 is way below MRF share price 120,000
        Optional<RsiHighwayTranche> tranche = executionService.executeEntrySignal(signal, 10000.0);
        assertThat(tranche).isEmpty();
    }

    @Test
    void testExecutePaperEntrySignal() {
        RsiHighwaySignal signal = new RsiHighwaySignal(
                "INFY",
                RsiHighwaySignalType.INITIAL_ENTRY,
                1500.0,
                1450.0,
                1,
                65.0,
                62.0,
                52.0,
                20.0,
                PriceActionPattern.BULLISH_ENGULFING,
                "Initial Setup",
                Instant.now()
        );

        Optional<RsiHighwayTranche> tranche = executionService.executeEntrySignal(signal, 1000000.0);
        assertThat(tranche).isPresent();
        assertThat(tranche.get().trancheNumber()).isEqualTo(1);
        assertThat(tranche.get().entryPrice()).isEqualTo(1500.0);
        assertThat(tranche.get().quantity()).isGreaterThan(0);
    }

    @Test
    void testExecuteExitPosition() {
        RsiHighwayPosition position = new RsiHighwayPosition("TCS", "NSE", 3500.0, 3300.0);
        position.addTranche(new RsiHighwayTranche(1, 10, 3500.0, Instant.now(), "ORD_01"));

        boolean exited = executionService.executeExit(position, 3450.0, "Daily RSI < 50 Close");
        assertThat(exited).isTrue();
        assertThat(position.isActive()).isFalse();
    }
}

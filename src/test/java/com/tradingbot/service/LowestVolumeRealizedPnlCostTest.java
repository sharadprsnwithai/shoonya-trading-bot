package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PNL-5: booked P&L is gross of brokerage, stamp duty and slippage — the loss breaker and /status
 * must operate on the NET figure (estimated per-trade cost, configurable).
 */
class LowestVolumeRealizedPnlCostTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant TODAY = Instant.parse("2026-10-13T06:00:00Z"); // 11:30 IST

    private LowestVolumeReversalService service;

    @BeforeEach
    void setUp() {
        service =
                new LowestVolumeReversalService(
                        mock(ShoonyaMarketDataService.class),
                        new TechnicalAnalysisService(),
                        null,
                        mock(ShoonyaConfig.class),
                        null);
        service.setClock(Clock.fixed(TODAY, IST));
        service.setEstimatedCostPerTradeRupees(100.0);
    }

    private static LowestVolumePaperPosition closedFuturesTrade(
            String id, double entry, double exit, int qty) {
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        id,
                        "TCS",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.FULL_TARGET_1_2,
                        "TCS FUT",
                        175,
                        1,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(entry),
                        BigDecimal.valueOf(entry * 0.98),
                        BigDecimal.valueOf(entry * 1.04),
                        qty,
                        BigDecimal.valueOf(1000),
                        Instant.parse("2026-10-13T05:00:00Z"));
        pos.closeFullFutures(BigDecimal.valueOf(exit), "TARGET_1_2_FULL_EXIT", TODAY);
        return pos;
    }

    @Test
    @DisplayName("PNL-5: today's realized P&L is net of estimated costs for each closed trade")
    void realizedPnlDeductsEstimatedPerTradeCosts() {
        LowestVolumePaperPosition win = closedFuturesTrade("T1", 1000.0, 1002.0, 150);
        LowestVolumePaperPosition loss = closedFuturesTrade("T2", 1000.0, 999.0, 100);
        service.getTradeHistory().add(win);
        service.getTradeHistory().add(loss);

        double gross =
                win.getTotalRealizedPnl().doubleValue() + loss.getTotalRealizedPnl().doubleValue();

        // ₹200 gross − (2 closed trades × ₹100) = ₹0 net.
        assertThat(service.calculateTodayRealizedPnl()).isEqualTo(gross - 200.0);
    }

    @Test
    @DisplayName("PNL-5: a zero cost knob keeps the historical gross behaviour")
    void zeroCostKeepsGrossPnl() {
        service.setEstimatedCostPerTradeRupees(0.0);
        LowestVolumePaperPosition win = closedFuturesTrade("T1", 1000.0, 1002.0, 150);
        service.getTradeHistory().add(win);

        assertThat(service.calculateTodayRealizedPnl())
                .isEqualTo(win.getTotalRealizedPnl().doubleValue());
    }
}

package com.tradingbot.telegram;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.strategy.RsiCrossoverPosition;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramServiceTest {

    private ShoonyaConfig config;
    private TelegramService telegramService;

    @BeforeEach
    void setUp() {
        config = mock(ShoonyaConfig.class);
        when(config.isTelegramEnabled()).thenReturn(false); // Do not send live network requests in unit tests
        telegramService = new TelegramService(config);
    }

    @Test
    void testSendRsiCrossoverAlertsDisabled() {
        RsiCrossoverPosition pos =
                new RsiCrossoverPosition(
                        "TRD_1",
                        "NIFTY24OCT22500CE",
                        "CE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(150.0),
                        65,
                        Instant.now());

        // Should not throw when disabled
        telegramService.sendRsiCrossoverEntryAlert(pos, 58.5, 52.0, 48.0, 51.5);

        pos.close(BigDecimal.valueOf(180.0), "RSI_REVERSAL", Instant.now());
        telegramService.sendRsiCrossoverExitAlert(pos, "RSI_REVERSAL");
    }

    @Test
    void testSendRsiCrossoverHedgedAlertsDisabled() {
        RsiCrossoverPosition hedgedPos =
                new RsiCrossoverPosition(
                        "TRD_HEDGE_1",
                        "NIFTY24OCT22500PE",
                        "SELL",
                        "PE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(150.0),
                        65,
                        Instant.now(),
                        true,
                        "NIFTY24OCT22050PE",
                        BigDecimal.valueOf(22050),
                        BigDecimal.valueOf(12.0),
                        65);

        telegramService.sendRsiCrossoverEntryAlert(hedgedPos, 58.5, 52.0, 48.0, 51.5);

        hedgedPos.close(BigDecimal.valueOf(60.0), BigDecimal.valueOf(2.0), "TARGET_PROFIT_HIT", Instant.now());
        telegramService.sendRsiCrossoverExitAlert(hedgedPos, "TARGET_PROFIT_HIT");
    }
}

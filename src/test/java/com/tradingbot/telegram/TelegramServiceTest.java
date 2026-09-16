package com.tradingbot.telegram;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.config.ShoonyaConfig;
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
        when(config.isTelegramEnabled())
                .thenReturn(false); // Do not send live network requests in unit tests
        telegramService = new TelegramService(config);
    }

    @Test
    void testSendLvrSetupArmedAlertDisabled() {
        com.tradingbot.model.strategy.LowestVolumeSetup setup =
                new com.tradingbot.model.strategy.LowestVolumeSetup(
                        "RELIANCE", com.tradingbot.model.strategy.LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                BigDecimal.valueOf(2500.0),
                BigDecimal.valueOf(2490.0),
                BigDecimal.valueOf(2520.0));
        setup.setAtr14(12.5);

        // Should not throw when disabled
        telegramService.sendLvrSetupArmedAlert(setup, 4, BigDecimal.valueOf(1000));
    }

    @Test
    void testSendLvrTradeEntryAlertDisabled() {
        com.tradingbot.model.strategy.LowestVolumeSetup setup =
                new com.tradingbot.model.strategy.LowestVolumeSetup(
                        "RELIANCE", com.tradingbot.model.strategy.LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                BigDecimal.valueOf(2500.0),
                BigDecimal.valueOf(2490.0),
                BigDecimal.valueOf(2520.0));

        com.tradingbot.model.strategy.LowestVolumePaperPosition pos =
                new com.tradingbot.model.strategy.LowestVolumePaperPosition(
                        "LVR_1",
                        "RELIANCE",
                        "CE",
                        "RELIANCE25OCT2500CE",
                        BigDecimal.valueOf(2500),
                        250,
                        4,
                        com.tradingbot.model.strategy.LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(45.0),
                        BigDecimal.valueOf(2500.0),
                        BigDecimal.valueOf(2490.0),
                        BigDecimal.valueOf(2520.0),
                        1000,
                        BigDecimal.valueOf(1000),
                        Instant.now());

        // Should not throw when disabled
        telegramService.sendLvrTradeEntryAlert(pos, setup);
    }

    @Test
    void testSendLvrScanRetryAlert() {
        telegramService.sendLvrScanRetryAlert(true, java.time.LocalTime.of(9, 30), 0);
        telegramService.sendLvrScanRetryAlert(false, null, 2);
    }
}

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

    @Test
    void testEscapeMarkdown() {
        org.assertj.core.api.Assertions.assertThat(TelegramService.escapeMarkdown("SPOT_SL_HIT"))
                .isEqualTo("SPOT\\_SL\\_HIT");
        org.assertj.core.api.Assertions.assertThat(
                        TelegramService.escapeMarkdown("TRD_1*test_2`3[4"))
                .isEqualTo("TRD\\_1\\*test\\_2\\`3\\[4");
        org.assertj.core.api.Assertions.assertThat(TelegramService.escapeMarkdown(null))
                .isEqualTo("");
    }

    @Test
    void testSendTextMessageWithChatIdWhenDisabled() {
        // Should gracefully handle target chatId when disabled
        telegramService.sendTextMessage("987654321", "Test custom chat alert");
        telegramService.sendAlert("987654321", "Test custom alert");
    }
}

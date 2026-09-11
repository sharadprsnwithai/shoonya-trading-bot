package com.tradingbot.telegram;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void testSendRsiCrossoverHedgedAlertsEnabledMessageFormat() {
        ShoonyaConfig activeConfig = mock(ShoonyaConfig.class);
        when(activeConfig.isTelegramEnabled()).thenReturn(true);
        when(activeConfig.getTelegramBotToken()).thenReturn("dummy-token");
        when(activeConfig.getTelegramChatId()).thenReturn("dummy-chat");

        java.util.List<String> messages = new java.util.ArrayList<>();
        TelegramService capturingService =
                new TelegramService(activeConfig) {
                    @Override
                    public void sendAsync(String text) {
                        messages.add(text);
                    }
                };

        RsiCrossoverPosition hedgedPos =
                new RsiCrossoverPosition(
                        "TRD_HEDGE_1",
                        "NIFTY24OCT24850PE",
                        "SELL",
                        "PE",
                        BigDecimal.valueOf(24850),
                        BigDecimal.valueOf(150.0),
                        65,
                        Instant.now(),
                        true,
                        "NIFTY24OCT24350PE",
                        BigDecimal.valueOf(24350),
                        BigDecimal.valueOf(12.0),
                        65);

        capturingService.sendRsiCrossoverEntryAlert(hedgedPos, 58.5, 52.0, 48.0, 51.5);

        assertThat(messages).hasSize(1);
        String entryMsg = messages.get(0);
        assertThat(entryMsg).contains("SELL *NIFTY 24850 PE* (`NIFTY24OCT24850PE`) @ ₹150.00");
        assertThat(entryMsg).contains("BUY *NIFTY 24350 PE* (`NIFTY24OCT24350PE`) @ ₹12.00");
        assertThat(entryMsg).contains("BULL PUT SPREAD (2% OTM HEDGE)");

        hedgedPos.close(BigDecimal.valueOf(60.0), BigDecimal.valueOf(2.0), "TARGET_PROFIT_HIT", Instant.now());
        capturingService.sendRsiCrossoverExitAlert(hedgedPos, "TARGET_PROFIT_HIT");

        assertThat(messages).hasSize(2);
        String exitMsg = messages.get(1);
        assertThat(exitMsg).contains("Main Sell Leg (NIFTY 24850 PE)");
        assertThat(exitMsg).contains("Hedge Buy Leg (NIFTY 24350 PE)");
        assertThat(exitMsg).contains("TARGET_PROFIT_HIT");
    }

    @Test
    void testSendDailyWmaAlertsDisabled() {
        com.tradingbot.model.strategy.DailyWmaPosition pos =
                new com.tradingbot.model.strategy.DailyWmaPosition(
                        "WMA_1",
                        "NIFTY",
                        "SELL",
                        "PE",
                        "BULLISH",
                        java.time.LocalDate.of(2026, 9, 10),
                        Instant.now(),
                        BigDecimal.valueOf(24850),
                        BigDecimal.valueOf(24620),
                        java.time.LocalDate.of(2026, 9, 24),
                        "NIFTY24SEP24100PE",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(95.0),
                        0.22,
                        65,
                        "NIFTY24SEP23600PE",
                        BigDecimal.valueOf(23600),
                        BigDecimal.valueOf(12.0),
                        65,
                        BigDecimal.valueOf(83.0),
                        BigDecimal.valueOf(190.0),
                        0);

        telegramService.sendDailyWmaEntryAlert(pos, 24850.0, 24620.0);
        telegramService.sendDailyWmaStopLossAlert(pos, 195.0);

        pos.close(BigDecimal.valueOf(20.0), BigDecimal.valueOf(2.0), "EXPIRY_SQUARE_OFF", Instant.now(), java.time.LocalDate.of(2026, 9, 24), BigDecimal.valueOf(25200));
        telegramService.sendDailyWmaExitAlert(pos, "EXPIRY_SQUARE_OFF");
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
}

package com.tradingbot.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.service.BollingerHaPositionalService;
import java.net.http.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramBotCommandListenerTest {

    private BollingerHaPositionalService positionalService;
    private PositionalStrategyConfig positionalConfig;
    private TelegramService telegramService;
    private ShoonyaConfig shoonyaConfig;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private TelegramBotCommandListener commandListener;

    @BeforeEach
    void setUp() {
        positionalService = mock(BollingerHaPositionalService.class);
        positionalConfig = new PositionalStrategyConfig();
        telegramService = mock(TelegramService.class);
        shoonyaConfig = mock(ShoonyaConfig.class);
        when(shoonyaConfig.isTelegramEnabled()).thenReturn(true);
        when(shoonyaConfig.getTelegramBotToken()).thenReturn("MOCK_BOT_TOKEN");
        when(shoonyaConfig.getTelegramChatId()).thenReturn("12345678");

        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);

        commandListener =
                new TelegramBotCommandListener(
                        positionalService,
                        positionalConfig,
                        telegramService,
                        shoonyaConfig,
                        objectMapper,
                        httpClient);
    }

    @Test
    void testHandleStatusCommand() {
        when(positionalService.getSummaryStatus()).thenReturn("📊 Status: FLAT");

        String response = commandListener.processCommand("/status");
        assertNotNull(response);
        assertTrue(response.contains("Status: FLAT"));
    }

    @Test
    void testHandleScanCommand() {
        doNothing().when(positionalService).scanAndEvaluate();
        when(positionalService.getSummaryStatus()).thenReturn("📊 Status: ALERT_PENDING");

        String response = commandListener.processCommand("/scan");
        verify(positionalService, times(1)).scanAndEvaluate();
        assertTrue(response.contains("Scan completed"));
    }

    @Test
    void testHandleApproveAndRejectCommands() {
        when(positionalService.approveStagedTrade()).thenReturn(true);
        String approveResp = commandListener.processCommand("/approve");
        assertTrue(approveResp.contains("approved"));

        when(positionalService.rejectStagedTrade()).thenReturn(true);
        String rejectResp = commandListener.processCommand("/reject");
        assertTrue(rejectResp.contains("rejected"));
    }

    @Test
    void testHandleModeToggleCommand() {
        String resp = commandListener.processCommand("/mode AUTO");
        assertTrue(resp.contains("AUTO"));
        assertEquals("AUTO", positionalConfig.getExecutionMode());

        resp = commandListener.processCommand("/mode MANUAL_CONFIRMATION");
        assertTrue(resp.contains("MANUAL_CONFIRMATION"));
        assertEquals("MANUAL_CONFIRMATION", positionalConfig.getExecutionMode());
    }

    @Test
    void testHandleExitCommand() {
        doNothing().when(positionalService).forceExitCurrentPosition(anyString());
        String resp = commandListener.processCommand("/exit");
        verify(positionalService, times(1)).forceExitCurrentPosition("TELEGRAM_MANUAL_EXIT");
        assertTrue(resp.contains("Exit signal dispatched"));
    }
}

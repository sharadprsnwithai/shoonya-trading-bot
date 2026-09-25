package com.tradingbot.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumeSectorState;
import com.tradingbot.service.LowestVolumeReversalService;
import java.net.http.HttpClient;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramBotCommandListenerTest {

    private LowestVolumeReversalService lvrService;
    private TelegramService telegramService;
    private ShoonyaConfig shoonyaConfig;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private TelegramBotCommandListener commandListener;

    @BeforeEach
    void setUp() {
        lvrService = mock(LowestVolumeReversalService.class);
        telegramService = mock(TelegramService.class);
        shoonyaConfig = mock(ShoonyaConfig.class);
        when(shoonyaConfig.isTelegramEnabled()).thenReturn(true);
        when(shoonyaConfig.getTelegramBotToken()).thenReturn("MOCK_BOT_TOKEN");
        when(shoonyaConfig.getTelegramChatId()).thenReturn("12345678");

        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);

        commandListener =
                new TelegramBotCommandListener(
                        lvrService, telegramService, shoonyaConfig, objectMapper, httpClient);
    }

    @Test
    void testHandleStatusCommand() {
        when(lvrService.getSectorState())
                .thenReturn(
                        new LowestVolumeSectorState(
                                30,
                                20,
                                LowestVolumeDirection.LONG,
                                "NIFTY PHARMA",
                                1.25,
                                List.of("SUNPHARMA", "CIPLA")));
        when(lvrService.getActiveSetups()).thenReturn(Collections.emptyMap());
        when(lvrService.getOpenPositions()).thenReturn(Collections.emptyMap());
        when(lvrService.getTradeHistory()).thenReturn(Collections.emptyList());

        String response = commandListener.processCommand("/status");
        assertNotNull(response);
        assertTrue(response.contains("NIFTY PHARMA"));
        assertTrue(response.contains("SUNPHARMA"));
    }

    @Test
    void testHandleScanCommand() {
        when(lvrService.getSectorState()).thenReturn(LowestVolumeSectorState.empty());
        when(lvrService.getActiveSetups()).thenReturn(Collections.emptyMap());
        when(lvrService.getOpenPositions()).thenReturn(Collections.emptyMap());
        when(lvrService.getTradeHistory()).thenReturn(Collections.emptyList());

        String response = commandListener.processCommand("/scan");
        verify(lvrService, times(1)).runCycle();
        assertTrue(response.contains("LVR 5-Minute Strategy Cycle executed"));
    }

    @Test
    void testHandleExitCommand() {
        String resp = commandListener.processCommand("/exit");
        verify(lvrService, times(1)).executeHardExit(any());
        assertTrue(resp.contains("Manual hard exit executed"));
    }

    @Test
    void testHandleResetCommand() {
        String resp = commandListener.processCommand("/reset");
        verify(lvrService, times(1)).resetDaily();
        assertTrue(resp.contains("LVR daily session state reset"));
    }

    @Test
    void testHandleHelpCommand() {
        String resp = commandListener.processCommand("/help");
        assertTrue(resp.contains("Lowest Volume Reversal Bot Commands"));
    }
}

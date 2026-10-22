package com.tradingbot.telegram;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.strategy.ttcstraddle.model.StraddleDailyState;
import com.tradingbot.strategy.ttcstraddle.service.IntradayStraddleService;
import java.net.http.HttpClient;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelegramStraddleCommandTest {

    private LowestVolumeReversalService lvrService;
    private TelegramService telegramService;
    private ShoonyaConfig shoonyaConfig;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        lvrService = mock(LowestVolumeReversalService.class);
        telegramService = mock(TelegramService.class);
        shoonyaConfig = mock(ShoonyaConfig.class);
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
    }

    @Test
    @DisplayName("/straddle_status returns current active instrument and state")
    void testStraddleStatus() {
        IntradayStraddleService straddleService = mock(IntradayStraddleService.class);
        StraddleDailyState state = new StraddleDailyState(LocalDate.of(2026, 10, 19), "NIFTY");
        when(straddleService.getState()).thenReturn(state);

        TelegramBotCommandListener listener =
                new TelegramBotCommandListener(
                        lvrService,
                        null,
                        null,
                        null,
                        null,
                        straddleService,
                        telegramService,
                        shoonyaConfig,
                        objectMapper,
                        httpClient);

        String response = listener.processCommand("/straddle_status");
        assertNotNull(response);
        assertTrue(response.contains("STRADDLE"));
        assertTrue(response.contains("NIFTY"));
    }

    @Test
    @DisplayName("/straddle_exit triggers emergency hard square-off")
    void testStraddleExit() {
        IntradayStraddleService straddleService = mock(IntradayStraddleService.class);

        TelegramBotCommandListener listener =
                new TelegramBotCommandListener(
                        lvrService,
                        null,
                        null,
                        null,
                        null,
                        straddleService,
                        telegramService,
                        shoonyaConfig,
                        objectMapper,
                        httpClient);

        String response = listener.processCommand("/straddle_exit");
        assertNotNull(response);
        assertTrue(response.contains("square-off"));
        verify(straddleService, times(1)).executeHardSquareOff(any());
    }
}

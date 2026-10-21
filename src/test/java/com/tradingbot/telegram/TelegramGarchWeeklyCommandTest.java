package com.tradingbot.telegram;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeReport;
import com.tradingbot.strategy.monthlyrange.service.GarchRangeService;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the /garch_weekly Telegram command that forces the weekly GARCH range routine. */
class TelegramGarchWeeklyCommandTest {

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
    @DisplayName("/garch_weekly runs the weekly GARCH routine and replies with its summary")
    void testGarchWeeklyRunsRoutine() {
        GarchRangeService garchService = mock(GarchRangeService.class);
        var report =
                new GarchRangeReport(
                        Instant.now(),
                        LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 27),
                        5,
                        List.of(),
                        "Weekly range forecast completed for 2026-10-27 (1 succeeded, 0 failed).");
        when(garchService.generateWeeklyReport()).thenReturn(report);

        TelegramBotCommandListener listener =
                new TelegramBotCommandListener(
                        lvrService,
                        null,
                        null,
                        null,
                        garchService,
                        telegramService,
                        shoonyaConfig,
                        objectMapper,
                        httpClient);

        String response = listener.processCommand("/garch_weekly");

        verify(garchService, times(1)).generateWeeklyReport();
        assertTrue(
                response.contains("Weekly range forecast completed for 2026-10-27"),
                "expected the report summary as reply, got: " + response);
    }

    @Test
    @DisplayName("/garch_weekly warns instead of failing when the GARCH service is not active")
    void testGarchWeeklyWithoutService() {
        TelegramBotCommandListener listener =
                new TelegramBotCommandListener(
                        lvrService,
                        null,
                        null,
                        null,
                        null,
                        telegramService,
                        shoonyaConfig,
                        objectMapper,
                        httpClient);

        String response = listener.processCommand("/garch_weekly");

        assertTrue(response.contains("not active"), "got: " + response);
    }
}

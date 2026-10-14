package com.tradingbot.strategy.silverorb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tradingbot.strategy.silverorb.config.SilverOrbProperties;
import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbState;
import com.tradingbot.strategy.silverorb.model.SilverOrbTrade;
import com.tradingbot.strategy.silverorb.persistence.SilverOrbStateStore;
import com.tradingbot.strategy.silverorb.service.SilverOrbService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

/** Monitoring endpoint tests for {@link SilverOrbController}: state view and manual cycle run. */
class SilverOrbControllerTest {

    @TempDir Path tempDir;

    private SilverOrbService service;
    private SilverOrbProperties props;
    private SilverOrbController controller;

    @BeforeEach
    void setUp() {
        service = mock(SilverOrbService.class);
        props = new SilverOrbProperties();
        props.setStateFile(tempDir.resolve("silver-orb-state.json").toString());
        controller = new SilverOrbController(service, props);
    }

    @Test
    @DisplayName("State endpoint reports the placeholder before any cycle has run")
    void stateWithoutFileReturnsPlaceholder() {
        ResponseEntity<Map<String, Object>> resp = controller.state();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody())
                .containsEntry("enabled", true)
                .containsEntry("schedulerEnabled", true)
                .containsEntry("runUntil", LocalDate.of(2026, 11, 14))
                .containsEntry("contract", "MCX:SILVER04DEC26 (token 495214)")
                .containsEntry("sizing", "1 lot(s) x 30 kg")
                .containsEntry("state", "no state yet (no cycle has run)");
    }

    @Test
    @DisplayName("State endpoint surfaces the ledger and today's session once state exists")
    void stateWithFileReturnsLedgerAndSession() throws IOException {
        SilverOrbState state = new SilverOrbState();
        SilverOrbTrade win = new SilverOrbTrade();
        win.side = "LONG";
        win.entryTime = LocalDateTime.of(2026, 10, 14, 18, 30);
        win.entryPrice = 97;
        win.settle(LocalDateTime.of(2026, 10, 14, 19, 0), 99.425, "TARGET", 7.0, 10.0, 72.75);
        state.trades.add(win);
        SilverOrbDayState day = new SilverOrbDayState();
        day.date = LocalDate.of(2026, 10, 14);
        day.orbHigh = 96;
        day.orbLow = 94;
        day.orbBarCount = 6;
        day.bias = "LONG";
        day.dayComplete = true;
        day.trade = win;
        state.currentDay = day;
        SilverOrbStateStore.save(Path.of(props.getStateFile()), state);

        ResponseEntity<Map<String, Object>> resp = controller.state();

        assertThat(resp.getBody())
                .containsEntry("totalPnlRs", 72.75)
                .containsEntry("trades", 1)
                .containsEntry("winRatePct", 100.0)
                .containsEntry("finalSummarySent", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> today = (Map<String, Object>) resp.getBody().get("today");
        assertThat(today)
                .containsEntry("date", LocalDate.of(2026, 10, 14))
                .containsEntry("orbBarCount", 6)
                .containsEntry("bias", "LONG");
    }

    @Test
    @DisplayName("Run-now endpoint executes exactly one cycle")
    void runNowTriggersCycle() {
        ResponseEntity<Map<String, String>> resp = controller.runNow();

        verify(service, times(1)).runCycle();
        assertThat(resp.getBody()).containsEntry("status", "cycle executed");
    }
}

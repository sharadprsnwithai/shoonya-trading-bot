package com.tradingbot.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.repository.SqliteBackupService;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class HistoricalOhlcControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private HistoricalOhlcCacheService cacheService;
    @MockBean private SqliteBackupService backupService;

    @Test
    void testGetOhlcStatus() throws Exception {
        when(cacheService.getCachedSymbolCount()).thenReturn(520);
        when(cacheService.isCacheValidForToday()).thenReturn(true);
        when(cacheService.getLastUpdated()).thenReturn(Instant.parse("2026-09-18T08:00:00Z"));
        when(cacheService.getStateFilePath()).thenReturn("data/historical_ohlc.json");

        mockMvc.perform(get("/api/v1/historical-ohlc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cachedSymbols").value(520))
                .andExpect(jsonPath("$.cacheValidForToday").value(true))
                .andExpect(jsonPath("$.stateFilePath").value("data/historical_ohlc.json"));
    }

    @Test
    void testTriggerSyncAll() throws Exception {
        when(cacheService.syncAll(true)).thenReturn(520);

        mockMvc.perform(post("/api/v1/historical-ohlc/sync?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.symbolsUpdated").value(520));

        verify(cacheService, times(1)).syncAll(true);
    }

    @Test
    void testTriggerSyncSingleSymbol() throws Exception {
        when(cacheService.syncSymbol("RELIANCE", 2)).thenReturn(true);

        mockMvc.perform(post("/api/v1/historical-ohlc/sync?symbol=RELIANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.symbol").value("RELIANCE"));

        verify(cacheService, times(1)).syncSymbol("RELIANCE", 2);
    }

    @Test
    void testTriggerBackupEndpoint() throws Exception {
        File dummyBackup = new File("data/backups/trading_bot_backup_2026-09-20.db");
        when(backupService.createDailyBackup()).thenReturn(Optional.of(dummyBackup));

        mockMvc.perform(post("/api/v1/historical-ohlc/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.backupFile").value("trading_bot_backup_2026-09-20.db"));
    }

    @Test
    void testListBackupsEndpoint() throws Exception {
        File dummyBackup = new File("data/backups/trading_bot_backup_2026-09-20.db");
        when(backupService.getAvailableBackups()).thenReturn(List.of(dummyBackup));

        mockMvc.perform(get("/api/v1/historical-ohlc/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.backups[0].name").value("trading_bot_backup_2026-09-20.db"));
    }
}

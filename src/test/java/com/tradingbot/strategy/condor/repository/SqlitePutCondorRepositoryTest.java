package com.tradingbot.strategy.condor.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.strategy.condor.model.PutCondorCycleHistory;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePutCondorRepositoryTest {

    @TempDir File tempDir;

    private SqlitePutCondorRepository repository;
    private File dbFile;

    @BeforeEach
    void setUp() {
        dbFile = new File(tempDir, "test_trading_bot.db");
        repository = new SqlitePutCondorRepository(dbFile.getAbsolutePath());
        repository.init();
    }

    @Test
    @DisplayName("Save and load active PutCondorPosition from SQLite")
    void testSaveAndLoadActivePosition() {
        PutCondorPosition pos = new PutCondorPosition();
        pos.setState(PutCondorState.CONDOR_ACTIVE);
        pos.setCycleExpiryDate(LocalDate.of(2026, 10, 29));
        pos.setEntrySpotPrice(BigDecimal.valueOf(25000));
        pos.setLots(50);
        pos.setLotSize(65);
        pos.setK1BuyStrike(24800);
        pos.setK2SellStrike(24600);
        pos.setK3SellStrike(24400);
        pos.setK4BuyStrike(24200);
        pos.calculateAndSetInitialDebit();

        repository.saveActivePosition(pos);

        Optional<PutCondorPosition> loadedOpt = repository.loadActivePosition();
        assertTrue(loadedOpt.isPresent(), "Active position should be present");

        PutCondorPosition loaded = loadedOpt.get();
        assertEquals(PutCondorState.CONDOR_ACTIVE, loaded.getState());
        assertEquals(24800, loaded.getK1BuyStrike());
        assertEquals(50, loaded.getLots());

        // Clear active position
        repository.clearActivePosition();
        Optional<PutCondorPosition> clearedOpt = repository.loadActivePosition();
        assertFalse(clearedOpt.isPresent(), "Active position should be cleared");
    }

    @Test
    @DisplayName("Save and fetch historical completed cycle")
    void testSaveAndFetchHistory() {
        PutCondorCycleHistory history = new PutCondorCycleHistory();
        history.setCycleMonth("OCT 2026");
        history.setEntryDate(LocalDate.of(2026, 10, 1));
        history.setExitDate(LocalDate.of(2026, 10, 29));
        history.setEntrySpot(BigDecimal.valueOf(25000));
        history.setExitSpot(BigDecimal.valueOf(25400));
        history.setLots(50);
        history.setTotalQuantity(3250);
        history.setInitialNetDebitRs(BigDecimal.valueOf(68000));
        history.setRealizedPnlRs(BigDecimal.valueOf(312000));
        history.setRoiPct(BigDecimal.valueOf(6.24));
        history.setAdjustmentsSummary("UpSpread");
        history.setExitReason("TARGET_PROFIT_HIT");

        repository.saveCycleHistory(history);

        List<PutCondorCycleHistory> historyList = repository.getHistory(10);
        assertEquals(1, historyList.size());
        assertEquals("OCT 2026", historyList.get(0).getCycleMonth());
        assertEquals(
                0, BigDecimal.valueOf(312000).compareTo(historyList.get(0).getRealizedPnlRs()));
    }
}

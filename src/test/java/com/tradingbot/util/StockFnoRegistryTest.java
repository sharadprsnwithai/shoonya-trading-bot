package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockFnoRegistryTest {

    @Test
    void testSubscribedSymbolsContainNiftyAndAll29Stocks() {
        List<String> symbols = StockFnoRegistry.getAllSubscribedSymbols();
        assertThat(symbols).hasSize(30);
        assertThat(symbols)
                .contains(
                        "NIFTY50",
                        "ABB",
                        "ADANIENSOL",
                        "ADANIGREEN",
                        "ADANIPOWER",
                        "ABCAPITAL",
                        "BSE",
                        "BHARATFORG",
                        "BHEL",
                        "CGPOWER",
                        "CUMMINSIND",
                        "FEDERALBNK",
                        "GVT&D",
                        "GLENMARK",
                        "HINDALCO",
                        "POWERINDIA",
                        "KEI",
                        "LTF",
                        "LAURUSLABS",
                        "MCX",
                        "NTPC",
                        "NATIONALUM",
                        "POLYCAB",
                        "MOTHERSON",
                        "SHRIRAMFIN",
                        "SOLARINDS",
                        "SAIL",
                        "TATASTEEL",
                        "TORNTPHARM",
                        "VEDL");
    }

    @Test
    void testStrikeStepAndAtmCalculation() {
        // Nifty (strike step 50)
        assertThat(StockFnoRegistry.getStrikeStep("NIFTY50", BigDecimal.valueOf(24123)))
                .isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(StockFnoRegistry.calculateAtmStrike("NIFTY50", BigDecimal.valueOf(24123)))
                .isEqualByComparingTo(BigDecimal.valueOf(24100));
        assertThat(StockFnoRegistry.calculateAtmStrike("NIFTY50", BigDecimal.valueOf(24135)))
                .isEqualByComparingTo(BigDecimal.valueOf(24150));

        // ABB (strike step 100)
        assertThat(StockFnoRegistry.calculateAtmStrike("ABB", BigDecimal.valueOf(7842)))
                .isEqualByComparingTo(BigDecimal.valueOf(7800));

        // TATASTEEL (strike step 2.5)
        assertThat(StockFnoRegistry.calculateAtmStrike("TATASTEEL", BigDecimal.valueOf(151.2)))
                .isEqualByComparingTo(BigDecimal.valueOf(150.0));
    }

    @Test
    void testLotSizes() {
        assertThat(StockFnoRegistry.getLotSize("NIFTY50")).isEqualTo(65);
        assertThat(StockFnoRegistry.getLotSize("TATASTEEL")).isEqualTo(5500);
        assertThat(StockFnoRegistry.getLotSize("ABB")).isEqualTo(125);
    }

    @Test
    void testTargetExpiryRollThreshold() {
        // Test when trade date is near expiry (e.g. 5 days before last Thursday)
        LocalDate entryDateNear = LocalDate.of(2026, 10, 26); // Oct 2026 last Thursday is Oct 29
        LocalDate expiry = StockFnoRegistry.calculateTargetExpiry(entryDateNear, 10);
        // Should roll to November 2026 last Thursday (Nov 26, 2026)
        assertThat(expiry.getMonthValue()).isEqualTo(11);

        // Test when trade date is well before expiry (> 10 days)
        LocalDate entryDateEarly = LocalDate.of(2026, 10, 5);
        LocalDate expiryEarly = StockFnoRegistry.calculateTargetExpiry(entryDateEarly, 10);
        assertThat(expiryEarly.getMonthValue()).isEqualTo(10);
    }

    @Test
    void testWeeklyTargetExpiryForIndices() {
        // Monday Oct 5, 2026 -> Thursday is Oct 8, 2026 (3 days remaining > 2)
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate weeklyExpiry = StockFnoRegistry.calculateWeeklyTargetExpiry(monday, 2);
        assertThat(weeklyExpiry).isEqualTo(LocalDate.of(2026, 10, 8));

        // Wednesday Oct 7, 2026 -> 1 day remaining (<= 2) -> Rolls to next week Oct 15, 2026
        LocalDate wednesday = LocalDate.of(2026, 10, 7);
        LocalDate rolledWeekly = StockFnoRegistry.calculateWeeklyTargetExpiry(wednesday, 2);
        assertThat(rolledWeekly).isEqualTo(LocalDate.of(2026, 10, 15));

        // NIFTY index respects preferWeekly
        LocalDate niftyExpiry = StockFnoRegistry.calculateTargetExpiry("NIFTY50", monday, true, 2);
        assertThat(niftyExpiry).isEqualTo(LocalDate.of(2026, 10, 8));

        // Stocks always get monthly expiry regardless of preferWeekly flag
        LocalDate stockExpiry = StockFnoRegistry.calculateTargetExpiry("BSE", monday, true, 10);
        assertThat(stockExpiry).isEqualTo(LocalDate.of(2026, 10, 29));
    }
}

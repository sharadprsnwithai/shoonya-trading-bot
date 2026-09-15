package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
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

        // BANKNIFTY (strike step 100)
        assertThat(StockFnoRegistry.getStrikeStep("BANKNIFTY", BigDecimal.valueOf(52000)))
                .isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(StockFnoRegistry.calculateAtmStrike("BANKNIFTY", BigDecimal.valueOf(52075)))
                .isEqualByComparingTo(BigDecimal.valueOf(52100));

        // SENSEX (strike step 100)
        assertThat(StockFnoRegistry.getStrikeStep("SENSEX", BigDecimal.valueOf(80000)))
                .isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(StockFnoRegistry.calculateAtmStrike("SENSEX", BigDecimal.valueOf(80123)))
                .isEqualByComparingTo(BigDecimal.valueOf(80100));

        // BSE Stock (strike step 50)
        assertThat(StockFnoRegistry.getStrikeStep("BSE", BigDecimal.valueOf(2400)))
                .isEqualByComparingTo(BigDecimal.valueOf(50));

        // SAIL Stock (strike step 2.5)
        assertThat(StockFnoRegistry.getStrikeStep("SAIL", BigDecimal.valueOf(140)))
                .isEqualByComparingTo(BigDecimal.valueOf(2.5));
        assertThat(StockFnoRegistry.calculateAtmStrike("SAIL", BigDecimal.valueOf(141.2)))
                .isEqualByComparingTo(BigDecimal.valueOf(140.0));
        assertThat(StockFnoRegistry.calculateAtmStrike("SAIL", BigDecimal.valueOf(141.3)))
                .isEqualByComparingTo(BigDecimal.valueOf(142.5));

        // TATASTEEL (strike step 2.5)
        assertThat(StockFnoRegistry.calculateAtmStrike("TATASTEEL", BigDecimal.valueOf(151.2)))
                .isEqualByComparingTo(BigDecimal.valueOf(150.0));
    }

    @Test
    void testLotSizesForAllInstruments() {
        assertThat(StockFnoRegistry.getLotSize("NIFTY50")).isEqualTo(65);
        assertThat(StockFnoRegistry.getLotSize("BANKNIFTY")).isEqualTo(30);
        assertThat(StockFnoRegistry.getLotSize("SENSEX")).isEqualTo(20);
        assertThat(StockFnoRegistry.getLotSize("BSE")).isEqualTo(250);
        assertThat(StockFnoRegistry.getLotSize("LAURUSLABS")).isEqualTo(1100);
        assertThat(StockFnoRegistry.getLotSize("SAIL")).isEqualTo(4700);
        assertThat(StockFnoRegistry.getLotSize("POLYCAB")).isEqualTo(125);
        assertThat(StockFnoRegistry.getLotSize("ADANIENSOL")).isEqualTo(675);
        assertThat(StockFnoRegistry.getLotSize("MCX")).isEqualTo(125);
        assertThat(StockFnoRegistry.getLotSize("TORNTPHARM")).isEqualTo(250);
        assertThat(StockFnoRegistry.getLotSize("BHEL")).isEqualTo(2625);
        assertThat(StockFnoRegistry.getLotSize("HINDALCO")).isEqualTo(1400);
        assertThat(StockFnoRegistry.getLotSize("UNKNOWN_STOCK")).isEqualTo(1);
    }

    @Test
    void testIsIndex() {
        assertThat(StockFnoRegistry.isIndex("NIFTY")).isTrue();
        assertThat(StockFnoRegistry.isIndex("NIFTY50")).isTrue();
        assertThat(StockFnoRegistry.isIndex("BANKNIFTY")).isTrue();
        assertThat(StockFnoRegistry.isIndex("SENSEX")).isTrue();
        assertThat(StockFnoRegistry.isIndex("BSESN")).isTrue();
        assertThat(StockFnoRegistry.isIndex("BSE")).isFalse();
        assertThat(StockFnoRegistry.isIndex("SAIL")).isFalse();
        assertThat(StockFnoRegistry.isIndex("POLYCAB")).isFalse();
        assertThat(StockFnoRegistry.isIndex(null)).isFalse();
    }

    @Test
    void testGetExchangeAndSegment() {
        assertThat(StockFnoRegistry.getExchange("NIFTY")).isEqualTo("NSE");
        assertThat(StockFnoRegistry.getSegment("NIFTY")).isEqualTo("NFO");
        assertThat(StockFnoRegistry.getExchange("BANKNIFTY")).isEqualTo("NSE");
        assertThat(StockFnoRegistry.getSegment("BANKNIFTY")).isEqualTo("NFO");
        assertThat(StockFnoRegistry.getExchange("SENSEX")).isEqualTo("BSE");
        assertThat(StockFnoRegistry.getSegment("SENSEX")).isEqualTo("BFO");
        assertThat(StockFnoRegistry.getExchange("BSE")).isEqualTo("NSE");
        assertThat(StockFnoRegistry.getSegment("BSE")).isEqualTo("NFO");
    }

    @Test
    void testWeeklyExpiryForNiftyIsThursday() {
        // Monday Oct 5, 2026 -> Thursday is Oct 8, 2026 (3 days remaining > 2)
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate weeklyExpiry = StockFnoRegistry.calculateWeeklyTargetExpiry("NIFTY", monday, 2);
        assertThat(weeklyExpiry).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(weeklyExpiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @Test
    void testWeeklyExpiryForSensexIsFriday() {
        // Monday Oct 5, 2026 -> Friday is Oct 9, 2026
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate weeklyExpiry = StockFnoRegistry.calculateWeeklyTargetExpiry("SENSEX", monday, 2);
        assertThat(weeklyExpiry).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(weeklyExpiry.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void testWeeklyExpiryRollsToNextWeek() {
        // Wednesday Oct 7, 2026 -> Thursday Oct 8 (1 day <= 2) -> Rolls to next Thu Oct 15
        LocalDate wednesday = LocalDate.of(2026, 10, 7);
        LocalDate rolledWeekly =
                StockFnoRegistry.calculateWeeklyTargetExpiry("NIFTY", wednesday, 2);
        assertThat(rolledWeekly).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void testMonthlyExpiryForNiftyIsLastThursday() {
        LocalDate earlyOct = LocalDate.of(2026, 10, 5);
        LocalDate monthlyExpiry =
                StockFnoRegistry.calculateMonthlyTargetExpiry("NIFTY", earlyOct, 10);
        assertThat(monthlyExpiry).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(monthlyExpiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @Test
    void testMonthlyExpiryForSensexIsLastFriday() {
        LocalDate earlyOct = LocalDate.of(2026, 10, 5);
        LocalDate monthlyExpiry =
                StockFnoRegistry.calculateMonthlyTargetExpiry("SENSEX", earlyOct, 10);
        assertThat(monthlyExpiry).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(monthlyExpiry.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void testMonthlyExpiryForBankNiftyIsLastThursday() {
        LocalDate earlyOct = LocalDate.of(2026, 10, 5);
        LocalDate monthlyExpiry =
                StockFnoRegistry.calculateMonthlyTargetExpiry("BANKNIFTY", earlyOct, 10);
        assertThat(monthlyExpiry).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(monthlyExpiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @Test
    void testMonthlyExpiryRollsToNextMonth() {
        LocalDate lateOct = LocalDate.of(2026, 10, 26);
        LocalDate expiry = StockFnoRegistry.calculateMonthlyTargetExpiry("NIFTY", lateOct, 10);
        assertThat(expiry.getMonthValue()).isEqualTo(11);
    }

    @Test
    void testFormatTradingSymbolMonthlyNifty() {
        LocalDate expiry = LocalDate.of(2026, 10, 29);
        String sym =
                StockFnoRegistry.formatTradingSymbol(
                        "NIFTY", expiry, BigDecimal.valueOf(25000), "CE", false);
        assertThat(sym).isEqualTo("NIFTY26OCT25000CE");
    }

    @Test
    void testFormatTradingSymbolWeeklyNifty() {
        LocalDate expiry = LocalDate.of(2026, 10, 8);
        String sym =
                StockFnoRegistry.formatTradingSymbol(
                        "NIFTY", expiry, BigDecimal.valueOf(25000), "PE", true);
        assertThat(sym).isEqualTo("NIFTY08OCT2625000PE");
    }

    @Test
    void testFormatTradingSymbolMonthlySensex() {
        LocalDate expiry = LocalDate.of(2026, 10, 30);
        String sym =
                StockFnoRegistry.formatTradingSymbol(
                        "SENSEX", expiry, BigDecimal.valueOf(80000), "CE", false);
        assertThat(sym).isEqualTo("SENSEX26OCT80000CE");
    }

    @Test
    void testFormatTradingSymbolWeeklySensex() {
        LocalDate expiry = LocalDate.of(2026, 10, 9);
        String sym =
                StockFnoRegistry.formatTradingSymbol(
                        "SENSEX", expiry, BigDecimal.valueOf(80000), "PE", true);
        assertThat(sym).isEqualTo("SENSEX09OCT2680000PE");
    }

    @Test
    void testFormatTradingSymbolMonthlyBankNifty() {
        LocalDate expiry = LocalDate.of(2026, 10, 29);
        String sym =
                StockFnoRegistry.formatTradingSymbol(
                        "BANKNIFTY", expiry, BigDecimal.valueOf(52000), "CE", false);
        assertThat(sym).isEqualTo("BANKNIFTY26OCT52000CE");
    }

    @Test
    void testFormatTradingSymbolMonthlyStock() {
        LocalDate expiry = LocalDate.of(2026, 10, 29);
        String sym =
                StockFnoRegistry.formatTradingSymbol(
                        "BSE", expiry, BigDecimal.valueOf(2400), "PE", false);
        assertThat(sym).isEqualTo("BSE26OCT2400PE");
    }

    @Test
    void testCalculateAtmForStockBSE() {
        BigDecimal atm = StockFnoRegistry.calculateAtmStrike("BSE", BigDecimal.valueOf(2412));
        // BSE strike step is 50
        assertThat(atm).isEqualByComparingTo(BigDecimal.valueOf(2400.0));
    }

    @Test
    void testCalculateAtmForStockHindalco() {
        BigDecimal atm = StockFnoRegistry.calculateAtmStrike("HINDALCO", BigDecimal.valueOf(683));
        // HINDALCO strike step is 10
        assertThat(atm).isEqualByComparingTo(BigDecimal.valueOf(680));
    }

    @Test
    void testEstimateTheoreticalPremiumAtm() {
        BigDecimal premium =
                StockFnoRegistry.estimateTheoreticalPremium(
                        "NIFTY", BigDecimal.valueOf(24000), BigDecimal.valueOf(24000), "CE", 5.0);
        // ATM 5-day premium for Nifty should be roughly 100-200 range
        assertThat(premium.doubleValue()).isBetween(50.0, 300.0);
    }

    @Test
    void testEstimateTheoreticalPremiumOtm() {
        BigDecimal premiumAtm =
                StockFnoRegistry.estimateTheoreticalPremium(
                        "NIFTY", BigDecimal.valueOf(24000), BigDecimal.valueOf(24000), "PE", 5.0);
        BigDecimal premiumOtm =
                StockFnoRegistry.estimateTheoreticalPremium(
                        "NIFTY", BigDecimal.valueOf(24000), BigDecimal.valueOf(23500), "PE", 5.0);
        // OTM premium should be significantly lower than ATM
        assertThat(premiumOtm.doubleValue()).isLessThan(premiumAtm.doubleValue());
        assertThat(premiumOtm.doubleValue()).isGreaterThan(0.0);
    }

    @Test
    void testEstimateTheoreticalPremiumSensex() {
        BigDecimal premium =
                StockFnoRegistry.estimateTheoreticalPremium(
                        "SENSEX", BigDecimal.valueOf(80000), BigDecimal.valueOf(80000), "CE", 5.0);
        // ATM 5-day premium for SENSEX should be in ~350-800 range
        assertThat(premium.doubleValue()).isBetween(100.0, 1000.0);
    }

    @Test
    void testEstimateTheoreticalPremiumStock() {
        BigDecimal premium =
                StockFnoRegistry.estimateTheoreticalPremium(
                        "BSE", BigDecimal.valueOf(2400), BigDecimal.valueOf(2400), "CE", 20.0);
        // Stock options have higher IV around 28%, ATM premium should be notable
        assertThat(premium.doubleValue()).isBetween(10.0, 250.0);
    }

    @Test
    void testCalculateOtmStrike() {
        BigDecimal otmPe =
                StockFnoRegistry.calculateOtmStrike("NIFTY", BigDecimal.valueOf(24000), "PE", 2);
        // PE OTM 2 steps below ATM: 24000 - (50 * 2) = 23900
        assertThat(otmPe).isEqualByComparingTo(BigDecimal.valueOf(23900));

        BigDecimal otmCe =
                StockFnoRegistry.calculateOtmStrike("NIFTY", BigDecimal.valueOf(24000), "CE", 2);
        // CE OTM 2 steps above ATM: 24000 + (50 * 2) = 24100
        assertThat(otmCe).isEqualByComparingTo(BigDecimal.valueOf(24100));

        BigDecimal otmSensex =
                StockFnoRegistry.calculateOtmStrike("SENSEX", BigDecimal.valueOf(80000), "PE", 2);
        // SENSEX strike step 100: 80000 - (100 * 2) = 79800
        assertThat(otmSensex).isEqualByComparingTo(BigDecimal.valueOf(79800));
    }

    @Test
    void testTargetExpiryForStocksAlwaysMonthly() {
        LocalDate earlyOct = LocalDate.of(2026, 10, 5);
        // Stock should ALWAYS get monthly expiry regardless of preferWeekly
        LocalDate stockExpiry = StockFnoRegistry.calculateTargetExpiry("BSE", earlyOct, true, 10);
        assertThat(stockExpiry).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(stockExpiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @Test
    void testTargetExpiryForNiftyWeekly() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate expiry = StockFnoRegistry.calculateTargetExpiry("NIFTY", monday, true, 2);
        assertThat(expiry).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @Test
    void testTargetExpiryForSensexWeekly() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        LocalDate expiry = StockFnoRegistry.calculateTargetExpiry("SENSEX", monday, true, 2);
        assertThat(expiry).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void testTargetExpiryForBankNiftyMonthly() {
        // BANKNIFTY with preferWeekly=false should get monthly
        LocalDate earlyOct = LocalDate.of(2026, 10, 5);
        LocalDate expiry = StockFnoRegistry.calculateTargetExpiry("BANKNIFTY", earlyOct, false, 10);
        assertThat(expiry).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }
}

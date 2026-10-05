package com.tradingbot.strategy.driftvwap.model;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DriftVwapPositionTest {

    @Test
    @DisplayName("Sold Option Position - Target Hit (+70% decay) computes correct positive P&L")
    void testSoldOptionTargetHitComputesPositivePnl() {
        BigDecimal entryPrem = BigDecimal.valueOf(100.00);
        BigDecimal targetPrem = BigDecimal.valueOf(30.00); // 70% decay
        BigDecimal slPrem = BigDecimal.valueOf(160.00); // 60% expansion
        int qty = 150; // 2 lots

        DriftVwapPosition pos =
                new DriftVwapPosition(
                        "DVWAP-1",
                        "NIFTY",
                        "PE",
                        "NIFTY26OCT25000PE",
                        BigDecimal.valueOf(25000),
                        2,
                        75,
                        DriftDirection.BULLISH_DRIFT,
                        BigDecimal.valueOf(25000),
                        entryPrem,
                        slPrem,
                        targetPrem,
                        qty,
                        BigDecimal.valueOf(9000),
                        Instant.now());

        assertFalse(pos.isClosed());
        assertEquals(BigDecimal.valueOf(100.00), pos.getEntryPremium());
        assertEquals(BigDecimal.valueOf(30.00), pos.getTargetPremium());
        assertEquals(BigDecimal.valueOf(160.00), pos.getSlPremium());

        // Target hit @ 30.00 -> Profit = (100 - 30) * 150 = +10,500.00
        pos.close(targetPrem, "OPTION_TARGET_DECAY", Instant.now());

        assertTrue(pos.isClosed());
        assertEquals(0, BigDecimal.valueOf(10500.00).compareTo(pos.getRealizedPnl()));
        assertEquals("OPTION_TARGET_DECAY", pos.getExitReason());
    }

    @Test
    @DisplayName("Sold Option Position - SL Hit (+60% expansion) computes correct negative P&L")
    void testSoldOptionSlHitComputesNegativePnl() {
        BigDecimal entryPrem = BigDecimal.valueOf(80.00);
        BigDecimal targetPrem = BigDecimal.valueOf(24.00);
        BigDecimal slPrem = BigDecimal.valueOf(128.00);
        int qty = 75;

        DriftVwapPosition pos =
                new DriftVwapPosition(
                        "DVWAP-2",
                        "NIFTY",
                        "CE",
                        "NIFTY26OCT25000CE",
                        BigDecimal.valueOf(25000),
                        1,
                        75,
                        DriftDirection.BEARISH_DRIFT,
                        BigDecimal.valueOf(25000),
                        entryPrem,
                        slPrem,
                        targetPrem,
                        qty,
                        BigDecimal.valueOf(3600),
                        Instant.now());

        // SL hit @ 128.00 -> Loss = (80 - 128) * 75 = -3,600.00
        pos.close(slPrem, "OPTION_SL_EXPANSION", Instant.now());

        assertTrue(pos.isClosed());
        assertEquals(0, BigDecimal.valueOf(-3600.00).compareTo(pos.getRealizedPnl()));
        assertEquals("OPTION_SL_EXPANSION", pos.getExitReason());
    }
}

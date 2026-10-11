package com.tradingbot.strategy.car.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarWeeklyPropertiesTest {

    private CarWeeklyProperties props;

    @BeforeEach
    void setUp() {
        props = new CarWeeklyProperties();
        props.setAccumulationOnlySymbols(List.of("BEES", "ETF", "RELIANCE"));
    }

    @Test
    void testPartialPatternMatchForBeesAndEtfs() {
        // "BEES" matches NIFTYBEES, SILVERBEES, GOLDBEES, BANKBEES
        assertTrue(props.isAccumulationOnly("NIFTYBEES"));
        assertTrue(props.isAccumulationOnly("SILVERBEES"));
        assertTrue(props.isAccumulationOnly("GOLDBEES"));
        assertTrue(props.isAccumulationOnly("NSE:BANKBEES"));

        // "ETF" matches CPSEETF, MAHKTECH
        assertTrue(props.isAccumulationOnly("CPSEETF"));

        // Exact match
        assertTrue(props.isAccumulationOnly("RELIANCE"));

        // Non-exempt stocks
        assertFalse(props.isAccumulationOnly("TCS"));
        assertFalse(props.isAccumulationOnly("INFY"));
        assertFalse(props.isAccumulationOnly("TATAMOTORS"));
    }

    @Test
    void testAutomaticSgbDetection() {
        // Auto SGB detection
        assertTrue(props.isAccumulationOnly("SGB24AUG"));
        assertTrue(props.isAccumulationOnly("SGBNOV29"));
        assertTrue(props.isAccumulationOnly("NSE:SGBDE31"));
    }

    @Test
    void testWatchlistAndScopeProperties() {
        assertFalse(props.isScanOnlyWatchlistAndHoldings());
        props.setScanOnlyWatchlistAndHoldings(true);
        assertTrue(props.isScanOnlyWatchlistAndHoldings());

        assertTrue(props.getWatchlistSymbols().isEmpty());
        props.setWatchlistSymbols(List.of("CCHHL", "MOREALTY", "PREMIERENE"));
        assertEquals(3, props.getWatchlistSymbols().size());
        assertTrue(props.getWatchlistSymbols().contains("CCHHL"));
    }
}

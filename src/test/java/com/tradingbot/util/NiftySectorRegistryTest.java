package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NiftySectorRegistryTest {

    @Test
    @DisplayName("Should contain all 11 NSE sectors with valid F&O constituents")
    void testAll11SectorsPresent() {
        Map<String, List<String>> sectors = NiftySectorRegistry.getSectorConstituents();
        assertEquals(11, sectors.size());
        assertTrue(sectors.containsKey("NIFTY MEDIA"));
        assertTrue(sectors.containsKey("NIFTY IT"));
        assertTrue(sectors.containsKey("NIFTY PHARMA"));
        assertTrue(sectors.containsKey("NIFTY AUTO"));
        assertTrue(sectors.containsKey("NIFTY METAL"));
        assertTrue(sectors.containsKey("NIFTY BANK"));
        assertTrue(sectors.containsKey("NIFTY FIN SERVICE"));
        assertTrue(sectors.containsKey("NIFTY FMCG"));
        assertTrue(sectors.containsKey("NIFTY PSU BANK"));
        assertTrue(sectors.containsKey("NIFTY REALTY"));
        assertTrue(sectors.containsKey("NIFTY ENERGY"));

        assertTrue(sectors.get("NIFTY MEDIA").contains("PVRINOX"));
        assertTrue(sectors.get("NIFTY MEDIA").contains("SUNTV"));
        assertTrue(sectors.get("NIFTY IT").contains("TCS"));
        assertTrue(sectors.get("NIFTY IT").contains("INFY"));
    }

    @Test
    @DisplayName("Should correctly find sector by stock symbol")
    void testGetSectorForSymbol() {
        assertEquals("NIFTY MEDIA", NiftySectorRegistry.getSectorForSymbol("PVRINOX"));
        assertEquals("NIFTY IT", NiftySectorRegistry.getSectorForSymbol("INFY"));
        assertEquals("NIFTY PHARMA", NiftySectorRegistry.getSectorForSymbol("GLENMARK"));
        assertEquals("NIFTY AUTO", NiftySectorRegistry.getSectorForSymbol("TATAMOTORS"));
    }
}

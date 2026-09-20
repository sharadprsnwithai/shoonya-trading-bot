package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CommodityRegistryTest {

    @Test
    void testCommodityResolution() {
        assertTrue(CommodityRegistry.isCommodity("CRUDEOIL"));
        assertTrue(CommodityRegistry.isCommodity("crude"));
        assertTrue(CommodityRegistry.isCommodity("GOLD"));
        assertTrue(CommodityRegistry.isCommodity("SILVER"));
        assertTrue(CommodityRegistry.isCommodity("COPPER"));
        assertFalse(CommodityRegistry.isCommodity("RELIANCE"));

        var crudeMeta = CommodityRegistry.getMetadata("CRUDEOIL");
        assertNotNull(crudeMeta);
        assertEquals("CL=F", crudeMeta.yahooTicker());
        assertEquals(100, crudeMeta.lotSize());

        var goldMeta = CommodityRegistry.getMetadata("GOLD");
        assertNotNull(goldMeta);
        assertEquals("GC=F", goldMeta.yahooTicker());

        var silverMeta = CommodityRegistry.getMetadata("SILVER");
        assertNotNull(silverMeta);
        assertEquals("SI=F", silverMeta.yahooTicker());

        var copperMeta = CommodityRegistry.getMetadata("COPPER");
        assertNotNull(copperMeta);
        assertEquals("HG=F", copperMeta.yahooTicker());
        assertEquals(2500, copperMeta.lotSize());
        assertEquals(2.20462, copperMeta.unitMultiplier(), 0.0001);

        assertEquals(1.0, CommodityRegistry.getUnitMultiplier("CRUDEOIL"), 0.0001);
        assertEquals(0.321507, CommodityRegistry.getUnitMultiplier("GOLD"), 0.0001);
        assertEquals(32.15075, CommodityRegistry.getUnitMultiplier("SILVER"), 0.0001);
        assertEquals(2.20462, CommodityRegistry.getUnitMultiplier("COPPER"), 0.0001);
        assertEquals(1.0, CommodityRegistry.getUnitMultiplier("NATURALGAS"), 0.0001);
        assertEquals(1.0, CommodityRegistry.getUnitMultiplier("INFY"), 0.0001);
    }
}

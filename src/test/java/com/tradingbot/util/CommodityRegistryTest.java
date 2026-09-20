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
    }
}

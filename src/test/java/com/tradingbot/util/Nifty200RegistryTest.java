package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Nifty200RegistryTest {

    @Test
    void testNoDuplicateSymbolsInNifty200Universe() {
        List<String> symbols = Nifty200Registry.getAllSymbols();
        assertNotNull(symbols);
        assertFalse(symbols.isEmpty());

        Set<String> seen = new HashSet<>();
        for (String sym : symbols) {
            assertTrue(seen.add(sym), "Duplicate symbol found in Nifty200Registry: " + sym);
        }
    }

    @Test
    void testSymbolMetadataResolution() {
        var rel = Nifty200Registry.getMetadata("RELIANCE");
        assertNotNull(rel);
        assertEquals("RELIANCE", rel.symbol());
        assertTrue(rel.isFno());
        assertTrue(rel.lotSize() > 0);

        var mphasis = Nifty200Registry.getMetadata("MPHASIS");
        assertNotNull(mphasis);
        assertEquals("MPHASIS", mphasis.symbol());

        var motherson = Nifty200Registry.getMetadata("MOTHERSON");
        assertNotNull(motherson);
        assertEquals("MOTHERSON", motherson.symbol());
    }
}

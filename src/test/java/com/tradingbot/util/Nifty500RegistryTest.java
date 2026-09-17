package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Nifty500RegistryTest {

    @Test
    void testRegistryContains500Symbols() {
        List<String> symbols = Nifty500Registry.getAllSymbols();
        assertThat(symbols).isNotNull();
        assertThat(symbols.size()).isGreaterThanOrEqualTo(500);

        // Spot-check leading, midcap, smallcap, and boundary symbols
        assertThat(Nifty500Registry.containsSymbol("360ONE")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("RELIANCE")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("TCS")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("HDFCBANK")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("ZOMATO")).isFalse(); // Not in list unless listed under different name
        assertThat(Nifty500Registry.containsSymbol("SWIGGY")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("ECLERX")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("ZYDUSWELL")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("UNKNOWN_FAKE_STOCK")).isFalse();
    }

    @Test
    void testSymbolCaseInsensitivity() {
        assertThat(Nifty500Registry.containsSymbol("reliance")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("360one")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("Eclerx")).isTrue();
    }

    @Test
    void testMetadataLookup() {
        var meta = Nifty500Registry.getMetadata("RELIANCE");
        assertThat(meta).isNotNull();
        assertThat(meta.symbol()).isEqualTo("RELIANCE");
        assertThat(meta.exchange()).isEqualTo("NSE");
    }
}

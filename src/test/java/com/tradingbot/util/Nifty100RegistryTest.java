package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Nifty100RegistryTest {

    @Test
    void registry_contains100Symbols() {
        assertThat(Nifty100Registry.getAllSymbols()).hasSize(100);
    }

    @Test
    void registry_containsWellKnownNames() {
        List<String> symbols = Nifty100Registry.getAllSymbols();
        assertThat(symbols).contains("RELIANCE", "HDFCBANK", "TCS", "SBIN", "TATASTEEL", "INFY");
    }

    @Test
    void registry_containsNewListingTickers() {
        // 2025-2026 corporate-action / new listing tickers the user explicitly confirmed
        List<String> symbols = Nifty100Registry.getAllSymbols();
        assertThat(symbols)
                .contains("ENRIN", "TMCV", "TMPV", "LTM", "ETERNAL", "JIOFIN", "MAZDOCK");
    }

    @Test
    void registry_noDuplicates() {
        List<String> symbols = Nifty100Registry.getAllSymbols();
        assertThat(symbols).doesNotHaveDuplicates();
    }

    @Test
    void knownToken_returnsVerifiedStockFnoTokens() {
        // ABB has a verified token in StockFnoRegistry
        assertThat(Nifty100Registry.getKnownToken("ABB")).isEqualTo("13");
    }

    @Test
    void knownToken_unknownSymbol_returnsNull() {
        // TMCV/ENRIN etc. are not yet in StockFnoRegistry -> null, caller resolves via SearchScrip
        assertThat(Nifty100Registry.getKnownToken("TMCV")).isNull();
    }
}

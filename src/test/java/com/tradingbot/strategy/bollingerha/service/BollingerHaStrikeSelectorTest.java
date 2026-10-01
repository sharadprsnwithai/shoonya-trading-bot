package com.tradingbot.strategy.bollingerha.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BollingerHaStrikeSelectorTest {

    @Mock private ShoonyaOptionChainService optionChainService;

    @InjectMocks private BollingerHaStrikeSelector strikeSelector;

    @Test
    void testAtmRoundingAndSelection() {
        BigDecimal spot = new BigDecimal("25948.35");
        BigDecimal expectedAtm = new BigDecimal("25950");

        OptionStrike strike =
                new OptionStrike(
                        expectedAtm,
                        true,
                        new OptionContract(
                                "NIFTY26OCT25950CE",
                                "12345",
                                "CE",
                                expectedAtm,
                                BigDecimal.ZERO,
                                0L,
                                0L,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO),
                        new OptionContract(
                                "NIFTY26OCT25950PE",
                                "67890",
                                "PE",
                                expectedAtm,
                                BigDecimal.ZERO,
                                0L,
                                0L,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO));
        OptionChainResponse chainResp =
                new OptionChainResponse(
                        "NIFTY",
                        expectedAtm,
                        expectedAtm,
                        "NIFTY",
                        1,
                        0L,
                        0L,
                        1.0,
                        List.of(strike));
        when(optionChainService.getNifty50OptionChain(eq(expectedAtm), eq(1), eq(false)))
                .thenReturn(chainResp);

        SelectedStrikes result = strikeSelector.selectWeeklyAtmStrikes(spot);
        assertNotNull(result);
        assertEquals(expectedAtm, result.atmStrike());
        assertEquals("12345", result.ceToken());
        assertEquals("NIFTY26OCT25950CE", result.ceSymbol());
        assertEquals("67890", result.peToken());
        assertEquals("NIFTY26OCT25950PE", result.peSymbol());
    }
}

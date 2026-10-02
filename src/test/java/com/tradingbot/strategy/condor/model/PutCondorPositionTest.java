package com.tradingbot.strategy.condor.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PutCondorPositionTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Test PutCondorPosition state initialization and MTM calculation")
    void testPositionMtmCalculation() {
        PutCondorPosition pos = new PutCondorPosition();
        pos.setState(PutCondorState.CONDOR_ACTIVE);
        pos.setCycleExpiryDate(LocalDate.of(2026, 10, 29));
        pos.setEntrySpotPrice(BigDecimal.valueOf(25000));
        pos.setLots(50);
        pos.setLotSize(65);
        pos.setTotalQuantity(3250);

        // Set strikes
        pos.setK1BuyStrike(24800);
        pos.setK2SellStrike(24600);
        pos.setK3SellStrike(24400);
        pos.setK4BuyStrike(24200);

        // Set entry prices
        pos.setK1EntryPrice(BigDecimal.valueOf(140.0));
        pos.setK2EntryPrice(BigDecimal.valueOf(95.0));
        pos.setK3EntryPrice(BigDecimal.valueOf(62.0));
        pos.setK4EntryPrice(BigDecimal.valueOf(38.0));

        // Initial net debit: (140 + 38) - (95 + 62) = 178 - 157 = 21.0 pts
        pos.calculateAndSetInitialDebit();
        assertEquals(0, BigDecimal.valueOf(21.0).compareTo(pos.getInitialNetDebitPts()));
        assertEquals(0, BigDecimal.valueOf(68250.0).compareTo(pos.getInitialNetDebitRs()));

        // Simulate Current LTPs: k1=160, k2=90, k3=50, k4=30
        // Current value: (160 + 30) - (90 + 50) = 190 - 140 = 50.0 pts
        // Net MTM pts: 50.0 - 21.0 = +29.0 pts -> Total MTM: 29.0 * 3250 = +Rs 94,250
        BigDecimal currentMtm = pos.computeCurrentMtm(
                BigDecimal.valueOf(160.0),
                BigDecimal.valueOf(90.0),
                BigDecimal.valueOf(50.0),
                BigDecimal.valueOf(30.0),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        assertEquals(0, BigDecimal.valueOf(94250.0).compareTo(currentMtm));
    }

    @Test
    @DisplayName("Test JSON serialization and deserialization of PutCondorPosition")
    void testJsonSerialization() throws Exception {
        PutCondorPosition pos = new PutCondorPosition();
        pos.setState(PutCondorState.UPSIDE_FINANCED);
        pos.setCycleExpiryDate(LocalDate.of(2026, 10, 29));
        pos.setEntrySpotPrice(BigDecimal.valueOf(25000));
        pos.setLots(50);
        pos.setLotSize(65);
        pos.setTotalQuantity(3250);
        pos.setK1BuyStrike(24800);
        pos.setK2SellStrike(24600);
        pos.setK3SellStrike(24400);
        pos.setK4BuyStrike(24200);

        String json = objectMapper.writeValueAsString(pos);
        assertNotNull(json);

        PutCondorPosition deserialized = objectMapper.readValue(json, PutCondorPosition.class);
        assertEquals(PutCondorState.UPSIDE_FINANCED, deserialized.getState());
        assertEquals(24800, deserialized.getK1BuyStrike());
        assertEquals(LocalDate.of(2026, 10, 29), deserialized.getCycleExpiryDate());
    }
}

package com.tradingbot.strategy.car.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarPortfolioStateTest {

    private Path tempFile;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("car-portfolio-test", ".json");
        objectMapper =
                new ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .disable(
                                com.fasterxml.jackson.databind.SerializationFeature
                                        .WRITE_DATES_AS_TIMESTAMPS);
    }

    @AfterEach
    void tearDown() {
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception ignored) {
        }
    }

    @Test
    void testAccumulateMultipleUnitsUpdatesWeightedAveragePriceAndTarget() {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));

        // Buy Unit 1: 50 shares @ 500.0
        state.addFill("RELIANCE", 50, new BigDecimal("500.0"));
        CarHolding h1 = state.getHolding("RELIANCE");
        assertNotNull(h1);
        assertEquals(50, h1.totalQuantity());
        assertEquals(new BigDecimal("500.00"), h1.averageBuyPrice());
        assertEquals(new BigDecimal("531.40"), h1.targetPrice()); // 500 * 1.0628 = 531.40

        // Buy Unit 2: 55 shares @ 460.0 (Averaging down)
        state.addFill("RELIANCE", 55, new BigDecimal("460.0"));
        CarHolding h2 = state.getHolding("RELIANCE");
        assertEquals(105, h2.totalQuantity());
        // (50 * 500 + 55 * 460) / 105 = (25000 + 25300) / 105 = 50300 / 105 = 479.05
        assertEquals(new BigDecimal("479.05"), h2.averageBuyPrice());
        assertEquals(new BigDecimal("509.13"), h2.targetPrice()); // 479.05 * 1.0628 = 509.13
    }

    @Test
    void testTargetHitBooksProfitAndCompoundsUnitSize() {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));
        assertEquals(new BigDecimal("25000.00"), state.getUnitSize());

        state.addFill("TCS", 50, new BigDecimal("500.0"));
        // Sell at target 531.40 -> Profit = (531.40 - 500.0) * 50 = 31.40 * 50 = +1570.0
        BigDecimal profit = state.closeHoldingAtTarget("TCS", new BigDecimal("531.40"));
        assertEquals(new BigDecimal("1570.00"), profit);
        assertNull(state.getHolding("TCS"));

        // Capital compounded: 1,000,000 + 1570 = 1,001,570 -> UNIT = 1,001,570 / 40 = 25,039.25
        assertEquals(new BigDecimal("1001570.00"), state.getTotalCapital());
        assertEquals(new BigDecimal("25039.25"), state.getUnitSize());
    }

    @Test
    void testJsonSerializationWithInstantFields() throws Exception {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));
        state.addFill("RELIANCE", 50, new BigDecimal("500.0"));
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        new CarGttOrder(
                                "GTT_123",
                                "ZERODHA",
                                "RELIANCE",
                                GttOrderType.BUY,
                                BigDecimal.valueOf(500.0),
                                BigDecimal.valueOf(500.10),
                                50,
                                GttStatus.PENDING,
                                java.time.LocalDate.now(),
                                java.time.Instant.now()));

        // Serialize to file
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), state);

        // Deserialize from file
        CarPortfolioState restored =
                objectMapper.readValue(tempFile.toFile(), CarPortfolioState.class);
        assertNotNull(restored);
        assertEquals(1, restored.getHoldings().size());
        assertNotNull(restored.getHolding("RELIANCE"));
        assertNotNull(restored.getHolding("RELIANCE").firstEntryTime());
        assertEquals(1, restored.getGttOrders().size());
        assertNotNull(restored.getGttOrders().get("RELIANCE").createdAt());
    }

    @Test
    void testAvailableUnitsChargesCapitalOccupiedRatherThanEntryCount() {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));

        // One share of MRF at 132,000 ties up 132000 / 25000 = 5.28 -> 6 units of capital.
        state.addFill("MRF", 1, new BigDecimal("132000.0"));

        assertEquals(1, state.getHolding("MRF").accumulatedUnits());
        assertEquals(34, state.getAvailableUnits());
    }

    @Test
    void testPendingBuyGttReservesCapitalAndFreesItWhenRemoved() {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));

        // 10 shares x 2500.10 = 25,001 -> 2 units reserved.
        state.getGttOrders()
                .put(
                        "RELIANCE",
                        new CarGttOrder(
                                "GTT_1",
                                "ZERODHA",
                                "RELIANCE",
                                GttOrderType.BUY,
                                BigDecimal.valueOf(2500.00),
                                BigDecimal.valueOf(2500.10),
                                10,
                                GttStatus.PENDING,
                                java.time.LocalDate.now(),
                                java.time.Instant.now()));
        assertEquals(38, state.getAvailableUnits());

        state.getGttOrders().remove("RELIANCE");
        assertEquals(40, state.getAvailableUnits());
    }

    @Test
    void testPlaceholderEntryWithoutQuantityReservesNothing() {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));

        // Legacy placeholder entries (no trigger, no quantity) must never eat a capital unit.
        state.getGttOrders()
                .put(
                        "TCS",
                        new CarGttOrder(
                                "GTT_2",
                                "ZERODHA",
                                "TCS",
                                GttOrderType.BUY,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                0,
                                GttStatus.PENDING,
                                java.time.LocalDate.now(),
                                java.time.Instant.now()));

        assertEquals(40, state.getAvailableUnits());
    }

    @Test
    void testCapitalIsReleasedAgainAfterExit() {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));

        state.addFill("MRF", 1, new BigDecimal("132000.0"));
        assertEquals(34, state.getAvailableUnits());

        state.closeHoldingAtTarget("MRF", new BigDecimal("140000.0"));
        assertEquals(40, state.getAvailableUnits());
        // Realized profit compounds into the unit size.
        assertTrue(state.getTotalCapital().compareTo(new BigDecimal("1000000.0")) > 0);
    }

    @Test
    void testLastRunWeekRoundTripsThroughJson() throws Exception {
        CarPortfolioState state =
                new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));
        java.time.LocalDate week = java.time.LocalDate.of(2026, 10, 5);
        state.setLastRunWeek(week);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), state);
        CarPortfolioState restored =
                objectMapper.readValue(tempFile.toFile(), CarPortfolioState.class);

        assertEquals(week, restored.getLastRunWeek());
    }
}

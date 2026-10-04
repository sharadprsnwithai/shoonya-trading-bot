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
        CarPortfolioState restored = objectMapper.readValue(tempFile.toFile(), CarPortfolioState.class);
        assertNotNull(restored);
        assertEquals(1, restored.getHoldings().size());
        assertNotNull(restored.getHolding("RELIANCE"));
        assertNotNull(restored.getHolding("RELIANCE").firstEntryTime());
        assertEquals(1, restored.getGttOrders().size());
        assertNotNull(restored.getGttOrders().get("RELIANCE").createdAt());
    }
}

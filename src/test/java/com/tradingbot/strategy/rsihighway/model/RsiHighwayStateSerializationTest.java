package com.tradingbot.strategy.rsihighway.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RsiHighwayStateSerializationTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void testSaveAndLoadStateJson() throws Exception {
        File tempFile = Files.createTempFile("rsi_highway_state_test", ".json").toFile();
        tempFile.deleteOnExit();

        RsiHighwayState state = new RsiHighwayState();
        RsiHighwayPosition pos = new RsiHighwayPosition("RELIANCE", "NSE", 2800.0, 2600.0);
        pos.addTranche(new RsiHighwayTranche(1, 10, 2800.0, Instant.now(), "ORD_001"));
        state.getPositions().put("RELIANCE", pos);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, state);

        RsiHighwayState loaded = objectMapper.readValue(tempFile, RsiHighwayState.class);
        assertThat(loaded.getPositions()).containsKey("RELIANCE");
        assertThat(loaded.getPositions().get("RELIANCE").getTotalQuantity()).isEqualTo(10);
        assertThat(loaded.getPositions().get("RELIANCE").getAveragePrice()).isEqualTo(2800.0);
    }

    @Test
    void testPositionPyramidingWeightedAverage() {
        RsiHighwayPosition pos = new RsiHighwayPosition("TCS", "NSE", 3500.0, 3300.0);
        // Tranche 1: 100 shares @ 3500
        pos.addTranche(new RsiHighwayTranche(1, 100, 3500.0, Instant.now(), "ORD_T1"));
        assertThat(pos.getTotalQuantity()).isEqualTo(100);
        assertThat(pos.getAveragePrice()).isEqualTo(3500.0);

        // Tranche 2 (Pyramid): 50 shares @ 3800
        pos.addTranche(new RsiHighwayTranche(2, 50, 3800.0, Instant.now(), "ORD_T2"));
        assertThat(pos.getTotalQuantity()).isEqualTo(150);
        // Weighted Avg = (100 * 3500 + 50 * 3800) / 150 = (350000 + 190000) / 150 = 540000 / 150 =
        // 3600.0
        assertThat(pos.getAveragePrice()).isEqualTo(3600.0);
    }

    @Test
    void testMarkToMarketPortfolioEquityAndClosePosition() {
        RsiHighwayState state = new RsiHighwayState();
        state.setAvailableCapital(500000.0);

        RsiHighwayPosition pos = new RsiHighwayPosition("INFY", "NSE", 1500.0, 1400.0);
        pos.addTranche(new RsiHighwayTranche(1, 100, 1500.0, Instant.now(), "ORD_T1"));
        state.getPositions().put("INFY", pos);

        // Before update: pos value is 100 * 1500 = 150,000, Total Equity = 650,000
        assertThat(state.getTotalPortfolioEquity()).isEqualTo(650000.0);

        // Stock goes up to 1650
        pos.updateMarketPrice(1650.0);
        assertThat(pos.getUnrealizedPnl()).isEqualTo(15000.0);
        // Mark-to-market total portfolio equity reflects appreciation: 500,000 + 100 * 1650 =
        // 665,000
        assertThat(state.getTotalPortfolioEquity()).isEqualTo(665000.0);

        // Close position at 1700
        pos.closePosition(1700.0, "Target Hit", Instant.now());
        assertThat(pos.isActive()).isFalse();
        assertThat(pos.getExitPrice()).isEqualTo(1700.0);
        assertThat(pos.getExitReason()).isEqualTo("Target Hit");
        assertThat(pos.getRealizedPnl()).isEqualTo(20000.0);
    }
}

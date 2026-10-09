package com.tradingbot.strategy.commodity.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommodityBacktestMetricsTest {

    @Test
    @DisplayName("Round-trip cost = brokerage x 2 sides x lots + slippage x 2 sides x notional")
    void roundTripCostMath() {
        CommodityVwapProperties properties = new CommodityVwapProperties();
        CommodityCostModel costModel = CommodityCostModel.from(properties);

        // CRUDEOILM: qty 10 = 1 lot, notional = 5500 x 10 x 1.0 = 55,000
        // brokerage = 25 x 2 x 1 = 50 ; slippage = 5bps x 2 x 55000 = 55 ; total = 105
        CommodityTradePosition longPos =
                CommodityTradePosition.createLong(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5480.00"),
                        new BigDecimal("2.5"),
                        10,
                        Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(costModel.roundTripCost(longPos)).isEqualByComparingTo(new BigDecimal("105.00"));
    }

    @Test
    @DisplayName("Null/unknown position costs zero")
    void nullPositionCostsZero() {
        CommodityCostModel costModel = CommodityCostModel.from(null);
        assertThat(costModel.roundTripCost(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Metrics are computed net of costs with correct PF, DD and win rate")
    void metricsNetOfCosts() {
        // Winning long: +1000 gross -> net +895 (cost 105)
        CommodityTradePosition win =
                CommodityTradePosition.createLong(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5480.00"),
                        new BigDecimal("2.5"),
                        10,
                        Instant.parse("2026-10-01T10:00:00Z"));
        win.close(new BigDecimal("5600.00"), Instant.parse("2026-10-01T12:00:00Z"), "T", 1.0);

        // Losing short: -800 gross -> net -905 (cost 105)
        CommodityTradePosition loss =
                CommodityTradePosition.createShort(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5520.00"),
                        new BigDecimal("2.5"),
                        10,
                        Instant.parse("2026-10-02T10:00:00Z"));
        loss.close(new BigDecimal("5580.00"), Instant.parse("2026-10-02T12:00:00Z"), "S", 1.0);

        CommodityBacktestMetrics metrics =
                CommodityBacktestMetrics.compute(
                        "unit-test",
                        List.of(loss, win),
                        CommodityCostModel.from(new CommodityVwapProperties()));

        assertThat(metrics.totalTrades()).isEqualTo(2);
        assertThat(metrics.winningTrades()).isEqualTo(1);
        assertThat(metrics.winRatePct()).isEqualTo(50.0);
        assertThat(metrics.grossPnl()).isEqualTo(200.0);
        assertThat(metrics.totalCosts()).isEqualTo(210.0);
        assertThat(metrics.netPnl()).isEqualTo(-10.0);
        // net curve: +895 (peak 895) then -905 -> trough -10 -> drawdown = 905
        assertThat(metrics.maxDrawdownInr()).isEqualTo(905.0);
        assertThat(metrics.profitFactor()).isEqualTo(0.99); // 895 / 905
        assertThat(metrics.runLabel()).isEqualTo("unit-test");
    }

    @Test
    @DisplayName("Metrics serialize to parseable JSON")
    void writesJson(@TempDir Path tempDir) throws Exception {
        CommodityBacktestMetrics metrics =
                CommodityBacktestMetrics.compute(
                        "json-test", List.of(), CommodityCostModel.from(null));

        metrics.writeJson(tempDir, "commodity-vwap-test.json");

        Path out = tempDir.resolve("commodity-vwap-test.json");
        assertThat(Files.exists(out)).isTrue();
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toFile());
        assertThat(node.get("runLabel").asText()).isEqualTo("json-test");
        assertThat(node.get("totalTrades").asInt()).isEqualTo(0);
        assertThat(node.get("netPnl").asDouble()).isEqualTo(0.0);
    }
}

package com.tradingbot.strategy.rsihighway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RsiHighwayMarketBreadthServiceTest {

    private final RsiHighwayMarketBreadthService breadthService = new RsiHighwayMarketBreadthService();

    @Test
    void testHealthyMarketBreadthOpensHighway() {
        Map<String, List<Candle>> candlesMap = new HashMap<>();
        // Create 20 stocks at 52-week highs
        for (int i = 0; i < 20; i++) {
            candlesMap.put("STOCK_" + i, createCandlesNearHigh());
        }

        List<Candle> indexCandles = createIndexCandles(0.05); // Index only 5% below ATH
        MarketBreadthSnapshot snapshot = breadthService.evaluateBreadth(candlesMap, indexCandles, 5, 0.20);

        assertThat(snapshot.isHighwayOpen()).isTrue();
        assertThat(snapshot.leadersNear52WeekHighCount()).isGreaterThanOrEqualTo(15);
    }

    @Test
    void testSevereIndexDrawdownClosesHighway() {
        Map<String, List<Candle>> candlesMap = new HashMap<>();
        candlesMap.put("STOCK_1", createCandlesNearHigh());

        List<Candle> indexCandles = createIndexCandles(0.25); // Index 25% below ATH (> 20% limit)
        MarketBreadthSnapshot snapshot = breadthService.evaluateBreadth(candlesMap, indexCandles, 5, 0.20);

        assertThat(snapshot.isHighwayOpen()).isFalse();
        assertThat(snapshot.reason()).contains("Index Drawdown");
    }

    @Test
    void testPoorBreadthCountClosesHighway() {
        Map<String, List<Candle>> candlesMap = new HashMap<>();
        // Only 2 stocks near high, but min threshold is 5
        candlesMap.put("STOCK_1", createCandlesNearHigh());
        candlesMap.put("STOCK_2", createCandlesNearHigh());
        candlesMap.put("STOCK_3", createCandlesFarFromHigh());

        List<Candle> indexCandles = createIndexCandles(0.05);
        MarketBreadthSnapshot snapshot = breadthService.evaluateBreadth(candlesMap, indexCandles, 5, 0.20);

        assertThat(snapshot.isHighwayOpen()).isFalse();
        assertThat(snapshot.leadersNear52WeekHighCount()).isEqualTo(2);
        assertThat(snapshot.reason()).contains("Insufficient 52-Week High Leaders");
    }

    private List<Candle> createCandlesNearHigh() {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            list.add(new Candle("SYM", "D", Instant.now().minusSeconds((250 - i) * 86400L),
                    BigDecimal.valueOf(100 + i), BigDecimal.valueOf(102 + i), BigDecimal.valueOf(99 + i), BigDecimal.valueOf(101 + i), 10000L));
        }
        return list;
    }

    private List<Candle> createCandlesFarFromHigh() {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            // Price fell from 500 to 200 (far from 52-week high)
            double price = 500.0 - i;
            list.add(new Candle("SYM", "D", Instant.now().minusSeconds((250 - i) * 86400L),
                    BigDecimal.valueOf(price), BigDecimal.valueOf(price + 2), BigDecimal.valueOf(price - 2), BigDecimal.valueOf(price), 10000L));
        }
        return list;
    }

    private List<Candle> createIndexCandles(double drawdownPct) {
        List<Candle> list = new ArrayList<>();
        list.add(new Candle("NIFTY_MIDCAP", "D", Instant.now().minusSeconds(864000L),
                BigDecimal.valueOf(50000), BigDecimal.valueOf(55000), BigDecimal.valueOf(49000), BigDecimal.valueOf(55000), 100000L)); // ATH 55000
        double currentPrice = 55000 * (1.0 - drawdownPct);
        list.add(new Candle("NIFTY_MIDCAP", "D", Instant.now(),
                BigDecimal.valueOf(currentPrice), BigDecimal.valueOf(currentPrice + 100), BigDecimal.valueOf(currentPrice - 100), BigDecimal.valueOf(currentPrice), 100000L));
        return list;
    }
}

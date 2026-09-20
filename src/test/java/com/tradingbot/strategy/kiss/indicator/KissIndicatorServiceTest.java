package com.tradingbot.strategy.kiss.indicator;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.positional.indicator.HeikinAshiCandle;
import com.tradingbot.strategy.kiss.config.KissStrategyConfig;
import com.tradingbot.strategy.kiss.model.KissSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KissIndicatorServiceTest {

    private TechnicalAnalysisService taService;
    private KissStrategyConfig config;
    private KissIndicatorService kissIndicatorService;

    @BeforeEach
    void setUp() {
        taService = new TechnicalAnalysisService();
        config = new KissStrategyConfig();
        config.setEmaPeriod(10); // Use 10 period for fast unit tests
        config.setMacdFastPeriod(5);
        config.setMacdSlowPeriod(10);
        config.setMacdSignalPeriod(3);
        kissIndicatorService = new KissIndicatorService(taService, config);
    }

    @Test
    void testHeikinAshiConversion() {
        List<Candle> regular = new ArrayList<>();
        Instant now = Instant.parse("2026-10-05T09:15:00Z");

        regular.add(
                new Candle(
                        "CRUDEOIL",
                        "60",
                        now,
                        BigDecimal.valueOf(6000),
                        BigDecimal.valueOf(6050),
                        BigDecimal.valueOf(5980),
                        BigDecimal.valueOf(6020),
                        1000));
        regular.add(
                new Candle(
                        "CRUDEOIL",
                        "60",
                        now.plusSeconds(3600),
                        BigDecimal.valueOf(6020),
                        BigDecimal.valueOf(6080),
                        BigDecimal.valueOf(6010),
                        BigDecimal.valueOf(6070),
                        1200));

        List<HeikinAshiCandle> haList = kissIndicatorService.calculateHeikinAshi(regular);
        assertEquals(2, haList.size());
        assertEquals(6012.5, haList.get(0).close().doubleValue(), 0.01);
        assertEquals(6010.0, haList.get(0).open().doubleValue(), 0.01);
    }

    @Test
    void testBullishSetupDetection() {
        List<Candle> hourly = new ArrayList<>();
        Instant now = Instant.parse("2026-10-05T09:15:00Z");

        // Generate 30 strongly rising candles
        for (int i = 0; i < 30; i++) {
            double price = 5000 + (i * 25);
            hourly.add(
                    new Candle(
                            "CRUDEOIL",
                            "60",
                            now.plusSeconds(i * 3600),
                            BigDecimal.valueOf(price - 10),
                            BigDecimal.valueOf(price + 20),
                            BigDecimal.valueOf(price - 15),
                            BigDecimal.valueOf(price + 15),
                            1000 + i * 50));
        }

        List<Candle> weekly = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double price = 5000 + (i * 100);
            weekly.add(
                    new Candle(
                            "CRUDEOIL",
                            "W",
                            now.plusSeconds(i * 7 * 86400),
                            BigDecimal.valueOf(price),
                            BigDecimal.valueOf(price + 100),
                            BigDecimal.valueOf(price - 20),
                            BigDecimal.valueOf(price + 80),
                            50000));
        }

        KissSnapshot snapshot = kissIndicatorService.computeSnapshot("CRUDEOIL", hourly, weekly);
        assertNotNull(snapshot);
        assertTrue(snapshot.weeklyHaBullish());
        assertTrue(snapshot.emaSlopeBullish());
        assertTrue(snapshot.isBullishSetup());
        assertFalse(snapshot.isBearishSetup());
        assertTrue(snapshot.suggestedTarget() > snapshot.currentPrice());
        assertTrue(snapshot.suggestedSl() < snapshot.currentPrice());
    }

    @Test
    void testBearishSetupDetection() {
        List<Candle> hourly = new ArrayList<>();
        Instant now = Instant.parse("2026-10-05T09:15:00Z");

        // Generate 30 strongly falling candles
        for (int i = 0; i < 30; i++) {
            double price = 6000 - (i * 25);
            hourly.add(
                    new Candle(
                            "CRUDEOIL",
                            "60",
                            now.plusSeconds(i * 3600),
                            BigDecimal.valueOf(price + 10),
                            BigDecimal.valueOf(price + 15),
                            BigDecimal.valueOf(price - 20),
                            BigDecimal.valueOf(price - 15),
                            1000 + i * 50));
        }

        List<Candle> weekly = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double price = 6000 - (i * 100);
            weekly.add(
                    new Candle(
                            "CRUDEOIL",
                            "W",
                            now.plusSeconds(i * 7 * 86400),
                            BigDecimal.valueOf(price),
                            BigDecimal.valueOf(price + 20),
                            BigDecimal.valueOf(price - 100),
                            BigDecimal.valueOf(price - 80),
                            50000));
        }

        KissSnapshot snapshot = kissIndicatorService.computeSnapshot("CRUDEOIL", hourly, weekly);
        assertNotNull(snapshot);
        assertFalse(snapshot.weeklyHaBullish());
        assertFalse(snapshot.emaSlopeBullish());
        assertTrue(snapshot.isBearishSetup());
        assertFalse(snapshot.isBullishSetup());
        assertTrue(snapshot.suggestedTarget() < snapshot.currentPrice());
        assertTrue(snapshot.suggestedSl() > snapshot.currentPrice());
    }
}

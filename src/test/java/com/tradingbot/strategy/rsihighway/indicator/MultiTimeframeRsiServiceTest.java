package com.tradingbot.strategy.rsihighway.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.MultiTimeframeRsiSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiTimeframeRsiServiceTest {

    private MultiTimeframeRsiService rsiService;

    @BeforeEach
    void setUp() {
        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        PriceActionPatternDetector detector = new PriceActionPatternDetector();
        rsiService = new MultiTimeframeRsiService(taService, detector);
    }

    @Test
    void testComputeSnapshotWithTrendingSeries() {
        List<Candle> dailyCandles = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-01-01T10:00:00Z");

        // Generate 600 daily candles in steady uptrend (approx 20 months)
        for (int i = 0; i < 600; i++) {
            double price = 100.0 + i * 2.0;
            dailyCandles.add(new Candle(
                    "BEL",
                    "D",
                    baseTime.plusSeconds(i * 86400L),
                    BigDecimal.valueOf(price - 1),
                    BigDecimal.valueOf(price + 2),
                    BigDecimal.valueOf(price - 2),
                    BigDecimal.valueOf(price),
                    10000L
            ));
        }

        MultiTimeframeRsiSnapshot snapshot = rsiService.computeSnapshot("BEL", dailyCandles);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.symbol()).isEqualTo("BEL");
        assertThat(snapshot.monthlyRsi()).isGreaterThanOrEqualTo(60.0);
        assertThat(snapshot.weeklyRsi()).isGreaterThanOrEqualTo(60.0);
        assertThat(snapshot.dailyRsi()).isGreaterThan(50.0);
        assertThat(snapshot.dailyAtr()).isGreaterThan(0.0);
        assertThat(snapshot.currentPrice()).isEqualTo(dailyCandles.get(599).close().doubleValue());
    }

    @Test
    void testComputeSnapshotHandlesInsufficientData() {
        List<Candle> smallList = List.of(
                new Candle("BEL", "D", Instant.now(), BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(95), BigDecimal.valueOf(102), 1000L)
        );
        MultiTimeframeRsiSnapshot snapshot = rsiService.computeSnapshot("BEL", smallList);
        assertThat(snapshot).isNull();
    }
}

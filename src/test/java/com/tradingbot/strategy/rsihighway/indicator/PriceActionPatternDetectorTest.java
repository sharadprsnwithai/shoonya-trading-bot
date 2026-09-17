package com.tradingbot.strategy.rsihighway.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PriceActionPatternDetectorTest {

    private final PriceActionPatternDetector detector = new PriceActionPatternDetector();

    @Test
    void testBullishEngulfingPattern() {
        Candle prevRed = new Candle("TATAMOTORS", "D", Instant.parse("2026-03-01T10:00:00Z"),
                BigDecimal.valueOf(100), BigDecimal.valueOf(102), BigDecimal.valueOf(95), BigDecimal.valueOf(96), 1000L);
        Candle currGreen = new Candle("TATAMOTORS", "D", Instant.parse("2026-03-02T10:00:00Z"),
                BigDecimal.valueOf(95), BigDecimal.valueOf(105), BigDecimal.valueOf(94), BigDecimal.valueOf(104), 2000L);

        Optional<PriceActionPattern> pattern = detector.detectPattern(List.of(prevRed, currGreen), 5.0);
        assertThat(pattern).isPresent().contains(PriceActionPattern.BULLISH_ENGULFING);
    }

    @Test
    void testHammerPinbarPattern() {
        Candle prev = new Candle("INFY", "D", Instant.parse("2026-03-01T10:00:00Z"),
                BigDecimal.valueOf(1500), BigDecimal.valueOf(1510), BigDecimal.valueOf(1490), BigDecimal.valueOf(1495), 1000L);
        // Hammer: Open 1490, High 1502, Low 1450, Close 1500 -> Body = 10, Lower Wick = 40 (4x body), Upper Wick = 2
        Candle hammer = new Candle("INFY", "D", Instant.parse("2026-03-02T10:00:00Z"),
                BigDecimal.valueOf(1490), BigDecimal.valueOf(1502), BigDecimal.valueOf(1450), BigDecimal.valueOf(1500), 2500L);

        Optional<PriceActionPattern> pattern = detector.detectPattern(List.of(prev, hammer), 20.0);
        assertThat(pattern).isPresent().contains(PriceActionPattern.HAMMER);
    }

    @Test
    void testMomentumExpansionPattern() {
        Candle prev = new Candle("SBIN", "D", Instant.parse("2026-03-01T10:00:00Z"),
                BigDecimal.valueOf(800), BigDecimal.valueOf(805), BigDecimal.valueOf(795), BigDecimal.valueOf(802), 1000L);
        // ATR = 10. Range = 830 - 800 = 30 (3.0x ATR >= 1.2x). Close 828 is in top 25% (range: 800 to 830)
        Candle expansion = new Candle("SBIN", "D", Instant.parse("2026-03-02T10:00:00Z"),
                BigDecimal.valueOf(801), BigDecimal.valueOf(830), BigDecimal.valueOf(800), BigDecimal.valueOf(828), 5000L);

        Optional<PriceActionPattern> pattern = detector.detectPattern(List.of(prev, expansion), 10.0);
        assertThat(pattern).isPresent().contains(PriceActionPattern.MOMENTUM_EXPANSION);
    }

    @Test
    void testHorizontalBreakoutPattern() {
        List<Candle> candles = List.of(
                new Candle("HDFC", "D", Instant.parse("2026-03-01T10:00:00Z"), BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(98), BigDecimal.valueOf(102), 1000L),
                new Candle("HDFC", "D", Instant.parse("2026-03-02T10:00:00Z"), BigDecimal.valueOf(102), BigDecimal.valueOf(104), BigDecimal.valueOf(99), BigDecimal.valueOf(101), 1000L),
                new Candle("HDFC", "D", Instant.parse("2026-03-03T10:00:00Z"), BigDecimal.valueOf(101), BigDecimal.valueOf(105), BigDecimal.valueOf(100), BigDecimal.valueOf(103), 1000L),
                new Candle("HDFC", "D", Instant.parse("2026-03-04T10:00:00Z"), BigDecimal.valueOf(103), BigDecimal.valueOf(104), BigDecimal.valueOf(100), BigDecimal.valueOf(102), 1000L),
                new Candle("HDFC", "D", Instant.parse("2026-03-05T10:00:00Z"), BigDecimal.valueOf(102), BigDecimal.valueOf(105), BigDecimal.valueOf(101), BigDecimal.valueOf(104), 1000L),
                // Breakout above max high (105)
                new Candle("HDFC", "D", Instant.parse("2026-03-06T10:00:00Z"), BigDecimal.valueOf(104), BigDecimal.valueOf(110), BigDecimal.valueOf(103), BigDecimal.valueOf(108), 3000L)
        );

        Optional<PriceActionPattern> pattern = detector.detectPattern(candles, 3.0);
        assertThat(pattern).isPresent();
    }

    @Test
    void testRsi50BounceDetection() {
        // Pullback to 52, then bounce to 56
        List<Double> rsiSeries = List.of(65.0, 58.0, 52.0, 56.5);
        assertThat(detector.isRsi50BounceOrCross(rsiSeries)).isTrue();
    }

    @Test
    void testRsi50CrossoverDetection() {
        // Was below 50 (46.0), now crosses cleanly above 50 (52.0)
        List<Double> rsiSeries = List.of(45.0, 46.0, 52.0);
        assertThat(detector.isRsi50BounceOrCross(rsiSeries)).isTrue();
    }

    @Test
    void testRsi50InvalidWhenDropping() {
        // RSI falling from 52 to 47
        List<Double> rsiSeries = List.of(60.0, 55.0, 52.0, 47.0);
        assertThat(detector.isRsi50BounceOrCross(rsiSeries)).isFalse();
    }

    @Test
    void testRsi50RejectedWhenOverbought() {
        // Previous dipped to 51, but current spiked to 72 (overbought)
        List<Double> rsiSeries = List.of(60.0, 51.0, 72.0);
        assertThat(detector.isRsi50BounceOrCross(rsiSeries)).isFalse();
    }
}

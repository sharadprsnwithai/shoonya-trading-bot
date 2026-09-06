package com.tradingbot.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandleResamplingUtilTest {

    @Test
    void testResampleDailyToWeeklyAggregatesMondayThroughFriday() {
        List<Candle> dailyCandles = new ArrayList<>();

        // Week 1: Mon 05-Oct-2026 to Fri 09-Oct-2026 (5 daily bars)
        Instant mon = Instant.parse("2026-10-05T03:45:00Z"); // Monday
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        mon,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(105),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(102),
                        1000));
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        mon.plusSeconds(86400),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(110),
                        BigDecimal.valueOf(101),
                        BigDecimal.valueOf(108),
                        1200));
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        mon.plusSeconds(2 * 86400),
                        BigDecimal.valueOf(108),
                        BigDecimal.valueOf(112),
                        BigDecimal.valueOf(106),
                        BigDecimal.valueOf(107),
                        800));
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        mon.plusSeconds(3 * 86400),
                        BigDecimal.valueOf(107),
                        BigDecimal.valueOf(115),
                        BigDecimal.valueOf(105),
                        BigDecimal.valueOf(114),
                        1500));
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        mon.plusSeconds(4 * 86400),
                        BigDecimal.valueOf(114),
                        BigDecimal.valueOf(118),
                        BigDecimal.valueOf(112),
                        BigDecimal.valueOf(116),
                        2000));

        // Week 2: Mon 12-Oct-2026 to Wed 14-Oct-2026 (3 daily bars)
        Instant nextMon = Instant.parse("2026-10-12T03:45:00Z");
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        nextMon,
                        BigDecimal.valueOf(116),
                        BigDecimal.valueOf(120),
                        BigDecimal.valueOf(115),
                        BigDecimal.valueOf(118),
                        1100));
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        nextMon.plusSeconds(86400),
                        BigDecimal.valueOf(118),
                        BigDecimal.valueOf(122),
                        BigDecimal.valueOf(117),
                        BigDecimal.valueOf(121),
                        1300));
        dailyCandles.add(
                new Candle(
                        "RELIANCE",
                        "D",
                        nextMon.plusSeconds(2 * 86400),
                        BigDecimal.valueOf(121),
                        BigDecimal.valueOf(125),
                        BigDecimal.valueOf(120),
                        BigDecimal.valueOf(124),
                        1400));

        List<Candle> weekly = CandleResamplingUtil.resampleDailyToWeekly(dailyCandles);

        assertThat(weekly).hasSize(2);

        // Verify Week 1
        Candle w1 = weekly.get(0);
        assertThat(w1.open()).isEqualByComparingTo(BigDecimal.valueOf(100)); // Mon Open
        assertThat(w1.high()).isEqualByComparingTo(BigDecimal.valueOf(118)); // Max High of week
        assertThat(w1.low()).isEqualByComparingTo(BigDecimal.valueOf(98)); // Min Low of week
        assertThat(w1.close()).isEqualByComparingTo(BigDecimal.valueOf(116)); // Fri Close
        assertThat(w1.volume()).isEqualTo(1000 + 1200 + 800 + 1500 + 2000); // 6500

        // Verify Week 2
        Candle w2 = weekly.get(1);
        assertThat(w2.open()).isEqualByComparingTo(BigDecimal.valueOf(116));
        assertThat(w2.high()).isEqualByComparingTo(BigDecimal.valueOf(125));
        assertThat(w2.low()).isEqualByComparingTo(BigDecimal.valueOf(115));
        assertThat(w2.close()).isEqualByComparingTo(BigDecimal.valueOf(124));
        assertThat(w2.volume()).isEqualTo(1100 + 1300 + 1400); // 3800
    }

    @Test
    void testResampleEmptyOrNullReturnsEmpty() {
        assertThat(CandleResamplingUtil.resampleDailyToWeekly(null)).isEmpty();
        assertThat(CandleResamplingUtil.resampleDailyToWeekly(List.of())).isEmpty();
    }
}

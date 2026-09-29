package com.tradingbot.strategy.car;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.model.CarAnalysisResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Mathematical engine for Cumulative Average Reversal (CAR) computation.
 */
@Component
public class CarCalculator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final int requiredPositiveDays;
    private final int lookbackDays;

    public CarCalculator() {
        this(10, 252);
    }

    public CarCalculator(int requiredPositiveDays, int lookbackDays) {
        this.requiredPositiveDays = requiredPositiveDays > 0 ? requiredPositiveDays : 10;
        this.lookbackDays = lookbackDays > 0 ? lookbackDays : 252;
    }

    public CarAnalysisResult analyze(String symbol, List<Candle> dailyCandles) {
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return new CarAnalysisResult(
                    symbol, false, 0, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        int startIdx = Math.max(0, dailyCandles.size() - lookbackDays);
        List<Candle> window = dailyCandles.subList(startIdx, dailyCandles.size());

        // 1. Find 52-Week Highest Close Anchor
        int anchorIdx = 0;
        BigDecimal highestClose = BigDecimal.ZERO;
        for (int i = 0; i < window.size(); i++) {
            BigDecimal c = window.get(i).close();
            if (c != null && c.compareTo(highestClose) > 0) {
                highestClose = c;
                anchorIdx = i;
            }
        }

        Candle anchorCandle = window.get(anchorIdx);
        LocalDate anchorDate = LocalDate.ofInstant(anchorCandle.timestamp(), IST);
        List<Candle> postAnchorCandles = window.subList(anchorIdx, window.size());

        List<BigDecimal> closes = postAnchorCandles.stream().map(Candle::close).toList();
        List<BigDecimal> cumAverages = calculateCumulativeAverages(closes);

        // 2. Count consecutive positive days at tail
        int streak = 0;
        for (int i = cumAverages.size() - 1; i >= 1; i--) {
            if (cumAverages.get(i).compareTo(cumAverages.get(i - 1)) > 0) {
                streak++;
            } else {
                break;
            }
        }

        boolean isCarPositive = streak >= requiredPositiveDays;
        BigDecimal latestClose = closes.get(closes.size() - 1);
        BigDecimal latestCumAvg = cumAverages.get(cumAverages.size() - 1);

        return new CarAnalysisResult(
                symbol,
                isCarPositive,
                streak,
                highestClose,
                anchorDate,
                latestClose,
                latestCumAvg,
                postAnchorCandles.size());
    }

    public List<BigDecimal> calculateCumulativeAverages(List<BigDecimal> closes) {
        List<BigDecimal> result = new ArrayList<>();
        if (closes == null || closes.isEmpty()) {
            return result;
        }

        BigDecimal runningSum = BigDecimal.ZERO;
        for (int i = 0; i < closes.size(); i++) {
            runningSum = runningSum.add(closes.get(i));
            BigDecimal count = BigDecimal.valueOf(i + 1);
            BigDecimal avg = runningSum.divide(count, 2, RoundingMode.HALF_UP);
            result.add(avg);
        }
        return result;
    }
}

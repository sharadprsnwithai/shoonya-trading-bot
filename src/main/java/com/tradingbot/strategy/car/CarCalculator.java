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

/** Mathematical engine for Cumulative Average Reversal (CAR) computation. */
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

    /**
     * Wires the two previously dead tuning knobs ({@code car-positive-days} and {@code
     * high-lookback-days}) into the calculation, so operators no longer have to believe the
     * configuration takes effect while the code silently hardcodes 10 / 252.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CarCalculator(com.tradingbot.strategy.car.config.CarWeeklyProperties properties) {
        this(
                properties != null ? properties.getCarPositiveDays() : 10,
                properties != null ? properties.getHighLookbackDays() : 252);
    }

    public CarAnalysisResult analyze(String symbol, List<Candle> dailyCandles) {
        return analyze(symbol, dailyCandles, CarWeekCalendar.activeWeekStart());
    }

    /**
     * @param asOf date used to derive the completed-week window for {@code lastWeekHigh}; pass the
     *     active week's Monday so a weekday run does not report candles from the week in progress
     */
    public CarAnalysisResult analyze(String symbol, List<Candle> dailyCandles, LocalDate asOf) {
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

        // 3. High of the last completed week (date-driven, so it stays correct on a 4-session
        //    holiday week and on weekday runs where trailing candles belong to the running week)
        BigDecimal lastWeekHigh = BigDecimal.ZERO;
        List<Candle> lastWeekCandles = CarWeekCalendar.previousCompletedWeek(dailyCandles, asOf);
        if (lastWeekCandles.isEmpty()) {
            int last5Start = Math.max(0, dailyCandles.size() - 5);
            lastWeekCandles = dailyCandles.subList(last5Start, dailyCandles.size());
        }
        for (Candle c : lastWeekCandles) {
            if (c != null && c.high() != null && c.high().compareTo(lastWeekHigh) > 0) {
                lastWeekHigh = c.high();
            }
        }
        if (lastWeekHigh.compareTo(BigDecimal.ZERO) <= 0) {
            lastWeekHigh = latestClose;
        }

        return new CarAnalysisResult(
                symbol,
                isCarPositive,
                streak,
                highestClose,
                anchorDate,
                latestClose,
                latestCumAvg,
                postAnchorCandles.size(),
                lastWeekHigh);
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

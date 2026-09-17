package com.tradingbot.strategy.rsihighway.indicator;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.model.MultiTimeframeRsiSnapshot;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import com.tradingbot.util.CandleResamplingUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service that resamples daily candles into weekly and monthly bars, calculates Wilder's RSI(14)
 * across all three timeframes, computes daily ATR(14), and detects price action confirmation patterns.
 */
@Service
public class MultiTimeframeRsiService {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeRsiService.class);
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int MIN_DAILY_CANDLES = 30; // Minimum to calculate RSI

    private final TechnicalAnalysisService taService;
    private final PriceActionPatternDetector patternDetector;
    private final RsiHighwayConfig config;

    public MultiTimeframeRsiService(
            TechnicalAnalysisService taService,
            PriceActionPatternDetector patternDetector) {
        this(taService, patternDetector, null);
    }

    @Autowired
    public MultiTimeframeRsiService(
            TechnicalAnalysisService taService,
            PriceActionPatternDetector patternDetector,
            @Autowired(required = false) RsiHighwayConfig config) {
        this.taService = taService;
        this.patternDetector = patternDetector;
        this.config = config;
    }

    /**
     * Computes the Multi-Timeframe RSI snapshot for a symbol given its chronological daily candles.
     *
     * @param symbol Symbol name (e.g. "RELIANCE")
     * @param dailyCandles Chronological list of daily candles (recommended 250+ bars)
     * @return MultiTimeframeRsiSnapshot or null if insufficient data
     */
    public MultiTimeframeRsiSnapshot computeSnapshot(String symbol, List<Candle> dailyCandles) {
        if (dailyCandles == null || dailyCandles.size() < MIN_DAILY_CANDLES) {
            log.debug("[RSI-HIGHWAY] Insufficient daily candles for {}: {}", symbol,
                    dailyCandles != null ? dailyCandles.size() : 0);
            return null;
        }

        // 1. Resample to Weekly and Monthly
        List<Candle> weeklyCandles = CandleResamplingUtil.resampleDailyToWeekly(dailyCandles);
        List<Candle> monthlyCandles = CandleResamplingUtil.resampleDailyToMonthly(dailyCandles);

        // 2. Extract price arrays for Daily series
        int dailySize = dailyCandles.size();
        double[] dailyHigh = new double[dailySize];
        double[] dailyLow = new double[dailySize];
        double[] dailyClose = new double[dailySize];

        for (int i = 0; i < dailySize; i++) {
            Candle c = dailyCandles.get(i);
            dailyHigh[i] = c.high().doubleValue();
            dailyLow[i] = c.low().doubleValue();
            dailyClose[i] = c.close().doubleValue();
        }

        // 3. Compute Daily RSI series & Daily ATR
        double[] dailyRsiSeries = taService.calculateRsiSeries(dailyClose, RSI_PERIOD);
        double[] dailyAtrSeries = taService.calculateAtrSeries(dailyHigh, dailyLow, dailyClose, ATR_PERIOD);

        double latestDailyRsi = getLatestValidValue(dailyRsiSeries);
        double latestDailyAtr = getLatestValidValue(dailyAtrSeries);

        // 4. Compute Weekly RSI
        double latestWeeklyRsi = Double.NaN;
        if (weeklyCandles != null && weeklyCandles.size() >= RSI_PERIOD + 1) {
            double[] weeklyClose = weeklyCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            latestWeeklyRsi = taService.calculateLatestRsi(weeklyClose, RSI_PERIOD);
        }

        // 5. Compute Monthly RSI
        double latestMonthlyRsi = Double.NaN;
        if (monthlyCandles != null && monthlyCandles.size() >= RSI_PERIOD + 1) {
            double[] monthlyClose = monthlyCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            latestMonthlyRsi = taService.calculateLatestRsi(monthlyClose, RSI_PERIOD);
        }

        // 6. Extract recent Daily RSI series for bounce/crossover detection
        List<Double> validDailyRsis = new ArrayList<>();
        for (double v : dailyRsiSeries) {
            if (!Double.isNaN(v)) {
                validDailyRsis.add(v);
            }
        }

        // 7. Detect Price Action Pattern on latest daily candle
        double minMonthly = config != null ? config.getMonthlyRsiThreshold() : 60.0;
        double minWeekly = config != null ? config.getWeeklyRsiThreshold() : 60.0;
        double dailyLower = config != null ? config.getDailyRsiLower() : 48.0;
        double dailyUpper = config != null ? config.getDailyRsiUpper() : 55.0;

        Optional<PriceActionPattern> pattern = patternDetector.detectPattern(dailyCandles, latestDailyAtr);
        boolean isRsiBounceOrCross = patternDetector.isRsi50BounceOrCross(validDailyRsis, dailyLower, dailyUpper, 50.0, 65.0);

        Candle signalCandle = dailyCandles.get(dailySize - 1);
        double currentPrice = signalCandle.close().doubleValue();
        double signalHigh = signalCandle.high().doubleValue();
        double signalLow = signalCandle.low().doubleValue();
        Instant timestamp = signalCandle.timestamp();

        boolean isHighwayCandidate = (!Double.isNaN(latestMonthlyRsi) && latestMonthlyRsi >= minMonthly)
                && (!Double.isNaN(latestWeeklyRsi) && latestWeeklyRsi >= minWeekly);

        boolean isDailySetupValid = isRsiBounceOrCross && pattern.isPresent();

        return new MultiTimeframeRsiSnapshot(
                symbol,
                latestMonthlyRsi,
                latestWeeklyRsi,
                latestDailyRsi,
                latestDailyAtr,
                currentPrice,
                signalHigh,
                signalLow,
                pattern,
                isHighwayCandidate,
                isDailySetupValid,
                timestamp
        );
    }

    private double getLatestValidValue(double[] series) {
        if (series == null) return Double.NaN;
        for (int i = series.length - 1; i >= 0; i--) {
            if (!Double.isNaN(series[i])) {
                return series[i];
            }
        }
        return Double.NaN;
    }
}

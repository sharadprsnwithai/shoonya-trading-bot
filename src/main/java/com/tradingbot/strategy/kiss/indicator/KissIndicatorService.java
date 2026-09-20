package com.tradingbot.strategy.kiss.indicator;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.MacdResult;
import com.tradingbot.positional.indicator.HeikinAshiCandle;
import com.tradingbot.strategy.kiss.config.KissStrategyConfig;
import com.tradingbot.strategy.kiss.model.KissSnapshot;
import com.tradingbot.util.CandleResamplingUtil;
import com.tradingbot.util.CommodityRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Calculates Multi-Timeframe Heikin-Ashi indicators for the KISS Strategy: - Weekly Heikin-Ashi
 * trend filter - 1-Hour 55 EMA High/Low Envelope & 55 Directional Slope - 1-Hour MACD (12, 26, 9)
 * zero-line trigger
 */
@Service
public class KissIndicatorService {

    private final TechnicalAnalysisService taService;
    private final KissStrategyConfig config;

    public KissIndicatorService(TechnicalAnalysisService taService, KissStrategyConfig config) {
        this.taService = taService;
        this.config = config;
    }

    /** Converts standard OHLC candles to Heikin-Ashi candles. */
    public List<HeikinAshiCandle> calculateHeikinAshi(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        int n = candles.size();
        List<HeikinAshiCandle> haList = new ArrayList<>(n);

        Candle first = candles.get(0);
        double fOpen = first.open() != null ? first.open().doubleValue() : 0.0;
        double fHigh = first.high() != null ? first.high().doubleValue() : fOpen;
        double fLow = first.low() != null ? first.low().doubleValue() : fOpen;
        double fClose = first.close() != null ? first.close().doubleValue() : fOpen;

        double haClose0 = (fOpen + fHigh + fLow + fClose) / 4.0;
        double haOpen0 = (fOpen + fClose) / 2.0;
        double haHigh0 = Math.max(fHigh, Math.max(haOpen0, haClose0));
        double haLow0 = Math.min(fLow, Math.min(haOpen0, haClose0));

        haList.add(
                new HeikinAshiCandle(
                        first.timestamp(),
                        first.formattedTime(),
                        BigDecimal.valueOf(haOpen0).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(haHigh0).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(haLow0).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(haClose0).setScale(2, RoundingMode.HALF_UP),
                        first.volume()));

        double prevHaOpen = haOpen0;
        double prevHaClose = haClose0;

        for (int i = 1; i < n; i++) {
            Candle curr = candles.get(i);
            double cOpen = curr.open() != null ? curr.open().doubleValue() : prevHaClose;
            double cHigh = curr.high() != null ? curr.high().doubleValue() : cOpen;
            double cLow = curr.low() != null ? curr.low().doubleValue() : cOpen;
            double cClose = curr.close() != null ? curr.close().doubleValue() : cOpen;

            double currHaClose = (cOpen + cHigh + cLow + cClose) / 4.0;
            double currHaOpen = (prevHaOpen + prevHaClose) / 2.0;
            double currHaHigh = Math.max(cHigh, Math.max(currHaOpen, currHaClose));
            double currHaLow = Math.min(cLow, Math.min(currHaOpen, currHaClose));

            haList.add(
                    new HeikinAshiCandle(
                            curr.timestamp(),
                            curr.formattedTime(),
                            BigDecimal.valueOf(currHaOpen).setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(currHaHigh).setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(currHaLow).setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(currHaClose).setScale(2, RoundingMode.HALF_UP),
                            curr.volume()));

            prevHaOpen = currHaOpen;
            prevHaClose = currHaClose;
        }

        return haList;
    }

    /**
     * Computes the complete KISS multi-timeframe technical indicator snapshot.
     *
     * @param symbol Ticker symbol
     * @param hourlyCandles Chronological 1-Hour candles
     * @param dailyOrWeeklyCandles Chronological Daily or Weekly candles
     * @return KissSnapshot containing indicator values and setup classification
     */
    public KissSnapshot computeSnapshot(
            String symbol, List<Candle> hourlyCandles, List<Candle> dailyOrWeeklyCandles) {

        if (hourlyCandles == null || hourlyCandles.size() < config.getEmaPeriod() + 5) {
            return null;
        }

        // 1. Weekly Heikin-Ashi Trend Filter
        boolean weeklyHaBullish = evaluateWeeklyTrend(dailyOrWeeklyCandles);

        // 2. 1-Hour Heikin-Ashi conversion
        List<HeikinAshiCandle> haList = calculateHeikinAshi(hourlyCandles);
        int size = haList.size();
        if (size < config.getEmaPeriod() + 5) {
            return null;
        }

        double[] haHigh = new double[size];
        double[] haLow = new double[size];
        double[] haClose = new double[size];

        for (int i = 0; i < size; i++) {
            HeikinAshiCandle ha = haList.get(i);
            haHigh[i] = ha.high().doubleValue();
            haLow[i] = ha.low().doubleValue();
            haClose[i] = ha.close().doubleValue();
        }

        // 3. 55 EMA High / Low Envelope and Signal EMA Slope
        int emaPeriod = config.getEmaPeriod();
        double[] emaHighSeries = taService.calculateEmaSeries(haHigh, emaPeriod);
        double[] emaLowSeries = taService.calculateEmaSeries(haLow, emaPeriod);
        double[] emaSignalSeries = taService.calculateEmaSeries(haClose, emaPeriod);

        int last = size - 1;
        int prev = size - 2;

        double currentEmaHigh = emaHighSeries[last];
        double currentEmaLow = emaLowSeries[last];
        double currentEmaSignal = emaSignalSeries[last];
        double prevEmaSignal = emaSignalSeries[prev];

        if (Double.isNaN(currentEmaHigh)
                || Double.isNaN(currentEmaLow)
                || Double.isNaN(currentEmaSignal)) {
            return null;
        }

        boolean emaSlopeBullish = currentEmaSignal > prevEmaSignal;

        // 4. MACD (12, 26, 9)
        MacdResult[] macdSeries =
                taService.calculateMacdSeries(
                        haClose,
                        config.getMacdFastPeriod(),
                        config.getMacdSlowPeriod(),
                        config.getMacdSignalPeriod());

        MacdResult latestMacd = macdSeries[last];
        double macdLine = latestMacd.macd();
        double macdSignal = latestMacd.signal();
        double macdHist = latestMacd.hist();

        // 5. Current price & Heikin-Ashi metrics
        HeikinAshiCandle latestHa = haList.get(last);
        Candle latestRealCandle = hourlyCandles.get(last);
        double currentPrice = latestRealCandle.close().doubleValue();

        double haOpen = latestHa.open().doubleValue();
        double haHighVal = latestHa.high().doubleValue();
        double haLowVal = latestHa.low().doubleValue();
        double haCloseVal = latestHa.close().doubleValue();

        // 6. Envelope Consolidation / Chop Check
        boolean inBand = haCloseVal >= currentEmaLow && haCloseVal <= currentEmaHigh;

        // 7. Entry Setup Logic (Breakout / Retest / Fresh Momentum - not overextended)
        double prevHaClose = haList.get(prev).close().doubleValue();
        double prevEmaHigh = emaHighSeries[prev];
        double prevEmaLow = emaLowSeries[prev];

        boolean freshBullishBreakout = prevHaClose <= prevEmaHigh * 1.005;
        boolean withinBullishBandProximity = haCloseVal <= currentEmaHigh * 1.04;
        boolean freshMacdBullishCross =
                macdLine >= macdSignal
                        && (macdSeries[prev].macd() <= macdSeries[prev].signal()
                                || (prev > 0
                                        && macdSeries[prev - 1].macd()
                                                <= macdSeries[prev - 1].signal()));

        boolean isBullishSetup =
                weeklyHaBullish
                        && (haCloseVal > currentEmaHigh)
                        && emaSlopeBullish
                        && !Double.isNaN(macdLine)
                        && !Double.isNaN(macdSignal)
                        && (macdLine >= macdSignal)
                        && (macdLine > 0)
                        && (freshBullishBreakout
                                || withinBullishBandProximity
                                || freshMacdBullishCross);

        boolean freshBearishBreakdown = prevHaClose >= prevEmaLow * 0.995;
        boolean withinBearishBandProximity = haCloseVal >= currentEmaLow * 0.96;
        boolean freshMacdBearishCross =
                macdLine <= macdSignal
                        && (macdSeries[prev].macd() >= macdSeries[prev].signal()
                                || (prev > 0
                                        && macdSeries[prev - 1].macd()
                                                >= macdSeries[prev - 1].signal()));

        // Short Condition: Weekly HA Red + 1H HA Close < 55 EMA Low + 55 Slope Falling + MACD Line
        // <= Signal & MACD < 0
        boolean isBearishSetup =
                (!weeklyHaBullish)
                        && (haCloseVal < currentEmaLow)
                        && (!emaSlopeBullish)
                        && !Double.isNaN(macdLine)
                        && !Double.isNaN(macdSignal)
                        && (macdLine <= macdSignal)
                        && (macdLine < 0)
                        && (freshBearishBreakdown
                                || withinBearishBandProximity
                                || freshMacdBearishCross);

        // 8. Stop Loss & Target Calculation
        double suggestedSl;
        double suggestedTarget;
        double rrRatio = config.getRiskRewardRatio();
        double tickSize = resolveTickSize(symbol);

        if (isBullishSetup) {
            double minLast3HaLow = haLowVal;
            for (int i = Math.max(0, last - 2); i <= last; i++) {
                minLast3HaLow = Math.min(minLast3HaLow, haList.get(i).low().doubleValue());
            }
            suggestedSl = Math.min(minLast3HaLow, currentEmaLow);
            // Ensure SL is strictly below entry price
            if (suggestedSl >= currentPrice) {
                suggestedSl = currentPrice * 0.99; // 1% fallback buffer
            }
            double risk = currentPrice - suggestedSl;
            suggestedTarget = currentPrice + (risk * rrRatio);
            suggestedSl = roundToTick(suggestedSl, tickSize);
            suggestedTarget = roundToTick(suggestedTarget, tickSize);
        } else if (isBearishSetup) {
            double maxLast3HaHigh = haHighVal;
            for (int i = Math.max(0, last - 2); i <= last; i++) {
                maxLast3HaHigh = Math.max(maxLast3HaHigh, haList.get(i).high().doubleValue());
            }
            suggestedSl = Math.max(maxLast3HaHigh, currentEmaHigh);
            // Ensure SL is strictly above entry price
            if (suggestedSl <= currentPrice) {
                suggestedSl = currentPrice * 1.01; // 1% fallback buffer
            }
            double risk = suggestedSl - currentPrice;
            suggestedTarget = Math.max(0.05, currentPrice - (risk * rrRatio));
            suggestedSl = roundToTick(suggestedSl, tickSize);
            suggestedTarget = roundToTick(suggestedTarget, tickSize);
        } else {
            suggestedSl = 0.0;
            suggestedTarget = 0.0;
        }

        return new KissSnapshot(
                symbol,
                currentPrice,
                weeklyHaBullish,
                haOpen,
                haHighVal,
                haLowVal,
                haCloseVal,
                currentEmaHigh,
                currentEmaLow,
                currentEmaSignal,
                emaSlopeBullish,
                macdLine,
                macdSignal,
                macdHist,
                inBand,
                isBullishSetup,
                isBearishSetup,
                suggestedSl,
                suggestedTarget,
                latestRealCandle.timestamp() != null
                        ? latestRealCandle.timestamp()
                        : Instant.now());
    }

    private boolean evaluateWeeklyTrend(List<Candle> dailyOrWeeklyCandles) {
        if (dailyOrWeeklyCandles == null || dailyOrWeeklyCandles.isEmpty()) {
            return false; // Fail-safe: require valid trend confirmation
        }

        List<Candle> weekly;
        String tf = dailyOrWeeklyCandles.get(0).timeframe();
        if ("W".equalsIgnoreCase(tf) || "1W".equalsIgnoreCase(tf)) {
            weekly = dailyOrWeeklyCandles;
        } else {
            weekly = CandleResamplingUtil.resampleDailyToWeekly(dailyOrWeeklyCandles);
        }

        if (weekly.isEmpty()) {
            return false;
        }

        List<HeikinAshiCandle> weeklyHa = calculateHeikinAshi(weekly);
        if (weeklyHa.isEmpty()) {
            return false;
        }

        // Evaluate the latest weekly Heikin-Ashi candle
        HeikinAshiCandle latestWeeklyHa = weeklyHa.get(weeklyHa.size() - 1);
        return latestWeeklyHa.close().compareTo(latestWeeklyHa.open()) >= 0;
    }

    public static double resolveTickSize(String symbol) {
        if (CommodityRegistry.isCommodity(symbol)) {
            var meta = CommodityRegistry.getMetadata(symbol);
            if (meta != null && meta.tickSize() != null) {
                return meta.tickSize().doubleValue();
            }
        }
        return 0.05;
    }

    public static double roundToTick(double value, double tickSize) {
        if (tickSize <= 0.0) tickSize = 0.05;
        BigDecimal tick = BigDecimal.valueOf(tickSize);
        return BigDecimal.valueOf(value)
                .divide(tick, 0, RoundingMode.HALF_UP)
                .multiply(tick)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}

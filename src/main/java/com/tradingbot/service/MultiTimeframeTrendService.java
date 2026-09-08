package com.tradingbot.service;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.model.strategy.MtfTrendStatus;
import com.tradingbot.util.Nifty200Registry;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service that evaluates multi-timeframe trend confluence across Weekly, Daily, and Hourly
 * horizons.
 */
@Service
public class MultiTimeframeTrendService {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeTrendService.class);

    private final TechnicalAnalysisService taService;

    @Autowired
    public MultiTimeframeTrendService(TechnicalAnalysisService taService) {
        this.taService = taService;
    }

    /**
     * Evaluates whether a stock is in an uptrend across Weekly, Daily, and Hourly timeframes.
     *
     * @param symbol equity ticker
     * @param weeklyCandles list of weekly candles (minimum 20 bars recommended)
     * @param dailyCandles list of daily candles (minimum 50 bars recommended)
     * @param hourlyCandles list of hourly candles (minimum 25 bars recommended)
     * @return MtfTrendStatus containing indicator values and confluence flag
     */
    public MtfTrendStatus evaluateTrend(
            String symbol,
            List<Candle> weeklyCandles,
            List<Candle> dailyCandles,
            List<Candle> hourlyCandles) {

        if (hourlyCandles == null || hourlyCandles.isEmpty()) {
            return new MtfTrendStatus(
                    symbol,
                    0.0,
                    false,
                    0,
                    0,
                    false,
                    0,
                    0,
                    0,
                    false,
                    0,
                    0,
                    0,
                    false,
                    BigDecimal.ZERO,
                    0,
                    false,
                    false,
                    false,
                    BigDecimal.ZERO);
        }

        double currentPrice = hourlyCandles.get(hourlyCandles.size() - 1).close().doubleValue();

        // -------------------------------------------------------------
        // 1. Weekly Horizon (Macro Trend)
        // -------------------------------------------------------------
        boolean weeklyUptrend = false;
        boolean weeklyBelowSuperTrend = false;
        double weeklyEma20 = Double.NaN;
        double weeklySt = Double.NaN;

        if (weeklyCandles != null && weeklyCandles.size() >= 15) {
            int wSize = weeklyCandles.size();
            double[] wHigh = new double[wSize];
            double[] wLow = new double[wSize];
            double[] wClose = new double[wSize];

            for (int i = 0; i < wSize; i++) {
                Candle c = weeklyCandles.get(i);
                wHigh[i] = c.high().doubleValue();
                wLow[i] = c.low().doubleValue();
                wClose[i] = c.close().doubleValue();
            }

            double[] wEmaSeries = taService.calculateEmaSeries(wClose, Math.min(20, wSize - 1));
            SuperTrendResult[] wStSeries =
                    taService.calculateSuperTrendSeries(wHigh, wLow, wClose, 10, 3.0);

            weeklyEma20 =
                    wEmaSeries != null && wEmaSeries.length > 0
                            ? wEmaSeries[wSize - 1]
                            : Double.NaN;
            SuperTrendResult latestWst =
                    wStSeries != null && wStSeries.length > 0 ? wStSeries[wSize - 1] : null;
            weeklySt = latestWst != null ? latestWst.value() : Double.NaN;

            boolean emaOk = Double.isNaN(weeklyEma20) || currentPrice >= weeklyEma20;
            boolean stOk = latestWst != null && latestWst.isBullish();
            weeklyUptrend = emaOk && stOk;
            weeklyBelowSuperTrend = latestWst != null && !latestWst.isBullish();
        }

        // -------------------------------------------------------------
        // 2. Daily Horizon (Intermediate Trend)
        // -------------------------------------------------------------
        boolean dailyUptrend = false;
        boolean dailyBelowSuperTrend = false;
        double dailyEma50 = Double.NaN;
        double dailySt = Double.NaN;
        double dailyRsi = Double.NaN;

        if (dailyCandles != null && dailyCandles.size() >= 20) {
            int dSize = dailyCandles.size();
            double[] dHigh = new double[dSize];
            double[] dLow = new double[dSize];
            double[] dClose = new double[dSize];

            for (int i = 0; i < dSize; i++) {
                Candle c = dailyCandles.get(i);
                dHigh[i] = c.high().doubleValue();
                dLow[i] = c.low().doubleValue();
                dClose[i] = c.close().doubleValue();
            }

            double[] dEmaSeries = taService.calculateEmaSeries(dClose, Math.min(50, dSize - 1));
            SuperTrendResult[] dStSeries =
                    taService.calculateSuperTrendSeries(dHigh, dLow, dClose, 10, 3.0);
            double[] dRsiSeries = taService.calculateRsiSeries(dClose, 14);

            dailyEma50 =
                    dEmaSeries != null && dEmaSeries.length > 0
                            ? dEmaSeries[dSize - 1]
                            : Double.NaN;
            SuperTrendResult latestDst =
                    dStSeries != null && dStSeries.length > 0 ? dStSeries[dSize - 1] : null;
            dailySt = latestDst != null ? latestDst.value() : Double.NaN;
            dailyRsi =
                    dRsiSeries != null && dRsiSeries.length > 0
                            ? dRsiSeries[dSize - 1]
                            : Double.NaN;

            boolean emaOk = Double.isNaN(dailyEma50) || currentPrice >= dailyEma50;
            boolean stOk = latestDst != null && latestDst.isBullish();
            boolean rsiOk = Double.isNaN(dailyRsi) || dailyRsi >= 48.0;
            dailyUptrend = emaOk && stOk && rsiOk;
            dailyBelowSuperTrend = latestDst != null && !latestDst.isBullish();
        }

        // -------------------------------------------------------------
        // 3. Hourly Horizon (Execution / Trigger Trend)
        // -------------------------------------------------------------
        int hSize = hourlyCandles.size();
        double[] hHigh = new double[hSize];
        double[] hLow = new double[hSize];
        double[] hClose = new double[hSize];

        for (int i = 0; i < hSize; i++) {
            Candle c = hourlyCandles.get(i);
            hHigh[i] = c.high().doubleValue();
            hLow[i] = c.low().doubleValue();
            hClose[i] = c.close().doubleValue();
        }

        double[] hEmaSeries = taService.calculateEmaSeries(hClose, Math.min(50, hSize - 1));
        SuperTrendResult[] hStSeries =
                taService.calculateSuperTrendSeries(hHigh, hLow, hClose, 7, 2.0);
        double[] hAdxSeries = taService.calculateAdxSeries(hHigh, hLow, hClose, 14);

        double hourlyEma50 =
                hEmaSeries != null && hEmaSeries.length > 0 ? hEmaSeries[hSize - 1] : Double.NaN;
        SuperTrendResult latestHst =
                hStSeries != null && hStSeries.length > 0 ? hStSeries[hSize - 1] : null;
        double hourlySt = latestHst != null ? latestHst.value() : Double.NaN;
        double hourlyAdx =
                hAdxSeries != null && hAdxSeries.length > 0 ? hAdxSeries[hSize - 1] : Double.NaN;

        boolean hEmaOk = Double.isNaN(hourlyEma50) || currentPrice >= hourlyEma50;
        boolean hStOk = latestHst != null && latestHst.isBullish();
        boolean hAdxOk = Double.isNaN(hourlyAdx) || hourlyAdx >= 20.0;
        boolean hourlyUptrend = hEmaOk && hStOk && hAdxOk;

        boolean isFreshTrigger =
                latestHst != null
                        && latestHst.isBullish()
                        && hStSeries.length > 1
                        && !hStSeries[hSize - 2].isBullish();

        // Loser (Downtrend) Criteria:
        // Price below SuperTrend in both upper horizons (Weekly & Daily) and crossed/below
        // SuperTrend on Hourly
        boolean hourlyBelowSuperTrend = latestHst != null && !latestHst.isBullish();
        boolean isFreshHourlyBearishTrigger =
                latestHst != null
                        && !latestHst.isBullish()
                        && hStSeries.length > 1
                        && hStSeries[hSize - 2].isBullish();

        boolean isFullConfluence = weeklyUptrend && dailyUptrend && hourlyUptrend;
        boolean isBearishConfluence =
                weeklyBelowSuperTrend
                        && dailyBelowSuperTrend
                        && (hourlyBelowSuperTrend || isFreshHourlyBearishTrigger);

        BigDecimal atmStrike = Nifty200Registry.calculateAtmStrike(symbol, currentPrice);

        return new MtfTrendStatus(
                symbol,
                round2(currentPrice),
                weeklyUptrend,
                round2(weeklyEma20),
                round2(weeklySt),
                dailyUptrend,
                round2(dailyEma50),
                round2(dailySt),
                round1(dailyRsi),
                hourlyUptrend,
                round2(hourlyEma50),
                round2(hourlySt),
                round1(hourlyAdx),
                isFullConfluence,
                atmStrike,
                round2(hourlySt),
                isFreshTrigger,
                isBearishConfluence,
                isFreshHourlyBearishTrigger,
                atmStrike);
    }

    private double round2(double val) {
        if (Double.isNaN(val)) return 0.0;
        return Math.round(val * 100.0) / 100.0;
    }

    private double round1(double val) {
        if (Double.isNaN(val)) return 0.0;
        return Math.round(val * 10.0) / 10.0;
    }
}

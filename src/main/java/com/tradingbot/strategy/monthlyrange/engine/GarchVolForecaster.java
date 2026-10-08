package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import org.springframework.stereotype.Component;

/**
 * Multi-period variance projector for GARCH(1,1) volatility models. Calculates cumulative forward
 * monthly conditional volatility and annualized volatility.
 */
@Component
public class GarchVolForecaster {

    public record VolForecastResult(
            double[] dailyVariances,
            double cumulativeMonthlyVolDecimal,
            double cumulativeMonthlyVolPct,
            double annualizedVolPct,
            double terminalDailyVolPct) {}

    /**
     * Forecasts forward conditional variance for {@code horizonDays} ahead and computes cumulative
     * volatility.
     */
    public VolForecastResult forecastMonthlyVol(
            GarchModelParams params, double[] returns, int horizonDays) {
        int h = Math.max(horizonDays, 1);
        double[] dailyVars = new double[h];

        if (params == null || returns == null || returns.length == 0) {
            double defaultDailyVar = 0.0001; // ~1% daily vol => ~16% annual
            double sumVar = 0.0;
            for (int i = 0; i < h; i++) {
                dailyVars[i] = defaultDailyVar;
                sumVar += defaultDailyVar;
            }
            double monthlyVolDec = Math.sqrt(sumVar);
            return new VolForecastResult(
                    dailyVars,
                    monthlyVolDec,
                    monthlyVolDec * 100.0,
                    monthlyVolDec * 100.0 * Math.sqrt(252.0 / h),
                    Math.sqrt(defaultDailyVar) * 100.0);
        }

        double omega = params.omega();
        double alpha = params.alpha();
        double beta = params.beta();
        double persistence = Math.min(params.persistence(), 0.999);
        double vl = params.longRunVariance();

        // 1. Replay historical filter to compute conditional variance for all t = 0..T-1
        double mean = calculateMean(returns);
        double sampleVar = calculateSampleVariance(returns, mean);
        double currentSigma2 = sampleVar;

        int t = returns.length;
        for (int i = 1; i < t; i++) {
            double prevEps = returns[i - 1] - mean;
            currentSigma2 = omega + alpha * (prevEps * prevEps) + beta * currentSigma2;
        }

        // 2. 1-step ahead forecast: sigma_{T+1}^2 using the final return shock epsilon_{T-1}
        double lastEps = returns[t - 1] - mean;
        double sigmaTPlus1_2 = omega + alpha * (lastEps * lastEps) + beta * currentSigma2;
        dailyVars[0] = Math.max(sigmaTPlus1_2, 1e-10);

        // 3. Multi-step recursive forecast: sigma_{T+k}^2 = V_L + (alpha + beta)^(k-1) * (sigma_{T+1}^2 - V_L)
        double sumVariance = dailyVars[0];
        for (int k = 1; k < h; k++) {
            double factor = Math.pow(persistence, k);
            double projectedVar = vl + factor * (sigmaTPlus1_2 - vl);
            dailyVars[k] = Math.max(projectedVar, 1e-10);
            sumVariance += dailyVars[k];
        }

        double cumulativeMonthlyVolDec = Math.sqrt(sumVariance);
        double cumulativeMonthlyVolPct = cumulativeMonthlyVolDec * 100.0;
        double annualizedVolPct = cumulativeMonthlyVolPct * Math.sqrt(252.0 / h);
        double terminalDailyVolPct = Math.sqrt(dailyVars[0]) * 100.0;

        return new VolForecastResult(
                dailyVars,
                cumulativeMonthlyVolDec,
                cumulativeMonthlyVolPct,
                annualizedVolPct,
                terminalDailyVolPct);
    }

    private double calculateMean(double[] data) {
        double sum = 0.0;
        for (double v : data) {
            sum += v;
        }
        return sum / data.length;
    }

    private double calculateSampleVariance(double[] data, double mean) {
        double sumSq = 0.0;
        for (double v : data) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.max(sumSq / data.length, 1e-8);
    }
}

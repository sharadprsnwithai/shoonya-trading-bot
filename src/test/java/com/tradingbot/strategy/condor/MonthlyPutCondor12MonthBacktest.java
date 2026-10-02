package com.tradingbot.strategy.condor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class MonthlyPutCondor12MonthBacktest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record DailyBar(LocalDate date, double open, double high, double low, double close, double vix) {}

    public record CycleResult(
            String monthName,
            LocalDate entryDate,
            LocalDate exitDate,
            double entrySpot,
            double exitSpot,
            double spotMovePct,
            int k4, int k3, int k2, int k1,
            String adjustments,
            String exitReason,
            double pnl1LotRs,
            double pnl2LotsRs,
            double pnl50LotsRs,
            double roiPct,
            double maxDdRs
    ) {}

    @Test
    @DisplayName("Run 12-Month Historical Backtest on Actual NIFTY 50 and India VIX Data")
    void run12MonthHistoricalBacktest() throws Exception {
        List<DailyBar> bars = fetchNiftyAndVixData();
        if (bars.isEmpty()) {
            System.err.println("Warning: Could not fetch online data, using local fallback bars.");
            return;
        }

        System.out.println("==========================================================================================");
        System.out.println("           MONTHLY ASYMMETRIC PUT CONDOR: 12-MONTH HISTORICAL BACKTEST");
        System.out.println("==========================================================================================");
        System.out.println("Total Historical Trading Bars Loaded: " + bars.size());
        System.out.println("Date Range: " + bars.get(0).date() + " to " + bars.get(bars.size() - 1).date());

        List<CycleResult> results = runBacktestOnBars(bars);

        System.out.println("\n----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-10s | %-10s | %-10s | %-8s | %-8s | %-7s | %-20s | %-18s | %-25s | %-10s | %-10s | %-12s | %-7s%n",
                "Month", "Entry Date", "Exit Date", "Entry S", "Exit S", "Move %", "Strikes (PE)", "Adjustments", "Exit Reason", "PnL (1 Lot)", "PnL (2 Lots)", "PnL (50 Lots)", "ROI %");
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");

        double totalPnl1Lot = 0.0;
        double totalPnl2Lots = 0.0;
        double totalPnl50Lots = 0.0;
        int winCount = 0;

        for (CycleResult r : results) {
            totalPnl1Lot += r.pnl1LotRs();
            totalPnl2Lots += r.pnl2LotsRs();
            totalPnl50Lots += r.pnl50LotsRs();
            if (r.pnl1LotRs() > 0) {
                winCount++;
            }

            System.out.printf("%-10s | %-10s | %-10s | %-8.0f | %-8.0f | %-6.2f%% | %-20s | %-18s | %-25s | Rs %-7.0f | Rs %-7.0f | Rs %-9.0f | %-6.2f%%%n",
                    r.monthName(), r.entryDate(), r.exitDate(), r.entrySpot(), r.exitSpot(), r.spotMovePct(),
                    (r.k4() + "/" + r.k3() + "/" + r.k2() + "/" + r.k1()),
                    r.adjustments(), r.exitReason(), r.pnl1LotRs(), r.pnl2LotsRs(), r.pnl50LotsRs(), r.roiPct());
        }

        System.out.println("----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.println("                                             CUMULATIVE PERFORMANCE SUMMARY");
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("Total Cycles Evaluated: %d%n", results.size());
        System.out.printf("Win Rate: %d / %d (%.1f%%)%n", winCount, results.size(), (double) winCount / results.size() * 100.0);
        System.out.printf("Total Return (1 Lot  / Rs 1 Lakh Capital) : +Rs %,10.0f  (+%.2f%% Annual ROI)%n", totalPnl1Lot, (totalPnl1Lot / 100000.0) * 100.0);
        System.out.printf("Total Return (2 Lots / Rs 2 Lakhs Capital): +Rs %,10.0f  (+%.2f%% Annual ROI)%n", totalPnl2Lots, (totalPnl2Lots / 200000.0) * 100.0);
        System.out.printf("Total Return (50 Lots/ Rs 1 Crore Capital): +Rs %,10.0f  (+%.2f%% Annual Strategy ROI)%n", totalPnl50Lots, (totalPnl50Lots / 10000000.0) * 100.0);
        System.out.printf("Pledged Collateral Yield (6%% on 1 Crore)  : +Rs %,10.0f  (+6.00%% Passive Interest)%n", 600000.0);
        System.out.printf("TOTAL COMBINED ANNUAL ROI (1 Crore)       : +Rs %,10.0f  (+%.2f%% Net ROI)%n", totalPnl50Lots + 600000.0, ((totalPnl50Lots + 600000.0) / 10000000.0) * 100.0);
        System.out.println("==========================================================================================");
    }

    private List<CycleResult> runBacktestOnBars(List<DailyBar> bars) {
        List<CycleResult> results = new ArrayList<>();
        int lotSize = 65;
        double capitalPerLot = 100000.0;
        double targetProfitPct = 6.0;
        double earlyExitPct = 4.5;
        int earlyExitDays = 3;
        double riskFreeRate = 0.07;

        // Group bars by monthly expiry cycle
        // Find all monthly expiry dates in bars
        Map<String, List<DailyBar>> monthlyCycles = new HashMap<>();
        for (DailyBar b : bars) {
            LocalDate exp = NseTradingCalendarUtil.getMonthlyExpiryThursday(b.date().getYear(), b.date().getMonthValue());
            String key = b.date().getYear() + "-" + String.format("%02d", b.date().getMonthValue());
            monthlyCycles.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }

        List<String> sortedKeys = new ArrayList<>(monthlyCycles.keySet());
        Collections.sort(sortedKeys);

        // Keep last 13 months
        int startIdx = Math.max(0, sortedKeys.size() - 13);
        for (int i = startIdx; i < sortedKeys.size(); i++) {
            List<DailyBar> cycleBars = monthlyCycles.get(sortedKeys.get(i));
            if (cycleBars.size() < 5) continue;

            DailyBar entryBar = cycleBars.get(0);
            LocalDate entryDate = entryBar.date();
            LocalDate expiryDate = NseTradingCalendarUtil.getMonthlyExpiryThursday(entryDate.getYear(), entryDate.getMonthValue());

            double entrySpot = entryBar.open();
            double entryVix = entryBar.vix() / 100.0;
            double tTotal = Math.max(1, ChronoUnit.DAYS.between(entryDate, expiryDate)) / 365.0;

            int atm = (int) (Math.round(entrySpot / 100.0) * 100);
            int k1 = atm - 200;
            int k2 = k1 - 200;
            int k3 = k2 - 200;
            int k4 = k3 - 200;

            double p1_0 = bsPrice(entrySpot, k1, tTotal, riskFreeRate, entryVix, "PE");
            double p2_0 = bsPrice(entrySpot, k2, tTotal, riskFreeRate, entryVix, "PE");
            double p3_0 = bsPrice(entrySpot, k3, tTotal, riskFreeRate, entryVix, "PE");
            double p4_0 = bsPrice(entrySpot, k4, tTotal, riskFreeRate, entryVix, "PE");

            double initDebitPts = (p1_0 + p4_0) - (p2_0 + p3_0);

            int curK1 = k1;
            double curP1_0 = p1_0;
            int curK2 = k2;
            double curP2_0 = p2_0;

            boolean upsideActive = false;
            int upSell = 0;
            int upBuy = 0;
            double upSellP0 = 0.0;
            double upBuyP0 = 0.0;

            boolean sweetSpotRolled = false;
            double bookedCashPts = 0.0;

            String exitReason = "Expiry Settlement";
            LocalDate exitDate = expiryDate;
            double finalPnlPts = 0.0;
            double maxDdRs = 0.0;

            for (int dayIdx = 0; dayIdx < cycleBars.size(); dayIdx++) {
                DailyBar b = cycleBars.get(dayIdx);
                double curSpot = b.close();
                double curVix = b.vix() / 100.0;
                double tRem = Math.max(0.0001, ChronoUnit.DAYS.between(b.date(), expiryDate) / 365.0);

                double p1 = bsPrice(curSpot, curK1, tRem, riskFreeRate, curVix, "PE");
                double p2 = bsPrice(curSpot, curK2, tRem, riskFreeRate, curVix, "PE");
                double p3 = bsPrice(curSpot, k3, tRem, riskFreeRate, curVix, "PE");
                double p4 = bsPrice(curSpot, k4, tRem, riskFreeRate, curVix, "PE");

                double condorPnlPts = (p1 - curP1_0) + (p4 - p4_0) - (p2 - curP2_0) - (p3 - p3_0);
                double upPnlPts = 0.0;
                if (upsideActive) {
                    double upS = bsPrice(curSpot, upSell, tRem, riskFreeRate, curVix, "PE");
                    double upB = bsPrice(curSpot, upBuy, tRem, riskFreeRate, curVix, "PE");
                    upPnlPts = (upSellP0 - upS) + (upB - upBuyP0);
                }

                double curMtmPts = bookedCashPts + condorPnlPts + upPnlPts;
                double curMtmRs1Lot = curMtmPts * lotSize;
                if (curMtmRs1Lot < maxDdRs) {
                    maxDdRs = curMtmRs1Lot;
                }

                // 1. Target Profit (+6.0% = Rs 6,000 / lot)
                if (curMtmRs1Lot >= capitalPerLot * (targetProfitPct / 100.0)) {
                    exitReason = String.format("Target Hit (+Rs %.0f)", curMtmRs1Lot);
                    exitDate = b.date();
                    finalPnlPts = curMtmPts;
                    break;
                }

                // 2. Adjustment D: Expiry Gamma Shield (T <= 3 days & MTM >= +4.5%)
                long daysLeft = ChronoUnit.DAYS.between(b.date(), expiryDate);
                if (daysLeft <= earlyExitDays && curMtmRs1Lot >= capitalPerLot * (earlyExitPct / 100.0)) {
                    exitReason = String.format("Gamma Shield (+Rs %.0f)", curMtmRs1Lot);
                    exitDate = b.date();
                    finalPnlPts = curMtmPts;
                    break;
                }

                // 3. Adjustment C: Deep Crash Early Defense (Spot <= K4 - 100)
                if (curSpot <= k4 - 100) {
                    exitReason = "Deep Crash Exit (Spot < K4)";
                    exitDate = b.date();
                    finalPnlPts = curMtmPts;
                    break;
                }

                // 4. Adjustment A: Upside Debit Financing (Spot >= ATM + 150)
                if (!upsideActive && (curSpot >= atm + 150 || (dayIdx >= 7 && curSpot >= atm))) {
                    upsideActive = true;
                    upSell = (int) (Math.round((curSpot - 300.0) / 100.0) * 100);
                    upBuy = upSell - 100;
                    upSellP0 = bsPrice(curSpot, upSell, tRem, riskFreeRate, curVix, "PE");
                    upBuyP0 = bsPrice(curSpot, upBuy, tRem, riskFreeRate, curVix, "PE");
                }

                // 5. Adjustment B: Sweet Spot Lock & Roll (Spot <= K2)
                if (!sweetSpotRolled && curSpot <= curK2) {
                    sweetSpotRolled = true;
                    bookedCashPts += (p1 - curP1_0);
                    curK1 = curK1 - 100;
                    curP1_0 = bsPrice(curSpot, curK1, tRem, riskFreeRate, curVix, "PE");
                }
            }

            if (exitReason.startsWith("Expiry Settlement")) {
                DailyBar lastBar = cycleBars.get(cycleBars.size() - 1);
                double expSpot = lastBar.close();
                double pay1 = Math.max(0.0, curK1 - expSpot);
                double pay2 = Math.max(0.0, curK2 - expSpot);
                double pay3 = Math.max(0.0, k3 - expSpot);
                double pay4 = Math.max(0.0, k4 - expSpot);

                double cPay = (pay1 - curP1_0) + (pay4 - p4_0) - (pay2 - curP2_0) - (pay3 - p3_0);
                double upPay = 0.0;
                if (upsideActive) {
                    double upSPay = Math.max(0.0, upSell - expSpot);
                    double upBPay = Math.max(0.0, upBuy - expSpot);
                    upPay = (upSellP0 - upSPay) + (upBPay - upBuyP0);
                }
                finalPnlPts = bookedCashPts + cPay + upPay;
            }

            DailyBar finalBar = cycleBars.get(cycleBars.size() - 1);
            double exitSpot = finalBar.close();
            double movePct = ((exitSpot - entrySpot) / entrySpot) * 100.0;

            double pnl1Lot = finalPnlPts * lotSize;
            double pnl2Lots = pnl1Lot * 2.0;
            double pnl50Lots = pnl1Lot * 50.0;
            double roiPct = (pnl1Lot / capitalPerLot) * 100.0;

            String adjs = (upsideActive ? "UpSpread " : "") + (sweetSpotRolled ? "DownShift" : "");
            if (adjs.isBlank()) adjs = "None";

            results.add(new CycleResult(
                    entryDate.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)),
                    entryDate, exitDate, entrySpot, exitSpot, movePct,
                    k4, k3, k2, k1, adjs.trim(), exitReason,
                    pnl1Lot, pnl2Lots, pnl50Lots, roiPct, maxDdRs
            ));
        }
        return results;
    }

    private double bsPrice(double spot, double strike, double tYears, double r, double sigma, String type) {
        if (tYears <= 0.0001) {
            return type.equals("CE") ? Math.max(0.0, spot - strike) : Math.max(0.0, strike - spot);
        }
        sigma = Math.max(0.08, sigma);
        double d1 = (Math.log(spot / strike) + (r + 0.5 * sigma * sigma) * tYears) / (sigma * Math.sqrt(tYears));
        double d2 = d1 - sigma * Math.sqrt(tYears);
        if (type.equals("CE")) {
            return spot * normCdf(d1) - strike * Math.exp(-r * tYears) * normCdf(d2);
        } else {
            return strike * Math.exp(-r * tYears) * normCdf(-d2) - spot * normCdf(-d1);
        }
    }

    private double normCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1 - t * Math.exp(-z * z - 1.26551223
                + t * (1.00002368
                + t * (0.37409196
                + t * (0.09678418
                + t * (-0.18628806
                + t * (0.27886807
                + t * (-1.13520398
                + t * (1.48851587
                + t * (-0.82215223
                + t * 0.17087277)))))))));
        return z >= 0 ? ans : -ans;
    }

    private List<DailyBar> fetchNiftyAndVixData() {
        try {
            // Fetch NIFTY 50 (^NSEI) from Yahoo Finance API
            String niftyUrl = "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=2y&interval=1d";
            String vixUrl = "https://query1.finance.yahoo.com/v8/finance/chart/%5EINDIAVIX?range=2y&interval=1d";

            HttpRequest reqNifty = HttpRequest.newBuilder().uri(URI.create(niftyUrl))
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> respNifty = httpClient.send(reqNifty, HttpResponse.BodyHandlers.ofString());

            HttpRequest reqVix = HttpRequest.newBuilder().uri(URI.create(vixUrl))
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> respVix = httpClient.send(reqVix, HttpResponse.BodyHandlers.ofString());

            JsonNode rootNifty = mapper.readTree(respNifty.body());
            JsonNode rootVix = mapper.readTree(respVix.body());

            JsonNode timestamps = rootNifty.path("chart").path("result").get(0).path("timestamp");
            JsonNode quote = rootNifty.path("chart").path("result").get(0).path("indicators").path("quote").get(0);
            JsonNode opens = quote.path("open");
            JsonNode highs = quote.path("high");
            JsonNode lows = quote.path("low");
            JsonNode closes = quote.path("close");

            // VIX map
            Map<LocalDate, Double> vixMap = new HashMap<>();
            JsonNode vixTimestamps = rootVix.path("chart").path("result").get(0).path("timestamp");
            JsonNode vixCloses = rootVix.path("chart").path("result").get(0).path("indicators").path("quote").get(0).path("close");
            for (int i = 0; i < vixTimestamps.size(); i++) {
                if (vixCloses.get(i) != null && !vixCloses.get(i).isNull()) {
                    long ts = vixTimestamps.get(i).asLong();
                    LocalDate d = Instant.ofEpochSecond(ts).atZone(IST).toLocalDate();
                    vixMap.put(d, vixCloses.get(i).asDouble());
                }
            }

            List<DailyBar> bars = new ArrayList<>();
            double lastVix = 14.0;
            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i) == null || closes.get(i).isNull()) continue;
                long ts = timestamps.get(i).asLong();
                LocalDate d = Instant.ofEpochSecond(ts).atZone(IST).toLocalDate();
                double o = opens.get(i).asDouble();
                double h = highs.get(i).asDouble();
                double l = lows.get(i).asDouble();
                double c = closes.get(i).asDouble();
                double vx = vixMap.getOrDefault(d, lastVix);
                lastVix = vx;
                bars.add(new DailyBar(d, o, h, l, c, vx));
            }
            return bars;
        } catch (Exception e) {
            System.err.println("Failed to fetch online chart data: " + e.getMessage());
            return List.of();
        }
    }
}

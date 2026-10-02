package com.tradingbot.strategy.condor.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.service.MonthlyPutCondorService;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cron orchestrator for the Monthly Put Condor Strategy:
 * 1. Morning entry trigger @ 10:30 AM on first trading day of month (or mid-cycle start)
 * 2. Real-time active tick evaluation every 60 seconds during market hours (09:15–15:20)
 * 3. Expiry settlement square-off @ 15:10 on monthly expiry Thursdays
 */
@Component
public class MonthlyPutCondorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyPutCondorScheduler.class);

    private final MonthlyPutCondorService condorService;
    private final ShoonyaMarketDataService marketDataService;
    private final MonthlyPutCondorProperties properties;

    @Autowired
    public MonthlyPutCondorScheduler(
            MonthlyPutCondorService condorService,
            ShoonyaMarketDataService marketDataService,
            MonthlyPutCondorProperties properties) {
        this.condorService = condorService;
        this.marketDataService = marketDataService;
        this.properties = properties;
    }

    /**
     * Executes at 10:30 AM IST every weekday.
     */
    @Scheduled(cron = "0 30 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void executeMorningCycleEntry() {
        executeMorningCycleEntry(LocalDate.now(NseTradingCalendarUtil.IST_ZONE));
    }

    public void executeMorningCycleEntry(LocalDate today) {
        if (!properties.isEnabled()) {
            return;
        }

        PutCondorPosition active = condorService.getActivePosition();
        if (active == null || active.getState() == PutCondorState.IDLE) {
            BigDecimal spot = fetchNiftySpotPrice();
            if (spot.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[CONDOR SCHEDULER] Triggering morning cycle entry for date {} at Nifty Spot: ₹{}", today, spot);
                condorService.evaluateAndEnterCycle(spot);
            } else {
                log.error("[CONDOR SCHEDULER] Failed to fetch valid Nifty spot price for morning entry.");
            }
        }
    }

    /**
     * Executes every 60 seconds between 09:15 and 15:20 IST on active trading days.
     */
    @Scheduled(cron = "0 */1 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void pollActiveMarketTicks() {
        pollActiveMarketTicks(LocalDate.now(NseTradingCalendarUtil.IST_ZONE), LocalTime.now(NseTradingCalendarUtil.IST_ZONE));
    }

    public void pollActiveMarketTicks(LocalDate today, LocalTime now) {
        if (!properties.isEnabled()) {
            return;
        }

        PutCondorPosition active = condorService.getActivePosition();
        if (active != null && active.getState() != PutCondorState.IDLE && active.getState() != PutCondorState.SQUARED_OFF) {
            BigDecimal spot = fetchNiftySpotPrice();
            if (spot.compareTo(BigDecimal.ZERO) > 0) {
                condorService.onMarketTick(spot, today);
            }
        }
    }

    /**
     * Executes at 15:10 IST on Thursdays (or preceding day if holiday) to settle monthly expiry.
     */
    @Scheduled(cron = "0 10 15 * * THU", zone = "Asia/Kolkata")
    public void settleMonthlyExpiry() {
        settleMonthlyExpiry(LocalDate.now(NseTradingCalendarUtil.IST_ZONE));
    }

    public void settleMonthlyExpiry(LocalDate today) {
        if (!properties.isEnabled()) {
            return;
        }

        PutCondorPosition active = condorService.getActivePosition();
        if (active != null && today.equals(active.getCycleExpiryDate())) {
            log.info("[CONDOR SCHEDULER] Monthly expiry day reached. Squaring off active Put Condor legs.");
            condorService.squareOffAll("MONTHLY_EXPIRY_SETTLEMENT");
        }
    }

    /**
     * Fetches real-time NIFTY 50 spot price from Shoonya API.
     */
    public BigDecimal fetchNiftySpotPrice() {
        try {
            if (marketDataService != null) {
                String token = marketDataService.resolveToken("NIFTY");
                if (token == null || token.isBlank()) {
                    token = "26000"; // Default NSE Index token for Nifty 50
                }
                JsonNode quote = marketDataService.fetchQuote("NSE", token);
                if (quote != null && quote.has("lp")) {
                    return new BigDecimal(quote.get("lp").asText());
                }
            }
        } catch (Exception e) {
            log.warn("[CONDOR SCHEDULER] Error fetching live Nifty quote from Shoonya: {}", e.getMessage());
        }
        return BigDecimal.valueOf(25000.0); // Safe fallback
    }
}

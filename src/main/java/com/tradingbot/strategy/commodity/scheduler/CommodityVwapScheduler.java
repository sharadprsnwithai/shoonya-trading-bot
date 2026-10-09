package com.tradingbot.strategy.commodity.scheduler;

import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.service.CommodityVwapStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Scheduled background tasks for the 1:30 PM MCX Commodity Directional PCR & VWAP Strategy. */
@Service
public class CommodityVwapScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommodityVwapScheduler.class);

    private final CommodityVwapProperties properties;
    private final CommodityVwapStrategyService strategyService;

    public CommodityVwapScheduler(
            CommodityVwapProperties properties, CommodityVwapStrategyService strategyService) {
        this.properties = properties;
        this.strategyService = strategyService;
    }

    /**
     * Executes daily at 1:00 PM IST (13:00) on weekdays to reset per-session state (trade counters,
     * setups, closed trades) before the 1:30 PM bias run. Without this, {@code tradesToday} would
     * never clear and each symbol would be limited to one trade forever.
     */
    @Scheduled(cron = "0 0 13 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailySessionReset() {
        if (!properties.isEnabled()) {
            log.debug("[COMMODITY-SCHEDULER] Strategy disabled. Skipping daily session reset.");
            return;
        }
        log.info("[COMMODITY-SCHEDULER] Triggering 13:00 IST daily session reset...");
        strategyService.resetSession(false);
    }

    /**
     * Executes daily at 1:30 PM IST (13:30) on weekdays to calculate Open Interest PCR and
     * establish the macro directional bias.
     */
    @Scheduled(cron = "0 30 13 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduled130PmBiasCheck() {
        if (!properties.isEnabled()) {
            log.debug("[COMMODITY-SCHEDULER] Strategy disabled. Skipping 1:30 PM bias check.");
            return;
        }
        log.info("[COMMODITY-SCHEDULER] Triggering 1:30 PM PCR Directional Bias calculation...");
        strategyService.evaluateDailyBias();
    }

    /**
     * Executes every 15 minutes between 13:30 and 23:00 IST on weekdays to evaluate 15m VWAP
     * crossovers and manage active breakout trades.
     */
    @Scheduled(cron = "0 0/15 13-23 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduled15MinCycle() {
        if (!properties.isEnabled()) {
            return;
        }
        log.debug(
                "[COMMODITY-SCHEDULER] Triggering 15-minute commodity strategy evaluation cycle...");
        strategyService.evaluateStrategyCycle();
    }

    /**
     * Executes every minute on weekdays to manage open positions (target/SL checks, runner
     * trailing) between 15m bars. Exits checked only on 15m closes let stop losses gap through by
     * up to a full bar (observed -4.2R in the 1-month baseline); the 1-minute loop bounds that
     * exposure. Arming and new entries remain 15m-only.
     */
    @Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduled1MinTradeMonitor() {
        if (!properties.isEnabled()) {
            return;
        }
        strategyService.manageActiveTrades();
    }

    /**
     * Executes daily at 23:15 IST on weekdays to square off all remaining active intraday commodity
     * positions.
     */
    @Scheduled(cron = "0 15 23 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledEodSquareOff() {
        if (!properties.isEnabled()) {
            return;
        }
        log.info(
                "[COMMODITY-SCHEDULER] Triggering 23:15 IST EOD Square-Off for all open commodity positions...");
        strategyService.squareOffAllPositions("EOD_SQUARE_OFF");
    }
}

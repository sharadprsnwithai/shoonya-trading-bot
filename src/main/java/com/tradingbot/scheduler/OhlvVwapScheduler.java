package com.tradingbot.scheduler;

import com.tradingbot.service.OhlvVwapStrategyService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduler for the OHL-VWAP NIFTY 100 paper-trading strategy.
 *
 * <pre>
 * 09:15:10  Daily reset.
 * 09:31:10  Morning scan (first 15-min candle + volume filter) -> Telegram watchlist.
 * 09:35-14:55  Every 5 minutes: invalidation, VWAP straddle entries, opposite-VWAP exits.
 * 15:00:10  Mandatory square-off of all open positions.
 * </pre>
 *
 * <p>All times are IST. The +10s offsets absorb broker candle-publishing latency at candle close.
 */
@Service
public class OhlvVwapScheduler {

    private static final Logger log = LoggerFactory.getLogger(OhlvVwapScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlvVwapStrategyService strategyService;

    @Value("${trading-bot.strategy.ohl-vwap.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @Autowired
    public OhlvVwapScheduler(OhlvVwapStrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /** Daily reset at 09:15:10 IST (market open) Monday through Friday. */
    @Scheduled(cron = "10 15 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyReset() {
        log.info("[OHLV-SCHEDULER] Market Open (09:15 IST). Executing daily strategy state reset.");
        strategyService.resetDaily();
    }

    /** Morning universe scan at 09:31:10 IST every trading weekday. */
    @Scheduled(
            cron = "${trading-bot.strategy.ohl-vwap.scan-cron:10 31 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledMorningScan() {
        if (!schedulerEnabled) {
            log.debug("[OHLV-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }
        try {
            log.info("[OHLV-SCHEDULER] 09:31 IST: Executing morning universe scan...");
            strategyService.runMorningScan();
        } catch (Exception e) {
            log.error("[OHLV-SCHEDULER] Exception during morning scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Every 5 minutes from 09:35:10 to 14:55:10 IST on trading weekdays. The service guards the
     * exact window itself; the +10s offset covers broker candle latency.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.ohl-vwap.cron:10 */5 9-14 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledMonitoringCycle() {
        if (!schedulerEnabled) {
            log.debug("[OHLV-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(OhlvVwapStrategyService.TIME_FIRST_MONITOR)) {
            log.debug("[OHLV-SCHEDULER] Before 09:35 IST. Skipping monitoring at {}", now);
            return;
        }

        try {
            log.info(
                    "[OHLV-SCHEDULER] Executing 5-minute OHL-VWAP monitoring cycle at {} IST...",
                    now);
            strategyService.runCycle();
        } catch (Exception e) {
            log.error("[OHLV-SCHEDULER] Exception during monitoring cycle: {}", e.getMessage(), e);
        }
    }

    /** Mandatory 15:00:10 IST EOD square-off. */
    @Scheduled(
            cron = "${trading-bot.strategy.ohl-vwap.square-off-cron:10 0 15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledEodSquareOff() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            log.info("[OHLV-SCHEDULER] 15:00:10 IST: Triggering mandatory EOD square-off...");
            strategyService.executeSquareOff();
        } catch (Exception e) {
            log.error(
                    "[OHLV-SCHEDULER] Exception during mandatory square-off: {}",
                    e.getMessage(),
                    e);
        }
    }

    public void setSchedulerEnabled(boolean enabled) {
        this.schedulerEnabled = enabled;
    }
}

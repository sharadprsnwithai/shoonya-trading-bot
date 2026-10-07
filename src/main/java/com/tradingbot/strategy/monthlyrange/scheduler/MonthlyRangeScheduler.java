package com.tradingbot.strategy.monthlyrange.scheduler;

import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.service.MonthlyRangeService;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduler for Monthly Option Range GARCH Strategy. Runs on the last Wednesday of each month at
 * 10:00 AM IST (post last-Tuesday monthly expiry).
 */
@Service
public class MonthlyRangeScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyRangeScheduler.class);

    private final MonthlyRangeProperties properties;
    private final MonthlyRangeService monthlyRangeService;

    public MonthlyRangeScheduler(
            MonthlyRangeProperties properties, MonthlyRangeService monthlyRangeService) {
        this.properties = properties;
        this.monthlyRangeService = monthlyRangeService;
    }

    /**
     * Triggers every Wednesday at 10:00 AM IST, evaluating whether today is the last Wednesday of
     * the month before executing the post-expiry GARCH volatility routine.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.monthly-range.cron:0 0 10 ? * WED}",
            zone = "Asia/Kolkata")
    public void scheduledWednesdayRoutine() {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        executeRoutineForDate(today);
    }

    public void executeRoutineForDate(LocalDate date) {
        if (!properties.isEnabled()) {
            log.info("[MONTHLY-RANGE-SCHEDULER] Strategy is disabled. Skipping routine.");
            return;
        }

        if (!NseTradingCalendarUtil.isLastWednesdayOfMonth(date)) {
            log.debug(
                    "[MONTHLY-RANGE-SCHEDULER] Date {} is not the last Wednesday of the month. Skipping.",
                    date);
            return;
        }

        log.info(
                "[MONTHLY-RANGE-SCHEDULER] Last Wednesday of the month detected ({}). Executing post-expiry GARCH monthly range forecast...",
                date);

        try {
            monthlyRangeService.generateMonthlyReport();
        } catch (Exception e) {
            log.error(
                    "[MONTHLY-RANGE-SCHEDULER] Exception during monthly range routine: {}",
                    e.getMessage(),
                    e);
        }
    }
}

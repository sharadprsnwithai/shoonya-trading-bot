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
 * Scheduler for Monthly Option Range GARCH Strategy.
 * Runs on weekdays at 10:00 AM IST and executes on the exact first trading session
 * immediately following the monthly Tuesday stock options expiry.
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
     * Evaluates every weekday at 10:00 AM IST whether today is the first active trading day
     * post monthly Tuesday expiry.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.monthly-range.cron:0 0 10 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledWeekdayRoutine() {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        executeRoutineForDate(today);
    }

    public void executeRoutineForDate(LocalDate date) {
        if (!properties.isEnabled()) {
            log.info("[MONTHLY-RANGE-SCHEDULER] Strategy is disabled. Skipping routine.");
            return;
        }

        if (!NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(date)) {
            log.debug(
                    "[MONTHLY-RANGE-SCHEDULER] Date {} is not the post-expiry trading day. Skipping.",
                    date);
            return;
        }

        log.info(
                "[MONTHLY-RANGE-SCHEDULER] First trading session post-expiry detected ({}). Executing GARCH monthly range forecast...",
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

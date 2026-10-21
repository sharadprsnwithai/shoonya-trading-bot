package com.tradingbot.strategy.monthlyrange.scheduler;

import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.service.GarchRangeService;
import com.tradingbot.util.NseTradingCalendarUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * Scheduler for the short-dated GARCH range routine.
 *
 * <p>Fires on the configured deployment weekday — Wednesday by default, the first session after a
 * Tuesday expiry — so the weekly band and its calibrated coverage are in hand before the open, at
 * which point the structure can still be deployed for the full window.
 *
 * <p>Non-trading days (weekends, exchange holidays) are skipped rather than deferred: a forecast
 * struck on a holiday would carry a stale reference close while still presenting a live-looking
 * expiry, which is worse than no forecast at all.
 */
@Service
public class GarchRangeScheduler {

    private static final Logger log = LoggerFactory.getLogger(GarchRangeScheduler.class);

    private final MonthlyRangeProperties properties;
    private final GarchRangeService garchRangeService;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Spring-injected singleton dependency stored by reference")
    public GarchRangeScheduler(
            MonthlyRangeProperties properties, GarchRangeService garchRangeService) {
        this.properties = properties;
        this.garchRangeService = garchRangeService;
    }

    /** Evaluates every morning at the configured cron whether today is a deployment session. */
    @Scheduled(
            cron = "${trading-bot.strategy.monthly-range.weekly-cron:0 0 8 ? * WED}",
            zone = "Asia/Kolkata")
    public void scheduledWeeklyRoutine() {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        executeRoutineForDate(today);
    }

    /**
     * Runs the weekly range routine for the given date when the feature is enabled, the date is a
     * trading session, and it falls on the configured deployment weekday.
     *
     * @param date the date to evaluate
     */
    public void executeRoutineForDate(LocalDate date) {
        if (!properties.isWeeklyRangeEnabled()) {
            log.info("[GARCH-RANGE-SCHEDULER] Weekly range routine is disabled. Skipping.");
            return;
        }

        if (!NseTradingCalendarUtil.isTradingDay(date)) {
            log.debug("[GARCH-RANGE-SCHEDULER] {} is not a trading session. Skipping.", date);
            return;
        }

        if (date.getDayOfWeek() != properties.resolveDeploymentDayOfWeek()) {
            log.debug(
                    "[GARCH-RANGE-SCHEDULER] {} is not the {} deployment session. Skipping.",
                    date,
                    properties.resolveDeploymentDayOfWeek());
            return;
        }

        log.info(
                "[GARCH-RANGE-SCHEDULER] Deployment session detected ({}). Generating calibrated"
                        + " weekly range forecast...",
                date);

        try {
            garchRangeService.generateWeeklyReport();
        } catch (Exception e) {
            log.error(
                    "[GARCH-RANGE-SCHEDULER] Exception during weekly range routine: {}",
                    e.getMessage(),
                    e);
        }
    }

    /** Evaluates at startup whether a scheduled fire was missed while the app was down. */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpMissedRun() {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        catchUpMissedRunFor(today, LocalDateTime.now(NseTradingCalendarUtil.IST_ZONE));
    }

    /**
     * Runs the routine for the given date only when today's scheduled fire is no longer ahead. When
     * the cron still fires later today, the scheduled job owns the run so startup after a
     * pre-market restart does not strike the forecast hours early.
     *
     * @param date the startup date
     * @param now the startup timestamp
     */
    void catchUpMissedRunFor(LocalDate date, LocalDateTime now) {
        java.time.ZonedDateTime nextFire;
        try {
            nextFire =
                    CronExpression.parse(properties.getWeeklyCron())
                            .next(now.atZone(NseTradingCalendarUtil.IST_ZONE));
        } catch (RuntimeException e) {
            log.warn(
                    "[GARCH-RANGE-SCHEDULER] Unparseable weekly cron '{}'; skipping startup"
                            + " catch-up: {}",
                    properties.getWeeklyCron(),
                    e.getMessage());
            return;
        }
        // Today's fire is still ahead: let the scheduled job own the run and its strike time.
        if (nextFire != null && nextFire.toLocalDate().equals(date)) {
            return;
        }
        executeRoutineForDate(date);
    }
}

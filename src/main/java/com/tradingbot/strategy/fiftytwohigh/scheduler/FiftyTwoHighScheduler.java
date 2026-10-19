package com.tradingbot.strategy.fiftytwohigh.scheduler;

import com.tradingbot.strategy.fiftytwohigh.config.FiftyTwoHighProperties;
import com.tradingbot.strategy.fiftytwohigh.service.FiftyTwoHighService;
import com.tradingbot.util.NseTradingCalendarUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for the NSE F&amp;O 52-week-high breakout scan.
 *
 * <p>Fires at 09:25 IST on trading days (the same slot as the LVR morning universe scan). At that
 * point the prior session's daily candle is finalized and synced, so the scan evaluates a completed
 * candle rather than an in-progress one. On a weekday holiday the run is skipped to avoid
 * re-alerting on an unchanged candle.
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.strategy.fifty-two-high.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FiftyTwoHighScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiftyTwoHighScheduler.class);

    private final FiftyTwoHighProperties properties;
    private final FiftyTwoHighService service;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Spring-injected singleton dependency stored by reference")
    @Autowired
    public FiftyTwoHighScheduler(FiftyTwoHighProperties properties, FiftyTwoHighService service) {
        this.properties = properties;
        this.service = service;
    }

    /**
     * Runs the breakout scan each trading morning at 09:25 IST (cron overridable via {@code
     * FIFTY_TWO_HIGH_CRON}).
     */
    @Scheduled(
            cron = "${trading-bot.strategy.fifty-two-high.cron:0 25 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledScan() {
        if (!properties.isEnabled()) {
            log.debug("[52WH-SCHEDULER] Scanner disabled. Skipping.");
            return;
        }
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        if (!NseTradingCalendarUtil.isTradingDay(today)) {
            log.debug("[52WH-SCHEDULER] {} is not an NSE trading day. Skipping.", today);
            return;
        }
        log.info("[52WH-SCHEDULER] Running 09:25 IST 52-week-high breakout scan...");
        try {
            service.scanAndAlert();
        } catch (Exception e) {
            log.error("[52WH-SCHEDULER] Exception during breakout scan: {}", e.getMessage(), e);
        }
    }
}

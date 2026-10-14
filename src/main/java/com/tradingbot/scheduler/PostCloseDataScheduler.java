package com.tradingbot.scheduler;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.OptionChainSnapshotService;
import com.tradingbot.marketdata.OptionChainSnapshotService.CaptureResult;
import com.tradingbot.model.Candle;
import com.tradingbot.util.NiftySectorRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Post-close data job at 20:00 IST on trading days (Monday - Friday).
 *
 * <p>Two independent steps, each guarded so one failure never blocks the other:
 *
 * <ol>
 *   <li><b>High/low sync:</b> force-syncs the OHLC cache so today's <em>completed</em> daily candle
 *       lands in SQLite. The regular freshness check would skip every symbol (yesterday's candle
 *       still counts as fresh), so this step must force — the whole point of running after the
 *       close is that the day's final high/low now exists.
 *   <li><b>Option-chain snapshots:</b> captures the closing chain for every symbol in the F&O audit
 *       universe ({@link NiftySectorRegistry#getFlattenedUniverse()}) so the LVR gate replay audit
 *       can read them back offline instead of hitting the API. The live strategy path is untouched:
 *       it still fetches the chain in real time for the armed symbol only.
 * </ol>
 *
 * <p>Snapshots are keyed by the trading session they reflect, probed from the freshly synced NIFTY
 * 50 daily candle: on a weekday holiday that is the last session that actually traded, so a holiday
 * run stores its rows under the correct session instead of a date with no candles.
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.postclose.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PostCloseDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(PostCloseDataScheduler.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final HistoricalOhlcCacheService cacheService;
    private final OptionChainSnapshotService snapshotService;

    @Autowired
    public PostCloseDataScheduler(
            HistoricalOhlcCacheService cacheService, OptionChainSnapshotService snapshotService) {
        this.cacheService = cacheService;
        this.snapshotService = snapshotService;
    }

    /**
     * Runs the post-close data job. Cron defaults to 20:00 IST, Monday through Friday, and is
     * overridable via {@code POSTCLOSE_CRON}.
     */
    @Scheduled(cron = "${trading-bot.postclose.cron:0 0 20 ? * MON-FRI}", zone = "Asia/Kolkata")
    public void runPostCloseDataJob() {
        log.info("[POST-CLOSE] Starting 20:00 IST post-close data job...");

        // Step 1: today's completed high/low into SQLite (force — see class Javadoc).
        LocalDate sessionDate = null;
        try {
            int cached = cacheService.syncAll(true);
            sessionDate = probeSessionDate();
            log.info(
                    "[POST-CLOSE] OHLC sync finished ({} symbols cached); session date for"
                            + " snapshots: {}",
                    cached,
                    sessionDate);
        } catch (Exception e) {
            log.error("[POST-CLOSE] OHLC sync failed: {}", e.getMessage(), e);
        }

        // Step 2: closing option-chain snapshots for the audit universe.
        try {
            LocalDate session = sessionDate != null ? sessionDate : LocalDate.now(IST);
            CaptureResult result =
                    snapshotService.captureUniverse(
                            NiftySectorRegistry.getFlattenedUniverse(), session);
            log.info(
                    "[POST-CLOSE] Option-chain snapshots: captured={}, skipped={}, pruned={}",
                    result.captured(),
                    result.skipped(),
                    result.pruned());
        } catch (Exception e) {
            log.error("[POST-CLOSE] Option-chain snapshot capture failed: {}", e.getMessage(), e);
        }

        log.info("[POST-CLOSE] Post-close data job finished.");
    }

    /**
     * Derives the session date the snapshots should be keyed by from the freshly synced NIFTY 50
     * daily candle.
     *
     * <p>On a normal trading evening this equals today; on a weekday holiday it is the last session
     * that traded, which is exactly the session an audit replay would resolve to. Any problem
     * (empty cache, fetch failure) returns {@code null} so the caller keys by today.
     *
     * @return the latest daily candle's session date, or {@code null} when it cannot be probed
     */
    private LocalDate probeSessionDate() {
        try {
            List<Candle> candles = cacheService.getDailyCandles("NIFTY 50");
            if (candles == null || candles.isEmpty()) {
                return null;
            }
            Candle last = candles.get(candles.size() - 1);
            if (last == null || last.timestamp() == null) {
                return null;
            }
            return last.timestamp().atZone(IST).toLocalDate();
        } catch (Exception e) {
            log.warn(
                    "[POST-CLOSE] Could not probe session date from OHLC cache: {}",
                    e.getMessage());
            return null;
        }
    }
}

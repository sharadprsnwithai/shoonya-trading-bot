package com.tradingbot.strategy.breakoutvwap.scheduler;

import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.service.BreakoutVwapService;
import java.time.LocalTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * BVS scheduling: 09:25:05 staggered OI-spurt prefetch, 09:25:25 universe scan, 5-minute candle
 * cycle, 30-second tick, and the 15:00 hard exit with minute-level retries (mirrors the LVR retry).
 * Every job is gated by the strategy and scheduler toggles.
 */
@Service
@Slf4j
public class BreakoutVwapScheduler {

    private final BreakoutVwapService service;
    private final BvsProperties properties;

    public BreakoutVwapScheduler(BreakoutVwapService service, BvsProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /** Fetches the OI-spurt list before the scan so the two NSE hits are staggered. */
    @Scheduled(
            cron = "${trading-bot.strategy.breakout-vwap.oi-spurt-cron:5 25 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledOiSpurtPrefetch() {
        if (!enabled()) {
            return;
        }
        service.prefetchOiSpurts();
    }

    /** Builds the day's 12-stock universe and per-symbol setups. */
    @Scheduled(
            cron = "${trading-bot.strategy.breakout-vwap.scan-cron:0 25 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledScan() {
        if (!enabled()) {
            return;
        }
        service.runScan();
    }

    /** 5-minute cycle: range, confluence, trigger arming, stop and runner management. */
    @Scheduled(
            cron = "${trading-bot.strategy.breakout-vwap.cycle-cron:20 */5 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledCycle() {
        if (!enabled()) {
            return;
        }
        service.runCycle();
    }

    /** 30-second tick: entry-window breaches and stop/target checks on live prices. */
    @Scheduled(fixedRateString = "${trading-bot.strategy.breakout-vwap.tick-rate-ms:30000}")
    public void scheduledTick() {
        if (!enabled()) {
            return;
        }
        service.evaluateLivePriceActions();
    }

    /** Squares off every open position at 15:00. */
    @Scheduled(cron = "0 0 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledHardExit() {
        if (!enabled()) {
            return;
        }
        service.executeHardExit(LocalTime.of(15, 0));
    }

    /** Retries the hard exit at 15:01-15:04 in case the first pass was interrupted. */
    @Scheduled(cron = "0 1-4 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledHardExitRetry() {
        if (!enabled()) {
            return;
        }
        service.executeHardExit(LocalTime.of(15, 0));
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.isSchedulerEnabled();
    }
}

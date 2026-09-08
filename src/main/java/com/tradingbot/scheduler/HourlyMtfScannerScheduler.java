package com.tradingbot.scheduler;

import com.tradingbot.model.strategy.MtfTrendStatus;
import com.tradingbot.service.MultiTimeframeScannerService;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Hourly Scheduler that scans the NIFTY 200 universe for multi-timeframe uptrend confluence
 * (Weekly, Daily, and Hourly) during market hours and alerts the user via Telegram.
 */
@Service
public class HourlyMtfScannerScheduler {

    private static final Logger log = LoggerFactory.getLogger(HourlyMtfScannerScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30, 59);

    private final MultiTimeframeScannerService scannerService;

    @Value("${trading-bot.strategy.mtf-scanner.enabled:true}")
    private boolean enabled = true;

    @Autowired
    public HourlyMtfScannerScheduler(MultiTimeframeScannerService scannerService) {
        this.scannerService = scannerService;
    }

    /**
     * Scheduled hourly scan running during active trading weekdays (Monday through Friday).
     *
     * <p>Default schedule triggers at 15 minutes past each hour (10:15, 11:15, 12:15, 13:15, 14:15,
     * 15:15 IST), perfectly aligned with closed NSE 1-hour candles.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.mtf-scanner.cron:0 15 10-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public List<MtfTrendStatus> runScheduledScan() {
        if (!enabled) {
            log.info(
                    "[HOURLY-MTF-SCHEDULER] Multi-Timeframe Scanner is disabled in configuration.");
            return List.of();
        }

        LocalTime now = LocalTime.now(IST);
        if (!isWithinMarketHours(now)) {
            log.info(
                    "[HOURLY-MTF-SCHEDULER] Outside NSE market trading hours (09:15 - 15:30 IST). Skipping scan.");
            return List.of();
        }

        return runScanAndNotify();
    }

    public boolean isWithinMarketHours(LocalTime time) {
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Executes the multi-timeframe scan across NIFTY 200 (both Gainers and Losers) and sends the
     * Telegram report. Can be invoked programmatically on-demand or by the scheduled cron.
     */
    public List<MtfTrendStatus> runScanAndNotify() {
        log.info(
                "[HOURLY-MTF-SCHEDULER] Starting hourly Multi-Timeframe scan across NIFTY 200 (Gainers & Losers)...");
        long start = System.currentTimeMillis();

        List<MtfTrendStatus> allScanned = scannerService.scanAllNifty200();
        List<MtfTrendStatus> uptrend = scannerService.getConfluenceUptrendStocks(allScanned);
        List<MtfTrendStatus> downtrend = scannerService.getConfluenceDowntrendStocks(allScanned);

        long elapsed = System.currentTimeMillis() - start;
        log.info(
                "[HOURLY-MTF-SCHEDULER] Scan completed in {} ms. Found {} Gainers (Uptrend) & {} Losers (Downtrend) / {} total scanned.",
                elapsed,
                uptrend.size(),
                downtrend.size(),
                allScanned.size());

        scannerService.sendTelegramReport(uptrend, downtrend, allScanned.size());

        List<MtfTrendStatus> combined = new java.util.ArrayList<>(uptrend);
        combined.addAll(downtrend);
        return combined;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

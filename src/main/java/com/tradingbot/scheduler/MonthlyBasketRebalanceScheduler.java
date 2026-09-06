package com.tradingbot.scheduler;

import com.tradingbot.service.BasketHealthScoringService;
import com.tradingbot.util.StockFnoRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly Basket Rebalance Scheduler for Triple SuperTrend Options Strategy. Runs on the 1st of
 * every month at 09:00:00 IST (pre-market open). Evaluates the broader F&O stock universe, selects
 * the Top 10 Curated Momentum Equities, updates the active trading basket, and dispatches the
 * monthly rebalancing report to Telegram.
 */
@Component
public class MonthlyBasketRebalanceScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(MonthlyBasketRebalanceScheduler.class);

    private final BasketHealthScoringService scoringService;

    @Value("${trading-bot.rebalance.enabled:true}")
    private boolean rebalanceEnabled = true;

    @Value("${trading-bot.rebalance.lookback-days:30}")
    private int lookbackDays = 30;

    @Autowired
    public MonthlyBasketRebalanceScheduler(BasketHealthScoringService scoringService) {
        this.scoringService = scoringService;
    }

    /**
     * Executes on the 1st of every month at 09:00:00 IST. Cron expression: Second Minute Hour
     * Day-of-Month Month Day-of-Week
     */
    @Scheduled(cron = "${trading-bot.rebalance.cron:0 0 9 1 * ?}", zone = "Asia/Kolkata")
    public void onMonthlyRebalanceSchedule() {
        if (!rebalanceEnabled) {
            log.info(
                    "[MONTHLY-REBALANCE] Scheduled monthly rebalance skipped (disabled in config)");
            return;
        }

        log.info(
                "[MONTHLY-REBALANCE] 1st of Month Triggered: Commencing F&O basket rebalancing...");
        runMonthlyRebalance();
    }

    /**
     * Runs the quantitative rebalance across all candidates and updates the live curated basket.
     */
    public List<String> runMonthlyRebalance() {
        try {
            List<BasketHealthScoringService.StockHealthScore> ranked =
                    scoringService.rankAllSymbols(lookbackDays);

            if (ranked.isEmpty()) {
                log.warn("[MONTHLY-REBALANCE] No symbols could be ranked; keeping existing basket");
                return StockFnoRegistry.getCuratedSymbols();
            }

            List<String> top10 =
                    ranked.stream()
                            .filter(BasketHealthScoringService.StockHealthScore::isTop10)
                            .map(BasketHealthScoringService.StockHealthScore::symbol)
                            .toList();

            log.info(
                    "[MONTHLY-REBALANCE] Selected Top 10 Curated Basket for this Month: {}", top10);

            // Update the live registry
            StockFnoRegistry.setCuratedSymbols(top10);

            // Dispatch Telegram report
            scoringService.sendRebalanceTelegramReport(ranked);

            return top10;
        } catch (Exception e) {
            log.error(
                    "[MONTHLY-REBALANCE] Error during monthly rebalance execution: {}",
                    e.getMessage(),
                    e);
            return StockFnoRegistry.getCuratedSymbols();
        }
    }
}

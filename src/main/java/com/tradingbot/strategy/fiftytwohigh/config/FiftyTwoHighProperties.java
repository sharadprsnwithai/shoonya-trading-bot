package com.tradingbot.strategy.fiftytwohigh.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the NSE F&amp;O 52-week-high breakout scanner.
 *
 * <p>The scanner evaluates the most recent <em>completed</em> daily candle of every NSE F&amp;O
 * equity and reports the symbols whose daily high crossed above the high of the preceding 52 weeks
 * (a "fresh" 52-week-high breakout). It runs each trading morning at 09:25 IST — after the day's
 * candle has been finalized and synced — so the report always describes a settled session rather
 * than an in-progress one.
 */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.fifty-two-high")
public class FiftyTwoHighProperties {

    /** Master switch for the scanner (compute + report). */
    private boolean enabled = true;

    /**
     * Whether the 09:25 IST scheduler is armed (independent of the REST/Telegram on-demand path).
     */
    private boolean schedulerEnabled = true;

    /** Cron expression for the scheduled scan (IST). Defaults to 09:25 IST, weekdays. */
    private String cron = "0 25 9 ? * MON-FRI";

    /** Whether a scheduled/manual scan pushes a Telegram summary. */
    private boolean telegramAlertsEnabled = true;

    /** Calendar-day lookback that defines the "52 weeks" prior-high window (52 * 7 = 364). */
    private int lookbackDays = 364;

    /**
     * Minimum number of prior sessions required before a "52-week high" is considered meaningful.
     * Guards against thin history on recent listings producing a trivially "new" high.
     */
    private int minPriorSessions = 60;

    /**
     * Minimum percentage the latest high must exceed the prior 52-week high by to count as a
     * breakout. {@code 0.0} means any strict cross qualifies.
     */
    private double minBreakoutPct = 0.0;

    /**
     * When {@code true}, only close-confirmed breakouts are reported (the close must also finish
     * above the prior 52-week high). When {@code false}, a high-only intraday cross qualifies.
     */
    private boolean requireCloseConfirmation = false;

    /**
     * Optional explicit universe. When non-empty, only these symbols are scanned; when empty, the
     * full NSE F&amp;O equity universe (indices excluded) from {@code StockFnoRegistry} is used.
     */
    private List<String> symbols = new ArrayList<>();

    /** Years of daily history fetched per symbol (bounded to Yahoo's 1y-5y range). */
    private int yearsBack = 2;

    /**
     * If the latest cached daily candle is older than this many calendar days, the symbol is
     * re-synced before evaluation so a stale cache never masquerades as the latest session.
     */
    private int maxStaleCalendarDays = 4;

    /** How many breakouts a Telegram message lists before truncating (REST always returns all). */
    private int telegramMaxSymbols = 15;

    /**
     * Master switch for the experimental "trade-ready setup" filter layered on top of the raw
     * breakout scan. When enabled, breakouts are scored against the five research filters (volume
     * participation, 200-DMA trend, 20d momentum, relative strength vs NIFTY, NIFTY regime) and
     * only those clearing <em>all</em> are surfaced as trade-ready setups — the 09:25 Telegram
     * alert is then gated to fire only when at least one setup qualifies, carrying the recommended
     * spread strikes.
     *
     * <p>In-sample research signal. Validate (paper-trade) before sizing.
     */
    private boolean tradeSetupEnabled = true;

    /**
     * Minimum breakout-session volume, as a multiple of its 20-session average, to count as genuine
     * participation. Backtested default: 1.3.
     */
    private double tradeSetupMinVolumeRatio = 1.3;

    /**
     * Strikes above ATM for the <em>long</em> leg of the recommended bull-call debit spread (e.g. 4
     * → buy ATM+4).
     */
    private int tradeSetupLongStrikeOffset = 4;

    /**
     * Strikes above ATM for the <em>short</em> leg of the recommended bull-call debit spread (e.g.
     * 8 → sell ATM+8). Always forced above the long offset.
     */
    private int tradeSetupShortStrikeOffset = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public boolean isTelegramAlertsEnabled() {
        return telegramAlertsEnabled;
    }

    public void setTelegramAlertsEnabled(boolean telegramAlertsEnabled) {
        this.telegramAlertsEnabled = telegramAlertsEnabled;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public void setLookbackDays(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }

    public int getMinPriorSessions() {
        return minPriorSessions;
    }

    public void setMinPriorSessions(int minPriorSessions) {
        this.minPriorSessions = minPriorSessions;
    }

    public double getMinBreakoutPct() {
        return minBreakoutPct;
    }

    public void setMinBreakoutPct(double minBreakoutPct) {
        this.minBreakoutPct = minBreakoutPct;
    }

    public boolean isRequireCloseConfirmation() {
        return requireCloseConfirmation;
    }

    public void setRequireCloseConfirmation(boolean requireCloseConfirmation) {
        this.requireCloseConfirmation = requireCloseConfirmation;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols != null ? new ArrayList<>(symbols) : new ArrayList<>();
    }

    public int getYearsBack() {
        return yearsBack;
    }

    public void setYearsBack(int yearsBack) {
        this.yearsBack = yearsBack;
    }

    public int getMaxStaleCalendarDays() {
        return maxStaleCalendarDays;
    }

    public void setMaxStaleCalendarDays(int maxStaleCalendarDays) {
        this.maxStaleCalendarDays = maxStaleCalendarDays;
    }

    public int getTelegramMaxSymbols() {
        return telegramMaxSymbols;
    }

    public void setTelegramMaxSymbols(int telegramMaxSymbols) {
        this.telegramMaxSymbols = telegramMaxSymbols;
    }

    public boolean isTradeSetupEnabled() {
        return tradeSetupEnabled;
    }

    public void setTradeSetupEnabled(boolean tradeSetupEnabled) {
        this.tradeSetupEnabled = tradeSetupEnabled;
    }

    public double getTradeSetupMinVolumeRatio() {
        return tradeSetupMinVolumeRatio;
    }

    public void setTradeSetupMinVolumeRatio(double tradeSetupMinVolumeRatio) {
        this.tradeSetupMinVolumeRatio = tradeSetupMinVolumeRatio;
    }

    public int getTradeSetupLongStrikeOffset() {
        return tradeSetupLongStrikeOffset;
    }

    public void setTradeSetupLongStrikeOffset(int tradeSetupLongStrikeOffset) {
        this.tradeSetupLongStrikeOffset = tradeSetupLongStrikeOffset;
    }

    public int getTradeSetupShortStrikeOffset() {
        return tradeSetupShortStrikeOffset;
    }

    public void setTradeSetupShortStrikeOffset(int tradeSetupShortStrikeOffset) {
        this.tradeSetupShortStrikeOffset = tradeSetupShortStrikeOffset;
    }
}

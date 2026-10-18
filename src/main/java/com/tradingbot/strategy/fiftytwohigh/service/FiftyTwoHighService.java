package com.tradingbot.strategy.fiftytwohigh.service;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.fiftytwohigh.BreakoutTradeSetupCalculator;
import com.tradingbot.strategy.fiftytwohigh.FiftyTwoHighCalculator;
import com.tradingbot.strategy.fiftytwohigh.FiftyTwoHighCalculator.Outcome;
import com.tradingbot.strategy.fiftytwohigh.FiftyTwoHighCalculator.Status;
import com.tradingbot.strategy.fiftytwohigh.config.FiftyTwoHighProperties;
import com.tradingbot.strategy.fiftytwohigh.model.BreakoutTradeSetup;
import com.tradingbot.strategy.fiftytwohigh.model.FiftyTwoHighBreakout;
import com.tradingbot.strategy.fiftytwohigh.model.FiftyTwoHighReport;
import com.tradingbot.strategy.fiftytwohigh.model.NiftyHaStatus;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.BotLocale;
import com.tradingbot.util.HeikinAshiUtil;
import com.tradingbot.util.NiftySectorRegistry;
import com.tradingbot.util.NseTradingCalendarUtil;
import com.tradingbot.util.StockFnoRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the NSE F&amp;O 52-week-high breakout scan.
 *
 * <p>For every NSE F&amp;O equity (indices excluded) the scanner reads the symbol's most recent
 * completed daily candle and evaluates — via {@link FiftyTwoHighCalculator} — whether that candle's
 * high crossed above the high of the preceding 52 weeks. Results are aggregated into a {@link
 * FiftyTwoHighReport}, optionally pushed to Telegram, and served over REST.
 *
 * <p>Daily candles only: no intraday resampling is used. The scan relies on the shared OHLC cache
 * (normally warmed by the pre-open sync); any symbol whose latest cached candle is stale is
 * re-synced on demand so a warm-but-old cache can never masquerade as the latest session.
 */
@Service
public class FiftyTwoHighService {

    private static final Logger log = LoggerFactory.getLogger(FiftyTwoHighService.class);
    private static final String UNIVERSE_LABEL = "NSE F&O equities";
    private static final String NIFTY_50 = "NIFTY 50";

    private final FiftyTwoHighProperties properties;
    private final FiftyTwoHighCalculator calculator;
    private final BreakoutTradeSetupCalculator setupCalculator;
    private final HistoricalOhlcCacheService ohlcCache;
    private final TelegramService telegramService;

    @Autowired
    public FiftyTwoHighService(
            FiftyTwoHighProperties properties,
            FiftyTwoHighCalculator calculator,
            BreakoutTradeSetupCalculator setupCalculator,
            HistoricalOhlcCacheService ohlcCache,
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.calculator = calculator;
        this.setupCalculator = setupCalculator;
        this.ohlcCache = ohlcCache;
        this.telegramService = telegramService;
    }

    /**
     * Runs the scan across the universe and returns the aggregated report (no Telegram dispatch).
     */
    public FiftyTwoHighReport scan() {
        List<String> universe = resolveUniverse();
        log.info(
                "[52WH] Scanning {} symbols from universe ({}): {}... ",
                universe.size(),
                UNIVERSE_LABEL,
                universe.size() <= 12 ? universe : "full F&O list");

        List<FiftyTwoHighBreakout> breakouts = new ArrayList<>();
        LocalDate evaluatedSession = null;
        int evaluated = 0;
        int insufficient = 0;
        int unavailable = 0;

        List<Outcome> outcomes = evaluateUniverse(universe);
        for (Outcome o : outcomes) {
            if (o.sessionDate() != null
                    && (evaluatedSession == null || o.sessionDate().isAfter(evaluatedSession))) {
                evaluatedSession = o.sessionDate();
            }
            switch (o.status()) {
                case BREAKOUT -> breakouts.add(o.breakout());
                case NO_BREAKOUT -> evaluated++;
                case INSUFFICIENT_HISTORY -> insufficient++;
                case NO_DATA -> unavailable++;
            }
        }
        evaluated += breakouts.size();

        breakouts.sort(
                Comparator.comparing(FiftyTwoHighBreakout::breakoutPct)
                        .reversed()
                        .thenComparing(FiftyTwoHighBreakout::symbol));

        String summary =
                String.format(
                        BotLocale.INSTANCE,
                        "%d breakout(s) of %d %s evaluated for session %s"
                                + " (%d insufficient history, %d no data).",
                        breakouts.size(),
                        evaluated,
                        UNIVERSE_LABEL,
                        evaluatedSession != null ? evaluatedSession.toString() : "n/a",
                        insufficient,
                        unavailable);

        NiftyHaStatus niftyHa = computeNiftyHaStatus();
        List<BreakoutTradeSetup> tradeReadySetups = computeTradeReadySetups(breakouts);

        FiftyTwoHighReport report =
                new FiftyTwoHighReport(
                        Instant.now(),
                        evaluatedSession,
                        niftyHa,
                        UNIVERSE_LABEL,
                        universe.size(),
                        evaluated,
                        insufficient,
                        unavailable,
                        List.copyOf(breakouts),
                        tradeReadySetups,
                        summary);

        log.info("[52WH] {}", report.summary());
        return report;
    }

    /**
     * Runs the scan and — when the trade-setup filter is enabled — dispatches a Telegram alert
     * <em>only</em> if at least one close-confirmed breakout cleared all five filters. The alert
     * carries just those trade-ready setups (with the recommended spread strikes). When the filter
     * is disabled, falls back to the legacy full-breakout summary.
     *
     * @return the aggregated report
     */
    public FiftyTwoHighReport scanAndAlert() {
        FiftyTwoHighReport report = scan();
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) {
            return report;
        }
        try {
            if (properties.isTradeSetupEnabled()) {
                List<BreakoutTradeSetup> setups = report.tradeReadySetups();
                if (setups == null || setups.isEmpty()) {
                    log.info(
                            "[52WH] Trade-setup filter active but no breakout cleared all filters; "
                                    + "suppressing Telegram alert.");
                    return report;
                }
                telegramService.sendTextMessage(formatTradeReadyMessage(report));
            } else {
                telegramService.sendTextMessage(formatTelegramMessage(report));
            }
        } catch (Exception e) {
            log.error("[52WH] Failed to dispatch Telegram report: {}", e.getMessage(), e);
        }
        return report;
    }

    /** Evaluates a single symbol on demand and returns its breakout, or null when none. */
    public FiftyTwoHighBreakout scanSingle(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String clean = symbol.trim().toUpperCase();
        Outcome o = evaluateSymbol(clean);
        return o.status() == Status.BREAKOUT ? o.breakout() : null;
    }

    /**
     * Resolves the symbols to scan: the configured list when present, otherwise every NSE F&amp;O
     * equity in {@link StockFnoRegistry} (indices and BSE/SENSEX excluded, aliases deduped by
     * token).
     *
     * @return ordered universe, never null
     */
    List<String> resolveUniverse() {
        List<String> configured = properties.getSymbols();
        if (configured != null && !configured.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String s : configured) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim().toUpperCase());
                }
            }
            return out;
        }

        Set<String> seenTokens = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (var e : StockFnoRegistry.getAllInstruments().entrySet()) {
            String sym = e.getKey();
            StockFnoRegistry.InstrumentInfo info = e.getValue();
            if (info == null || !"NSE".equalsIgnoreCase(info.exchange())) {
                continue; // exclude BSE/SENSEX-listed derivatives
            }
            if (StockFnoRegistry.isIndex(sym)) {
                continue; // exclude benchmark indices (NIFTY*, BANKNIFTY, FINNIFTY, MIDCPNIFTY)
            }
            if (!seenTokens.add(info.token())) {
                continue; // dedupe ticker aliases that share an instrument token
            }
            out.add(sym);
        }
        return out;
    }

    /** Evaluates the universe in parallel with a small bounded pool (polite to the data source). */
    private List<Outcome> evaluateUniverse(List<String> universe) {
        if (universe.isEmpty()) {
            return List.of();
        }
        int poolSize = Math.max(1, Math.min(6, universe.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Outcome> outcomes = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<Outcome>> futures = new ArrayList<>();
            for (String sym : universe) {
                futures.add(executor.submit(() -> evaluateSymbol(sym)));
            }
            for (java.util.concurrent.Future<Outcome> f : futures) {
                try {
                    outcomes.add(f.get(60, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("[52WH] Evaluation failed/timed out: {}", e.getMessage());
                    outcomes.add(new Outcome(Status.NO_DATA, null, null));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return outcomes;
    }

    /**
     * Evaluates one symbol: reads its daily candles (refreshing first when the cached series is
     * stale), then runs the pure calculator.
     *
     * @param symbol normalized symbol
     * @return outcome, never null
     */
    private Outcome evaluateSymbol(String symbol) {
        try {
            List<Candle> daily = getCompletedDaily(symbol);
            if (daily == null || daily.isEmpty()) {
                return new Outcome(Status.NO_DATA, null, null);
            }
            String sector = NiftySectorRegistry.getSectorForSymbol(symbol);
            int lotSize = StockFnoRegistry.getLotSize(symbol);
            return calculator.evaluate(symbol, daily, sector, lotSize);
        } catch (Exception e) {
            log.warn("[52WH] Error evaluating {}: {}", symbol, e.getMessage());
            return new Outcome(Status.NO_DATA, null, null);
        }
    }

    /**
     * Loads a symbol's daily candles, re-syncing first when the cached series is stale, then drops
     * any in-progress (today) candle so the caller always sees the last fully completed session.
     *
     * @param symbol NSE symbol or index label (e.g. "NIFTY 50")
     * @return completed-session daily candles; empty when unavailable
     */
    private List<Candle> getCompletedDaily(String symbol) {
        try {
            List<Candle> daily = ohlcCache.getDailyCandles(symbol);
            if (isStale(daily)) {
                ohlcCache.syncSymbol(symbol, Math.max(1, properties.getYearsBack()));
                daily = ohlcCache.getDailyCandles(symbol);
            }
            return retainCompletedSessions(daily);
        } catch (Exception e) {
            log.warn("[52WH] Failed to load daily candles for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * Scores each close-confirmed breakout against the five research filters and returns those that
     * cleared all of them (strongest volume participation first). NIFTY 50 daily candles are read
     * once and shared across symbols for the relative-strength and market-regime gates.
     *
     * @param breakouts the raw breakouts from the scan
     * @return qualifying trade-ready setups (empty when disabled or nothing qualified)
     */
    private List<BreakoutTradeSetup> computeTradeReadySetups(List<FiftyTwoHighBreakout> breakouts) {
        if (!properties.isTradeSetupEnabled() || breakouts == null || breakouts.isEmpty()) {
            return List.of();
        }
        List<Candle> niftyDaily = getCompletedDaily(NIFTY_50);
        List<BreakoutTradeSetup> setups = new ArrayList<>();
        for (FiftyTwoHighBreakout b : breakouts) {
            if (!b.closeConfirmed()) {
                continue; // the filter only qualifies close-confirmed crosses
            }
            List<Candle> daily = getCompletedDaily(b.symbol());
            if (daily == null || daily.isEmpty()) {
                continue;
            }
            BreakoutTradeSetup setup =
                    setupCalculator.evaluate(b.symbol(), daily, niftyDaily, b.lotSize());
            if (setup != null && setup.tradeReady()) {
                setups.add(setup);
            }
        }
        setups.sort(
                Comparator.comparing(
                                BreakoutTradeSetup::volumeRatio,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(BreakoutTradeSetup::symbol));
        return List.copyOf(setups);
    }

    /**
     * Drops any candle dated today or later so the scanner always evaluates the last <em>fully
     * completed</em> session. At the 09:25 IST run the market opened minutes ago (09:15), so a
     * fresh fetch could surface today's in-progress candle; excluding it keeps the report
     * describing a settled session rather than a partial one.
     *
     * @param daily chronological daily candles (may be null/empty)
     * @return candles strictly before today, never null-safe to iterate
     */
    private List<Candle> retainCompletedSessions(List<Candle> daily) {
        if (daily == null || daily.isEmpty()) {
            return daily;
        }
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        List<Candle> completed = new ArrayList<>();
        for (Candle c : daily) {
            if (c == null || c.timestamp() == null) {
                continue;
            }
            LocalDate d = c.timestamp().atZone(NseTradingCalendarUtil.IST_ZONE).toLocalDate();
            if (d.isBefore(today)) {
                completed.add(c);
            }
        }
        return completed;
    }

    /** True when the latest daily candle is older than the configured staleness tolerance. */
    private boolean isStale(List<Candle> daily) {
        if (daily == null || daily.isEmpty()) {
            return true;
        }
        Candle latest = daily.get(daily.size() - 1);
        if (latest == null || latest.timestamp() == null) {
            return true;
        }
        LocalDate latestDate =
                latest.timestamp().atZone(NseTradingCalendarUtil.IST_ZONE).toLocalDate();
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        return latestDate.isBefore(
                today.minusDays(Math.max(1, properties.getMaxStaleCalendarDays())));
    }

    /**
     * Computes the Heikin-Ashi posture (color + streak) of the NIFTY 50 index on the last completed
     * session. Reads the shared daily OHLC cache and excludes any in-progress (today) candle so the
     * color reflects a settled bar — matching the breakout scan's evaluation session.
     *
     * @return the NIFTY 50 HA status, or {@code null} when index data is unavailable
     */
    private NiftyHaStatus computeNiftyHaStatus() {
        try {
            List<Candle> daily = retainCompletedSessions(ohlcCache.getDailyCandles(NIFTY_50));
            if (daily == null || daily.size() < 2) {
                log.warn("[52WH] NIFTY 50 daily data unavailable for Heikin-Ashi status.");
                return null;
            }
            List<Candle> ha = HeikinAshiUtil.toHeikinAshi(daily);
            if (ha.isEmpty()) {
                return null;
            }
            Candle last = ha.get(ha.size() - 1);
            NiftyHaStatus.Color color;
            if (HeikinAshiUtil.isGreen(last)) {
                color = NiftyHaStatus.Color.GREEN;
            } else if (HeikinAshiUtil.isRed(last)) {
                color = NiftyHaStatus.Color.RED;
            } else {
                color = NiftyHaStatus.Color.FLAT;
            }
            return new NiftyHaStatus(
                    last.timestamp().atZone(NseTradingCalendarUtil.IST_ZONE).toLocalDate(),
                    color,
                    last.open(),
                    last.high(),
                    last.low(),
                    last.close(),
                    HeikinAshiUtil.trendStreak(ha),
                    ha.size());
        } catch (Exception e) {
            log.warn("[52WH] Failed to compute NIFTY 50 Heikin-Ashi status: {}", e.getMessage());
            return null;
        }
    }

    /** Formats the report into a crisp Markdown Telegram message. */
    public String formatTelegramMessage(FiftyTwoHighReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 *52-WEEK HIGH BREAKOUT SCAN* (NSE F&O Equities)\n");
        sb.append("📅 *Session:* `")
                .append(report.evaluatedSession() != null ? report.evaluatedSession() : "n/a")
                .append("` | *Universe:* ")
                .append(report.universeSize())
                .append(" symbols\n");

        NiftyHaStatus ha = report.niftyHa();
        if (ha != null) {
            String emoji =
                    switch (ha.color()) {
                        case GREEN -> "🟩";
                        case RED -> "🟥";
                        case FLAT -> "⬜";
                    };
            sb.append(emoji)
                    .append(" *NIFTY 50 Heikin-Ashi* (")
                    .append(ha.sessionDate())
                    .append("): *")
                    .append(ha.color())
                    .append("*");
            if (ha.streak() > 0) {
                sb.append(" • ").append(ha.streak()).append("-bar streak");
            }
            sb.append(" • HA ₹")
                    .append(ha.haOpen())
                    .append(" → ₹")
                    .append(ha.haClose())
                    .append("\n");
        } else {
            sb.append("⬜ *NIFTY 50 Heikin-Ashi:* data unavailable\n");
        }

        sb.append("✅ *Breakouts:* ")
                .append(report.breakouts().size())
                .append("  •  Evaluated: ")
                .append(report.evaluatedCount())
                .append("  •  Thin history: ")
                .append(report.insufficientHistoryCount())
                .append("  •  No data: ")
                .append(report.dataUnavailableCount())
                .append("\n\n");

        List<BreakoutTradeSetup> setups =
                report.tradeReadySetups() != null ? report.tradeReadySetups() : List.of();
        sb.append("🎯 *Trade-ready (all filters):* ").append(setups.size()).append("\n");
        for (BreakoutTradeSetup s : setups) {
            sb.append(" 🔸 *")
                    .append(s.symbol())
                    .append("* — BUY ₹")
                    .append(s.buyStrike())
                    .append("C / SELL ₹")
                    .append(s.sellStrike())
                    .append("C  (vol ")
                    .append(s.volumeRatio())
                    .append("×, 20d ")
                    .append(s.momentum20dPct())
                    .append("%, RS ")
                    .append(s.relativeStrengthPct())
                    .append("%)\n");
        }
        sb.append("\n");

        if (report.breakouts().isEmpty()) {
            sb.append("_No F&O equity crossed its 52-week high on the latest daily candle._\n");
            return sb.toString();
        }

        int limit = Math.max(1, properties.getTelegramMaxSymbols());
        int shown = 0;
        for (FiftyTwoHighBreakout b : report.breakouts()) {
            if (shown >= limit) {
                break;
            }
            String confirm = b.closeConfirmed() ? "✅ close-confirmed" : "⚠️ wick only";
            sb.append("🔹 *")
                    .append(b.symbol())
                    .append("*")
                    .append(b.sector() != null && !b.sector().isBlank() ? " · " + b.sector() : "")
                    .append("\n");
            sb.append(" • High ₹")
                    .append(b.latestHigh())
                    .append(" crossed 52w high ₹")
                    .append(b.prior52wHigh())
                    .append(" (`+")
                    .append(b.breakoutPct())
                    .append("%`)\n");
            sb.append(" • Close ₹")
                    .append(b.latestClose())
                    .append(" [")
                    .append(confirm)
                    .append("] • Vol ")
                    .append(formatVolume(b.latestVolume()))
                    .append(" • Lot ")
                    .append(b.lotSize())
                    .append("\n\n");
            shown++;
        }

        int remaining = report.breakouts().size() - shown;
        if (remaining > 0) {
            sb.append("_…and ").append(remaining).append(" more (see REST report)._");
        }
        return sb.toString();
    }

    /**
     * Formats the 09:25 Telegram alert: only the trade-ready setups that cleared all five filters,
     * each with its recommended bull-call-spread strikes. Carries an experimental disclaimer since
     * the filter encodes in-sample research.
     *
     * @param report the scan report (its {@code tradeReadySetups} drive the message)
     * @return a crisp Markdown Telegram message
     */
    public String formatTradeReadyMessage(FiftyTwoHighReport report) {
        List<BreakoutTradeSetup> setups =
                report.tradeReadySetups() != null ? report.tradeReadySetups() : List.of();
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 *52-WEEK HIGH · TRADE-READY SETUPS* (NSE F&O)\n");
        sb.append("📅 `")
                .append(report.evaluatedSession() != null ? report.evaluatedSession() : "n/a")
                .append("` • *")
                .append(setups.size())
                .append("* passed all filters\n");

        NiftyHaStatus ha = report.niftyHa();
        if (ha != null) {
            String emoji =
                    switch (ha.color()) {
                        case GREEN -> "🟩";
                        case RED -> "🟥";
                        case FLAT -> "⬜";
                    };
            sb.append(emoji).append(" NIFTY 50 HA: *").append(ha.color()).append("*");
            if (ha.streak() > 0) {
                sb.append(" • ").append(ha.streak()).append("-bar");
            }
            sb.append("\n");
        }
        sb.append("\n");

        int limit = Math.max(1, properties.getTelegramMaxSymbols());
        int shown = 0;
        for (BreakoutTradeSetup s : setups) {
            if (shown >= limit) {
                break;
            }
            sb.append("🔸 *")
                    .append(s.symbol())
                    .append("*  · Lot ")
                    .append(s.lotSize())
                    .append("\n");
            sb.append(" • Spot ₹")
                    .append(s.spotRef())
                    .append(" • Vol ")
                    .append(s.volumeRatio())
                    .append("× 20d avg")
                    .append(" • 20d ")
                    .append(s.momentum20dPct())
                    .append("%")
                    .append(" • RS ")
                    .append(s.relativeStrengthPct())
                    .append("%\n");
            sb.append(" • 📦 BUY ₹")
                    .append(s.buyStrike())
                    .append("C / SELL ₹")
                    .append(s.sellStrike())
                    .append("C  (ATM+")
                    .append(properties.getTradeSetupLongStrikeOffset())
                    .append(" / ATM+")
                    .append(properties.getTradeSetupShortStrikeOffset())
                    .append(", near-monthly)\n");
            sb.append(" • Structure: hold-to-expiry · risk = debit · target = spread width\n\n");
            shown++;
        }

        int remaining = setups.size() - shown;
        if (remaining > 0) {
            sb.append("_…and ").append(remaining).append(" more (see REST report)._");
        } else {
            sb.append(
                    "_Experimental research signal (in-sample). Paper-trade & validate before sizing._");
        }
        return sb.toString();
    }

    /** Compact Indian-style volume formatting (Cr / L / K). */
    private static String formatVolume(long v) {
        if (v >= 1_00_00_000) {
            return String.format(BotLocale.INSTANCE, "%.2fCr", v / 1_00_00_000.0);
        }
        if (v >= 1_00_000) {
            return String.format(BotLocale.INSTANCE, "%.2fL", v / 1_00_000.0);
        }
        if (v >= 1_000) {
            return String.format(BotLocale.INSTANCE, "%.1fK", v / 1_000.0);
        }
        return String.valueOf(v);
    }
}

package com.tradingbot.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.NiftySectorRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * LIVE "what if" audit: replays <em>today's</em> real 5-minute candles for the whole LVR universe
 * through the production {@link LowestVolumeReversalService#replaySession}, under three gate
 * configurations, so the effect of the PCR changes can be measured on actual market data.
 *
 * <p>The three passes are:
 *
 * <ol>
 *   <li><b>A — NEW (production)</b>: PCR filter on with the 0.85–1.15 neutral no-trade band, plus
 *       the Option S&amp;R filter.
 *   <li><b>B — PCR off</b>: same strategy, only the Option S&amp;R filter left on. Isolates what
 *       the PCR gate itself costs.
 *   <li><b>C — option gates off</b>: neither PCR nor S&amp;R. Isolates the whole option-chain
 *       contribution.
 * </ol>
 *
 * <p>Every EXHAUST gate denial emitted by the replay is captured from the logger and bucketed by
 * gate name, so the audit reports not just <em>whether</em> a trade was taken but <em>why</em> it
 * was not.
 *
 * <p>Known limitations, stated openly because they bound the conclusion:
 *
 * <ul>
 *   <li>The option chain is read <b>once, live, after the close</b>, so every intraday decision is
 *       evaluated against today's closing PCR and closing max-OI strikes. Intraday PCR drift is not
 *       modelled.
 *   <li>PDH/PDL come from the daily feed rather than the live cache (the cache bean is not wired in
 *       this non-Spring harness).
 * </ul>
 *
 * <p>Needs a {@code .env} with working Shoonya credentials and a whitelisted public IP; without
 * them the test aborts via a JUnit assumption instead of failing.
 */
@Tag("live")
@DisplayName("LIVE: replay today's 5-min candles through the LVR strategy (gate audit)")
class LvrTodayGateReplayAuditTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String RULE = "=".repeat(118);
    private static final String SUBRULE = "-".repeat(118);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Replay workers per pass; the bottleneck is serial HTTP round-trips, not CPU. */
    private static final int WORKERS = Integer.getInteger("lvr.audit.workers", 4);

    /** Set to an ISO date (yyyy-MM-dd) to replay a specific session instead of the latest one. */
    private static final String SESSION_OVERRIDE = System.getProperty("lvr.audit.sessionDate");

    /** The session the replay ended up using; resolved from the data, not from the wall clock. */
    private LocalDate resolvedSessionDate;

    /** Matches the EXHAUST line emitted by {@code replaySession} when a gate kills the symbol. */
    private static final Pattern DISCARD =
            Pattern.compile("discarded for the day: \\[([^]]+)] (.*)");

    /** PCR denial kinds, split into behaviour that changed and behaviour that did not. */
    private static final String PCR_NEUTRAL_BAND = "neutral band (NEW behaviour)";

    private static final String PCR_DIRECTION = "direction mismatch (unchanged)";
    private static final String PCR_NO_DATA = "no valid PCR / fail-closed";
    private static final String PCR_OTHER = "other PCR denial";

    private ShoonyaConfig config;
    private ShoonyaMarketDataService marketDataService;
    private ShoonyaOptionChainService optionChainService;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;

    /** Wires up the real (non-mocked) Shoonya stack from {@code .env}. */
    @BeforeEach
    void connectToShoonya() {
        config = ShoonyaConfig.load();
        assumeTrue(config.isEnabled(), "SHOONYA_ENABLED=false — skipping live replay");
        assumeTrue(
                !config.getUserId().isBlank() && !config.getPassword().isBlank(),
                "No Shoonya credentials in .env — skipping live replay");
        assumeTrue(!config.getBaseUrl().isBlank(), "No Shoonya base URL — skipping live replay");

        ShoonyaAuthenticator authenticator = new ShoonyaAuthenticator(config);
        marketDataService = new ShoonyaMarketDataService(config, authenticator);
        optionChainService =
                new ShoonyaOptionChainService(config, authenticator, marketDataService);
        taService = new TechnicalAnalysisService();
        // Stands in for the real bot so an unguarded send can never NPE the replay.
        telegramService = Mockito.mock(TelegramService.class);
    }

    @Test
    @DisplayName("Today's universe replayed under the new PCR band vs. the same run without it")
    void replayTodayUnderThreeGateConfigs() {
        List<String> universe = universe();
        int cap = Integer.getInteger("lvr.audit.maxSymbols", universe.size());
        if (universe.size() > cap) {
            universe = new ArrayList<>(universe.subList(0, cap));
        }

        System.out.println(RULE);
        System.out.println(" LVR GATE AUDIT — REPLAY OF REAL SESSION CANDLES");
        System.out.println(RULE);
        System.out.printf(Locale.US, "%-28s: %d symbols%n", "Universe", universe.size());

        Map<String, List<Candle>> candles = fetchSessionCandles(universe);
        LocalDate sessionDate = resolvedSessionDate;
        System.out.printf(Locale.US, "%-28s: %s%n", "Session date (IST)", sessionDate);
        if (!sessionDate.equals(LocalDate.now(IST))) {
            System.out.printf(
                    Locale.US,
                    "%-28s: no candles for %s yet — replaying the last session that has data%n",
                    "Note",
                    LocalDate.now(IST));
        }
        System.out.printf(
                Locale.US,
                "%-28s: %d symbols have >=4 candles%n",
                "Fetched for session",
                candles.size());
        System.out.printf(Locale.US, "%-28s: %d%n", "Replay workers per pass", WORKERS);
        assumeTrue(!candles.isEmpty(), "No 5-min candles found — nothing to replay");

        PassResult passNew = runPass("A. NEW gate (PCR band 0.85-1.15 + S&R)", candles, true, true);
        PassResult passNoPcr = runPass("B. PCR gate OFF (S&R still on)", candles, false, true);
        PassResult passNoOpt = runPass("C. both option gates OFF", candles, false, false);

        printComparison(passNew, passNoPcr, passNoOpt, candles.size());

        assertThat(candles).as("session candles must exist for the replay").isNotEmpty();
        assertThat(passNew.symbolsReplayed)
                .as("every symbol with candles must have been replayed")
                .isEqualTo(candles.size());
        assertThat(passNew.trades).as("trade rows must be populated").isNotNull();

        System.out.println(RULE);
    }

    // ---------------------------------------------------------------- pass plumbing

    /**
     * Replays every symbol in both directions through a fresh service instance, capturing the gate
     * denials it logs.
     *
     * @param label human label for the printed section
     * @param pcrEnabled whether the PCR filter participates in the entry gate
     * @param optionSrEnabled whether the Option S&amp;R filter participates
     * @param candles session candles keyed by symbol
     * @return the aggregated result of this pass
     */
    private PassResult runPass(
            String label,
            Map<String, List<Candle>> candles,
            boolean pcrEnabled,
            boolean optionSrEnabled) {
        PassResult result = new PassResult(label, pcrEnabled, optionSrEnabled);

        Recorder recorder = new Recorder();
        Logger logger = (Logger) LoggerFactory.getLogger(LowestVolumeReversalService.class);
        Level oldLevel = logger.getLevel();
        boolean oldAdditive = logger.isAdditive();
        logger.setAdditive(false); // keep the (large) capture out of the XML console log
        logger.setLevel(Level.DEBUG);
        logger.addAppender(recorder);
        recorder.start();

        // Every symbol costs a handful of serial HTTP round-trips (daily candles for PDH/PDL, an
        // option-chain read plus one quote per strike for each armed setup), so the loop is run
        // concurrently. Each worker owns its OWN service instance, which keeps the replay's
        // per-symbol state — armed setups, exhausted symbols, trade history — fully isolated.
        int workers = Math.max(1, Math.min(WORKERS, candles.size()));
        List<Map.Entry<String, List<Candle>>> entries =
                new ArrayList<>(new TreeMap<>(candles).entrySet());
        ForkJoinPool pool = new ForkJoinPool(workers);
        try {
            pool.submit(
                            () ->
                                    entries.parallelStream()
                                            .forEach(
                                                    entry ->
                                                            replaySymbol(
                                                                    entry,
                                                                    result,
                                                                    pcrEnabled,
                                                                    optionSrEnabled)))
                    .get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LVR replay interrupted", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("LVR replay failed", ex.getCause());
        } finally {
            pool.shutdown();
            recorder.stop();
            logger.detachAppender(recorder);
            logger.setLevel(oldLevel);
            logger.setAdditive(oldAdditive);
        }

        // The LVR logger is detached from the console during the pass, so keep its warnings on the
        // result — that is where "chain is synthetic / contract missing" shows up.
        result.warnings.addAll(recorder.warnings);

        for (String message : recorder.messages) {
            Matcher matcher = DISCARD.matcher(message);
            if (matcher.find()) {
                String gate = matcher.group(1);
                String reason = matcher.group(2);
                result.deniedByGate.merge(gate, 1, Integer::sum);
                String symbol =
                        messageBetween(message, "[LVR][REPLAY] ", " discarded for the day:");
                if (symbol != null) {
                    result.deniedSymbols.add(symbol);
                }
                if ("PCR".equals(gate)) {
                    result.deniedByPcrKind.merge(classifyPcr(reason), 1, Integer::sum);
                    if (PCR_NEUTRAL_BAND.equals(classifyPcr(reason)) && symbol != null) {
                        result.neutralBandBlocked.add(symbol);
                    }
                }
            }
        }

        printPass(result);
        return result;
    }

    /**
     * Replays one symbol in both directions through its own service instance, folding the outcome
     * into the shared result. Called from several workers at once, so every mutation of {@code
     * result} is synchronised.
     *
     * @param entry the symbol and its session candles
     * @param result shared accumulator for this pass
     * @param pcrEnabled whether the PCR filter participates in the entry gate
     * @param optionSrEnabled whether the Option S&amp;R filter participates
     */
    private void replaySymbol(
            Map.Entry<String, List<Candle>> entry,
            PassResult result,
            boolean pcrEnabled,
            boolean optionSrEnabled) {
        LowestVolumeReversalService service = newService();
        service.setPcrFilterEnabled(pcrEnabled);
        service.setOptionSrFilterEnabled(optionSrEnabled);
        service.setPcrNeutralMin(0.85);
        service.setPcrNeutralMax(1.15);

        result.markSymbolReplayed();
        for (LowestVolumeDirection direction :
                List.of(LowestVolumeDirection.LONG, LowestVolumeDirection.SHORT)) {
            try {
                for (LowestVolumePaperPosition pos :
                        service.replaySession(entry.getKey(), direction, entry.getValue())) {
                    result.add(pos);
                }
            } catch (RuntimeException ex) {
                // Keep the audit running across the whole universe; the failure is still reported
                // so a broken symbol cannot silently shrink the sample.
                result.addError(
                        entry.getKey()
                                + " "
                                + direction
                                + ": "
                                + ex.getClass().getSimpleName()
                                + " "
                                + ex.getMessage());
            }
        }
    }

    /**
     * Builds a service wired the way production is wired (FUTURES, PARTIAL exit, one attempt per
     * symbol, telegram off), minus the Spring-managed scanner/cache beans.
     *
     * @return a fresh, state-isolated service instance
     */
    private LowestVolumeReversalService newService() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(
                        marketDataService,
                        taService,
                        telegramService,
                        config,
                        optionChainService,
                        null,
                        null,
                        null);
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);
        service.setTelegramAlerts(false);
        service.setTelegramArmedAlerts(false);
        // .env sets LVR_MAX_ATTEMPTS_PER_SYMBOL=1 (the Java default is 2).
        service.setMaxAttemptsPerSymbol(1);
        return service;
    }

    /**
     * Buckets one PCR denial reason into the four kinds reported by the audit.
     *
     * @param reason the human-readable denial reason from {@code evaluateEntryGate}
     * @return a stable label used for aggregation
     */
    private static String classifyPcr(String reason) {
        if (reason.contains("neutral no-trade band")) {
            return PCR_NEUTRAL_BAND;
        }
        if (reason.contains("no valid PCR")) {
            return PCR_NO_DATA;
        }
        if (reason.contains("not confirmed for LONG")
                || reason.contains("not confirmed for SHORT")) {
            return PCR_DIRECTION;
        }
        return PCR_OTHER;
    }

    /**
     * Extracts a substring delimited by two markers.
     *
     * @param text haystack
     * @param start inclusive start marker
     * @param end exclusive end marker
     * @return the text between the markers, or {@code null} when either marker is missing
     */
    private static String messageBetween(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) {
            return null;
        }
        from += start.length();
        int to = text.indexOf(end, from);
        return to < 0 ? null : text.substring(from, to);
    }

    // ---------------------------------------------------------------- printing

    /**
     * Prints the trade rows and denial breakdown for one pass.
     *
     * @param result the pass to report
     */
    private void printPass(PassResult result) {
        System.out.println();
        System.out.println(SUBRULE);
        System.out.printf(
                Locale.US,
                " PASS %s   pcrFilter=%s  optionSrFilter=%s%n",
                result.label,
                result.pcrEnabled,
                result.optionSrEnabled);
        System.out.printf(
                Locale.US,
                "   trades=%d  wins=%d  losses=%d  winRate=%.1f%%  netPnl=₹%,.2f  netR=%+.2f%n",
                result.trades.size(),
                result.wins,
                result.losses,
                result.trades.isEmpty() ? 0.0 : result.wins * 100.0 / result.trades.size(),
                result.netPnl,
                result.netR);
        System.out.println(SUBRULE);

        if (result.trades.isEmpty()) {
            System.out.println("  (no trades taken)");
        } else {
            System.out.printf(
                    Locale.US,
                    "  %-10s %-6s %5s  %10s  %10s  %10s  %-26s %11s %7s%n",
                    "SYMBOL",
                    "DIR",
                    "TIME",
                    "ENTRY",
                    "SL",
                    "TARGET",
                    "EXIT REASON",
                    "P&L",
                    "R");
            for (TradeRow row : result.trades) {
                System.out.printf(
                        Locale.US,
                        "  %-10s %-6s %5s  %10.2f  %10.2f  %10.2f  %-26s %11.2f %+6.2f%n",
                        row.symbol,
                        row.direction,
                        row.entryTime,
                        row.entry,
                        row.sl,
                        row.target,
                        row.exitReason,
                        row.pnl,
                        row.r);
            }
        }

        System.out.println();
        System.out.println("  Gate denials (symbol discarded for the day):");
        if (result.deniedByGate.isEmpty()) {
            System.out.println("    none recorded");
        } else {
            result.deniedByGate.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(
                            e ->
                                    System.out.printf(
                                            Locale.US,
                                            "    %-14s %4d%n",
                                            e.getKey(),
                                            e.getValue()));
        }
        if (!result.deniedByPcrKind.isEmpty()) {
            System.out.println("  PCR denial split:");
            for (String kind : List.of(PCR_NEUTRAL_BAND, PCR_DIRECTION, PCR_NO_DATA, PCR_OTHER)) {
                Integer count = result.deniedByPcrKind.get(kind);
                if (count != null) {
                    System.out.printf(Locale.US, "    %-32s %4d%n", kind, count);
                }
            }
            if (!result.neutralBandBlocked.isEmpty()) {
                System.out.printf(
                        Locale.US,
                        "    symbols blocked ONLY by the new neutral band: %d -> %s%n",
                        result.neutralBandBlocked.size(),
                        preview(result.neutralBandBlocked));
            }
        }
        System.out.printf(
                Locale.US,
                "  Symbols that reached the entry gate (denied or traded): %d / %d%n",
                result.reachedGate(),
                result.symbolsReplayed);

        if (!result.warnings.isEmpty()) {
            System.out.printf(
                    Locale.US,
                    "  Warnings captured during the pass (%d):%n",
                    result.warnings.size());
            int shown = 0;
            for (String warning : result.warnings) {
                if (shown >= 10) {
                    System.out.printf(
                            Locale.US,
                            "    ... %d more warning(s)%n",
                            result.warnings.size() - shown);
                    break;
                }
                System.out.println("    ! " + warning);
                shown++;
            }
        }
        if (!result.errors.isEmpty()) {
            System.out.printf(Locale.US, "  REPLAY ERRORS (%d):%n", result.errors.size());
            result.errors.forEach(e -> System.out.println("    X " + e));
        }
    }

    /**
     * Prints the three-way comparison the audit exists for.
     *
     * @param a new production gate
     * @param b PCR gate disabled
     * @param c both option gates disabled
     * @param symbolCount how many symbols had replayable candles
     */
    private void printComparison(PassResult a, PassResult b, PassResult c, int symbolCount) {
        System.out.println();
        System.out.println(RULE);
        System.out.println(" COMPARISON — " + symbolCount + " symbols x 2 directions, FUTURES");
        System.out.println(RULE);
        System.out.printf(
                Locale.US,
                "  %-46s %7s %7s %7s %14s %8s%n",
                "PASS",
                "TRADES",
                "WINS",
                "LOSSES",
                "NET P&L (₹)",
                "NET R");
        printComparisonRow(a);
        printComparisonRow(b);
        printComparisonRow(c);
        System.out.println();

        int pcrDenials = a.deniedByGate.getOrDefault("PCR", 0);
        int srDenials = a.deniedByGate.getOrDefault("OPTION_SR", 0);
        System.out.printf(
                Locale.US,
                "  New gate (A) denied %d symbol(s) at the PCR gate, of which %d were the NEW neutral"
                        + " no-trade band and %d were the pre-existing direction rules.%n",
                pcrDenials,
                a.deniedByPcrKind.getOrDefault(PCR_NEUTRAL_BAND, 0),
                a.deniedByPcrKind.getOrDefault(PCR_DIRECTION, 0)
                        + a.deniedByPcrKind.getOrDefault(PCR_NO_DATA, 0));
        System.out.printf(
                Locale.US, "  Option S&R denied %d symbol(s) under the new gate.%n", srDenials);
        System.out.printf(
                Locale.US,
                "  Delta A - B (cost of the PCR gate): %+,.2f ₹ and %+,.2f R over %d trade(s).%n",
                a.netPnl - b.netPnl,
                a.netR - b.netR,
                a.trades.size());
        System.out.printf(
                Locale.US,
                "  Delta A - C (cost of both option gates): %+,.2f ₹ and %+,.2f R.%n",
                a.netPnl - c.netPnl,
                a.netR - c.netR);
        System.out.println();
        System.out.println(
                "  CAVEAT: the option chain is sampled live AFTER the close, so today's");
        System.out.println(
                "  closing PCR / max-OI strikes are applied to every intraday decision.");
    }

    /**
     * Prints one summary row of the comparison table.
     *
     * @param result the pass to render
     */
    private static void printComparisonRow(PassResult result) {
        System.out.printf(
                Locale.US,
                "  %-46s %7d %7d %7d %14.2f %8.2f%n",
                result.label,
                result.trades.size(),
                result.wins,
                result.losses,
                result.netPnl,
                result.netR);
    }

    /**
     * Renders a bounded, comma separated preview of a symbol set.
     *
     * @param symbols symbols to show
     * @return up to 25 symbols followed by an ellipsis when truncated
     */
    private static String preview(Set<String> symbols) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String s : symbols) {
            if (shown >= 25) {
                sb.append(", ...");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(s);
            shown++;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- data helpers

    /**
     * @return the flattened, de-duplicated sector universe used by the production morning scan
     */
    private static List<String> universe() {
        List<String> symbols = new ArrayList<>();
        for (List<String> sector : NiftySectorRegistry.getSectorConstituents().values()) {
            for (String symbol : sector) {
                if (!symbols.contains(symbol)) {
                    symbols.add(symbol);
                }
            }
        }
        return symbols;
    }

    /**
     * Fetches 5-minute candles and resolves which session to replay.
     *
     * <p>The session is taken from the data, not from the wall clock: the run may happen after
     * midnight, on a weekend or on a holiday, when "today" has no candles at all. The session is
     * the one the majority of the universe last traded on — always the latest completed session.
     * {@code lvr.audit.sessionDate} overrides it for a deliberate back-date.
     *
     * @param symbols universe to fetch
     * @return session candles per symbol, only for symbols with at least four candles
     */
    private Map<String, List<Candle>> fetchSessionCandles(List<String> symbols) {
        Map<String, List<Candle>> rawBySymbol = new LinkedHashMap<>();
        Map<LocalDate, Integer> votes = new TreeMap<>();
        int failures = 0;
        for (String symbol : symbols) {
            try {
                List<Candle> raw = marketDataService.fetch5MinCandles(symbol, 3);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }
                rawBySymbol.put(symbol, raw);
                votes.merge(
                        LocalDate.ofInstant(raw.get(raw.size() - 1).timestamp(), IST),
                        1,
                        Integer::sum);
            } catch (Exception e) {
                failures++;
            }
        }
        if (failures > 0) {
            System.out.printf(
                    Locale.US, "%-28s: %d symbols failed to fetch%n", "Fetch failures", failures);
        }

        LocalDate sessionDate = resolveSessionDate(votes);
        this.resolvedSessionDate = sessionDate;

        Map<String, List<Candle>> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candle>> entry : rawBySymbol.entrySet()) {
            List<Candle> session =
                    entry.getValue().stream()
                            .filter(
                                    c ->
                                            sessionDate.equals(
                                                    LocalDate.ofInstant(c.timestamp(), IST)))
                            .toList();
            if (session.size() >= 4) {
                map.put(entry.getKey(), session);
            }
        }
        return map;
    }

    /**
     * Picks the session to replay from the per-symbol "latest date" votes.
     *
     * @param votes how many symbols last traded on each date
     * @return the most commonly voted date, breaking ties towards the newest, or today when there
     *     is no data at all
     */
    private static LocalDate resolveSessionDate(Map<LocalDate, Integer> votes) {
        if (SESSION_OVERRIDE != null && !SESSION_OVERRIDE.isBlank()) {
            return LocalDate.parse(SESSION_OVERRIDE);
        }
        return votes.entrySet().stream()
                .sorted(
                        (a, b) ->
                                a.getValue().equals(b.getValue())
                                        ? b.getKey().compareTo(a.getKey())
                                        : b.getValue() - a.getValue())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(LocalDate.now(IST));
    }

    // ---------------------------------------------------------------- value types

    /** One completed trade, flattened for printing. */
    private static final class TradeRow {
        private final String symbol;
        private final String direction;
        private final String entryTime;
        private final double entry;
        private final double sl;
        private final double target;
        private final String exitReason;
        private final double pnl;
        private final double r;

        /**
         * Flattens a completed paper position.
         *
         * @param pos the position returned by {@code replaySession}
         */
        private TradeRow(LowestVolumePaperPosition pos) {
            this.symbol = pos.getSymbol();
            this.direction = String.valueOf(pos.getDirection());
            this.entryTime =
                    pos.getEntryTime() == null
                            ? "-"
                            : TIME_FMT.format(pos.getEntryTime().atZone(IST));
            this.entry =
                    pos.getStockEntryPrice() != null ? pos.getStockEntryPrice().doubleValue() : 0.0;
            this.sl = pos.getInitialStockSl() != null ? pos.getInitialStockSl().doubleValue() : 0.0;
            this.target =
                    pos.getTarget1StockPrice() != null
                            ? pos.getTarget1StockPrice().doubleValue()
                            : 0.0;
            this.exitReason = pos.getExitReason() == null ? "-" : pos.getExitReason();
            this.pnl =
                    pos.getTotalRealizedPnl() != null
                            ? pos.getTotalRealizedPnl().doubleValue()
                            : 0.0;
            double plannedRisk =
                    pos.getPlannedRisk() != null ? pos.getPlannedRisk().doubleValue() : 0.0;
            this.r = plannedRisk > 0.0 ? pnl / plannedRisk : 0.0;
        }
    }

    /** Everything one gate configuration produced. */
    private static final class PassResult {
        private final String label;
        private final boolean pcrEnabled;
        private final boolean optionSrEnabled;
        private final List<TradeRow> trades = new ArrayList<>();
        private final Map<String, Integer> deniedByGate = new TreeMap<>();
        private final Map<String, Integer> deniedByPcrKind = new TreeMap<>();
        private final Set<String> deniedSymbols = new TreeSet<>();
        private final Set<String> neutralBandBlocked = new TreeSet<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private int symbolsReplayed;
        private double netPnl;
        private double netR;
        private int wins;
        private int losses;

        /**
         * @param label printed pass label
         * @param pcrEnabled whether the PCR gate was on for this pass
         * @param optionSrEnabled whether the S&amp;R gate was on for this pass
         */
        private PassResult(String label, boolean pcrEnabled, boolean optionSrEnabled) {
            this.label = label;
            this.pcrEnabled = pcrEnabled;
            this.optionSrEnabled = optionSrEnabled;
        }

        /**
         * Folds one completed trade into the totals.
         *
         * @param pos the completed position
         */
        private synchronized void add(LowestVolumePaperPosition pos) {
            TradeRow row = new TradeRow(pos);
            trades.add(row);
            netPnl += row.pnl;
            netR += row.r;
            if (row.pnl > 0) {
                wins++;
            } else if (row.pnl < 0) {
                losses++;
            }
        }

        /** Increments the count of symbols that entered the replay. */
        private synchronized void markSymbolReplayed() {
            symbolsReplayed++;
        }

        /**
         * Records a replay failure for one symbol/direction pair.
         *
         * @param message the failure description
         */
        private synchronized void addError(String message) {
            errors.add(message);
        }

        /**
         * @return how many symbols either hit a gate or produced a trade
         */
        private int reachedGate() {
            Set<String> union = new TreeSet<>(deniedSymbols);
            trades.forEach(t -> union.add(t.symbol));
            return union.size();
        }
    }

    /**
     * Captures {@code [LVR][REPLAY]} denial lines plus every WARN/ERROR, without letting them flood
     * the test's console XML output. The warnings are surfaced again afterwards so option chain
     * problems (synthetic chain, contract missing) stay visible.
     */
    private static final class Recorder extends AppenderBase<ILoggingEvent> {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            String message = event.getFormattedMessage();
            if (message == null) {
                return;
            }
            if (message.contains("[LVR][REPLAY]")) {
                messages.add(message);
            }
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                warnings.add(event.getLevel() + " " + message);
            }
        }
    }
}

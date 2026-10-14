package com.tradingbot.strategy.silverorb.service;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.silverorb.config.SilverOrbProperties;
import com.tradingbot.strategy.silverorb.engine.SilverOrbEngine;
import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbEvent;
import com.tradingbot.strategy.silverorb.model.SilverOrbEventType;
import com.tradingbot.strategy.silverorb.model.SilverOrbState;
import com.tradingbot.strategy.silverorb.model.SilverOrbTrade;
import com.tradingbot.strategy.silverorb.persistence.SilverOrbStateStore;
import com.tradingbot.telegram.TelegramService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one 5-minute Silver ORB cycle: fetch today's closed MCX silver 5m bars from Shoonya,
 * feed the pure {@link SilverOrbEngine}, persist state, and dispatch telegram alerts (entry, exit,
 * end-of-day P&L digest, month-end summary). PAPER only — this service never places orders.
 */
@Service
public class SilverOrbService {

    private static final Logger log = LoggerFactory.getLogger(SilverOrbService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final ShoonyaMarketDataService marketDataService;
    private final TelegramService telegramService;
    private final SilverOrbProperties props;
    private final SilverOrbEngine engine;

    private Clock clock = Clock.system(IST);

    /** Wires the service with its broker feed, telegram notifier and strategy configuration. */
    @Autowired
    public SilverOrbService(
            ShoonyaMarketDataService marketDataService,
            TelegramService telegramService,
            SilverOrbProperties props) {
        this.marketDataService = marketDataService;
        this.telegramService = telegramService;
        this.props = props;
        this.engine = new SilverOrbEngine(props);
    }

    /** Overrides the clock (unit tests only; mirrors the LVR service test hook). */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Runs one evaluation cycle over today's data: engine pass, alerts, persistence. */
    public void runCycle() {
        if (!props.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (today.isAfter(props.getRunUntil())) {
            sendFinalSummaryOnce();
            return;
        }

        long startEpoch = today.atStartOfDay(IST).toEpochSecond();
        long endEpoch = now.atZone(IST).toEpochSecond();
        List<Candle> raw =
                marketDataService.fetchHistoricalCandles(
                        props.getExch(),
                        props.getToken(),
                        props.getSymbol(),
                        props.getTimeframe(),
                        startEpoch,
                        endEpoch);
        List<Candle> dayBars = filterClosedDayBars(raw, today, now);
        if (dayBars.isEmpty()) {
            log.info(
                    "[SILVER-ORB] No closed {}m bars for {} yet — skipping cycle.",
                    props.barMinutes(),
                    today);
            return;
        }

        SilverOrbState state = SilverOrbStateStore.load(Path.of(props.getStateFile()));
        if (state == null) {
            state = new SilverOrbState();
        }
        if (state.currentDay == null || !today.equals(state.currentDay.date)) {
            carryOverUnsettledTrade(state);
            state.currentDay = newDayState(today);
        }
        SilverOrbDayState day = state.currentDay;

        for (SilverOrbEvent event : engine.processBars(dayBars, day)) {
            if (event.type() == SilverOrbEventType.ENTRY) {
                alertEntry(day);
            } else {
                state.trades.add(event.trade());
                alertExit(event.trade(), state);
            }
        }

        // Data-outage safety: past the square-off grace period with an open trade, hard settle.
        if (day.trade != null
                && day.trade.isOpen()
                && !now.toLocalTime().isBefore(props.getSquareOff().plusMinutes(15))) {
            Candle last = dayBars.get(dayBars.size() - 1);
            SilverOrbTrade forced = engine.forceSettle(day, last.close().doubleValue(), now);
            if (forced != null) {
                state.trades.add(forced);
                alertExit(forced, state);
            }
        }

        // End-of-day P&L digest, once per day, after the square-off bar has been processed.
        if (!now.toLocalTime().isBefore(props.getSquareOff().plusMinutes(5))
                && day.trade != null
                && !day.trade.isOpen()
                && !day.eodSummarySent) {
            alertDayDigest(day, state);
            day.eodSummarySent = true;
        }

        saveState(state);
    }

    /**
     * Filters the broker response to today's fully closed bars (a 5m bar opened at T is only
     * tradable once T+5min has passed), sorted ascending.
     */
    private List<Candle> filterClosedDayBars(List<Candle> raw, LocalDate today, LocalDateTime now) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Instant closeCutoff = now.minusMinutes(props.barMinutes()).atZone(IST).toInstant();
        return raw.stream()
                .filter(b -> b.timestamp() != null)
                .filter(b -> !b.timestamp().isAfter(closeCutoff))
                .filter(
                        b ->
                                LocalDateTime.ofInstant(b.timestamp(), IST)
                                        .toLocalDate()
                                        .equals(today))
                .sorted(Comparator.comparing(Candle::timestamp))
                .toList();
    }

    /** Creates a fresh day state (cumulative trade log intentionally untouched). */
    private SilverOrbDayState newDayState(LocalDate today) {
        SilverOrbDayState day = new SilverOrbDayState();
        day.date = today;
        return day;
    }

    /**
     * Closes out a trade left open across midnight (bot was down over the square-off window and
     * yesterday's candles are no longer fetchable): books it flat at entry with reason
     * UNSETTLED_EOD so the cumulative ledger and telegram stay honest instead of silently dropping
     * the position.
     */
    private void carryOverUnsettledTrade(SilverOrbState state) {
        SilverOrbDayState prev = state.currentDay;
        if (prev == null || prev.trade == null || !prev.trade.isOpen()) {
            return;
        }
        prev.trade.settle(
                prev.trade.entryTime,
                prev.trade.entryPrice,
                "UNSETTLED_EOD",
                props.getRiskPct(),
                props.getMarginPct(),
                0.0);
        state.trades.add(prev.trade);
        alertExit(prev.trade, state);
    }

    /** Persists state; a failure is logged but must never kill the trading loop. */
    private void saveState(SilverOrbState state) {
        state.savedAt = Instant.now(clock);
        try {
            SilverOrbStateStore.save(Path.of(props.getStateFile()), state);
        } catch (IOException e) {
            log.error(
                    "[SILVER-ORB] Failed to persist state to {}: {}",
                    props.getStateFile(),
                    e.getMessage(),
                    e);
        }
    }

    // ---------------------------------------------------------------------------- telegram

    /** Entry alert: side, entry, ORB, stop/target and rupee risk/target at the configured size. */
    private void alertEntry(SilverOrbDayState day) {
        if (!alertsOn()) {
            return;
        }
        SilverOrbTrade t = day.trade;
        double marginRs =
                t.entryPrice * props.getKgPerLot() * props.getLots() * props.getMarginPct() / 100.0;
        String message =
                String.format(
                        "\uD83E\uDDE9 *[SILVER-ORB] PAPER ENTRY* \uD83E\uDDEA\n"
                                + "\uD83D\uDCC8 *Side:* `%s` (ORB bias %s IST)\n"
                                + "\uD83C\uDFAF *Entry:* `%s` @ %s IST\n"
                                + "\uD83D\uDCCF *ORB:* %s – %s\n"
                                + "\uD83D\uDE91 *Stop:* `%s` (%.0f%% of margin = %.2f%% of price)\n"
                                + "\uD83C\uDFC6 *Target:* `%s` (+%.0f%% of margin = %.2f%% of price)\n"
                                + "\uD83D\uDCBC *%s %s* · %d lot(s) (%.0f kg) · margin ~₹%,.0f · "
                                + "risk ₹%,.0f · target ₹%,.0f",
                        t.side,
                        day.biasTime != null ? TIME_FMT.format(day.biasTime) : "-",
                        fmt2(t.entryPrice),
                        TIME_FMT.format(t.entryTime),
                        fmt2(day.orbHigh),
                        fmt2(day.orbLow),
                        fmt2(t.stopPrice),
                        props.getRiskPct(),
                        props.getRiskPct() * props.getMarginPct() / 100.0,
                        fmt2(t.targetPrice),
                        props.getTargetPct(),
                        props.getTargetPct() * props.getMarginPct() / 100.0,
                        props.getExch(),
                        props.getSymbol(),
                        props.getLots(),
                        props.getKgPerLot(),
                        marginRs,
                        marginRs * props.getRiskPct() / 100.0,
                        marginRs * props.getTargetPct() / 100.0);
        send(message);
    }

    /** Exit alert: fill, exit reason, rupee P&L, R-multiple and running day/month totals. */
    private void alertExit(SilverOrbTrade t, SilverOrbState state) {
        if (!alertsOn()) {
            return;
        }
        String message =
                String.format(
                        "\uD83C\uDFC1 *[SILVER-ORB] PAPER EXIT* \uD83E\uDDEA\n"
                                + "\uD83D\uDCC9 *%s* closed — *%s*\n"
                                + "\uD83C\uDFAF %s (%s) → %s (%s)\n"
                                + "\uD83D\uDCCA *Result:* %+.2f pts · %+.2f%% price · "
                                + "%+.2f%% margin · *%+.2fR*\n"
                                + "\uD83D\uDCB0 *Trade P&L:* ₹%,.0f\n"
                                + "\uD83D\uDCC5 *Day P&L:* ₹%,.0f · *Month:* ₹%,.0f "
                                + "(%+.2fR · %d trades · WR %.1f%%)",
                        t.side,
                        t.exitReason,
                        fmt2(t.entryPrice),
                        TIME_FMT.format(t.entryTime),
                        fmt2(t.exitPrice),
                        TIME_FMT.format(t.exitTime),
                        t.points,
                        t.priceRetPct,
                        t.marginRetPct,
                        t.rMultiple,
                        t.pnlRs,
                        t.pnlRs,
                        state.totalPnlRs(),
                        state.totalR(),
                        state.tradeCount(),
                        state.winRatePct());
        send(message);
    }

    /** End-of-day digest: the day's net plus the running month ledger (sent once per day). */
    private void alertDayDigest(SilverOrbDayState day, SilverOrbState state) {
        if (!alertsOn()) {
            return;
        }
        String message =
                String.format(
                        "\uD83D\uDCCA *[SILVER-ORB] DAY P&L* \uD83D\uDCCA\n"
                                + "\uD83D\uDCC5 %s · 1 trade · net ₹%,.0f (%+.2fR)\n"
                                + "\uD83D\uDCBC *Month (%s → %s):* ₹%,.0f · %d trades · "
                                + "WR %.1f%% · %+.2fR",
                        DAY_FMT.format(day.date),
                        day.trade.pnlRs,
                        day.trade.rMultiple,
                        DAY_FMT.format(props.getRunUntil().minusDays(30)),
                        DAY_FMT.format(props.getRunUntil()),
                        state.totalPnlRs(),
                        state.tradeCount(),
                        state.winRatePct(),
                        state.totalR());
        send(message);
    }

    /** One-time month-end summary once the configured runUntil day has passed. */
    private void sendFinalSummaryOnce() {
        SilverOrbState state = SilverOrbStateStore.load(Path.of(props.getStateFile()));
        if (state == null || state.finalSummarySent) {
            return;
        }
        state.finalSummarySent = true;
        saveState(state);
        if (alertsOn()) {
            send(
                    String.format(
                            "✅ *[SILVER-ORB] PAPER RUN COMPLETE*\n"
                                    + "\uD83D\uDCBC *Total:* ₹%,.0f · %d trades · WR %.1f%% · "
                                    + "%+.2fR\n"
                                    + "Validation window ended %s — compare against the spec's "
                                    + "acceptance criteria before any capital.",
                            state.totalPnlRs(),
                            state.tradeCount(),
                            state.winRatePct(),
                            state.totalR(),
                            DAY_FMT.format(props.getRunUntil())));
        }
    }

    private boolean alertsOn() {
        return props.isTelegramAlertsEnabled();
    }

    private void send(String message) {
        try {
            telegramService.sendAlert(message);
        } catch (Exception e) {
            log.error("[SILVER-ORB] Telegram alert failed: {}", e.getMessage(), e);
        }
    }

    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }
}

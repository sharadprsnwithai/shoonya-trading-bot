package com.tradingbot.service;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LvrInstrumentType;
import com.tradingbot.util.BotLocale;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L6: the shared pre-entry gate -- the single pre-entry decision used by BOTH the live trigger path
 * and {@code LowestVolumeReversalService.replaySession}, so a gate can never pass live and fail
 * replay (or vice versa) for identical inputs.
 *
 * <p>Pure and stateless: inputs arrive via {@link EntryGateInput}, the outcome returns as an {@link
 * EntryGateDecision}. The live index quote feeding the INDEX_ALIGNMENT gate is collected by the
 * service (fillIndexAlignment); only the decision lives here.
 */
public final class LvrEntryGate {

    private static final Logger log = LoggerFactory.getLogger(LvrEntryGate.class);

    // ===== L6: shared pre-entry gate — used by BOTH the live trigger path and replaySession =====

    /** What the caller should do when the gate denies an entry. */
    public enum EntryGateDisposition {
        /** All evaluated gates passed. */
        ALLOWED,
        /** Transient/unavailable data — retry on the next natural tick (no cooldown). */
        RETRY,
        /** Price-data-dependent rejection — retry after the standard 60s cooldown. */
        RETRY_COOLDOWN,
        /** Permanent rejection — discard the symbol for the day (REJECTED_EXHAUSTED). */
        EXHAUST
    }

    /** Decision produced by {@link #evaluateEntryGate}. */
    public record EntryGateDecision(EntryGateDisposition disposition, String gate, String reason) {
        public boolean allowed() {
            return disposition == EntryGateDisposition.ALLOWED;
        }
    }

    /**
     * L6: inputs for the shared entry gate. Fields left {@code null} (or {@code maxSlippagePct <
     * 0}) are NOT evaluated — the live trigger path leaves session-level gates to the outer loop of
     * {@code evaluateLivePriceActions} and to {@link
     * LowestVolumeReversalService#executePositionEntry}, while {@link
     * LowestVolumeReversalService#replaySession} fills everything.
     */
    public static final class EntryGateInput {
        public String symbol;
        public LowestVolumeDirection direction;

        /** Armed trigger price — the reference for the slippage band. */
        public BigDecimal triggerPrice;

        /** Market price the decision gates (VWAP / 15m / PDH) are evaluated against. */
        public BigDecimal decisionPrice;

        /** Price used for the slippage band (live: LTP at touch; replay: entry candle open). */
        public BigDecimal entryPrice;

        /** {@code < 0} disables the slippage gate. */
        public double maxSlippagePct = -1;

        public Boolean vwapEnabled;

        /** {@code null} or {@code <= 0} = unavailable (fail-closed RETRY when enabled). */
        public Double vwap;

        public Boolean range15mEnabled;
        public BigDecimal first15mHigh;
        public BigDecimal first15mLow;
        public Boolean pdhPdlEnabled;
        public BigDecimal pdh;
        public BigDecimal pdl;

        /** Max % entry is allowed to be above PDH (LONG) or below PDL (SHORT). <=0 = disabled. */
        public double maxPdhExtensionPct = 0;

        public Boolean pcrEnabled;
        public Double pcr;
        public Double pcrNeutralMin;
        public Double pcrNeutralMax;
        public Boolean optionSrFilterEnabled;
        public BigDecimal optionResistanceStrike;
        public BigDecimal optionSupportStrike;
        public Double optionSrBufferPct;
        public Boolean standDown;
        public Boolean breakerTripped;
        public LocalTime nowTime;
        public LocalTime entryCutoff;
        public Integer tradeAttempts;
        public Integer maxAttempts;
        public Integer openConcurrent;
        public Integer maxConcurrent;
        public Boolean hasRegistryEntry;
        public BigDecimal plannedRisk;
        public Double remainingBudget;

        // ---- F&O ban-period hard block (gate BAN_PERIOD; NCL/CMPT/70085) ----

        /** Master switch; {@code false} (kill switch or log-only validation) skips the gate. */
        public Boolean banPeriodEnabled;

        /** Symbol is in today's NSE F&O ban list. Consulted only when the gate is enabled. */
        public Boolean symbolBanned;

        /** Today's ban list could not be fetched or was unrecognisable. */
        public Boolean banListUnavailable;

        /** Policy for an unavailable list: {@code true} defers entries, {@code false} trades. */
        public Boolean banPeriodFailClosed;

        // ---- Expiry / DTE session gate (gate EXPIRY_DTE) ----

        /** Master switch; {@code false} or a floor of {@code <= 0} skips the gate. */
        public Boolean expiryDteEnabled;

        /** Instrument being entered — selects the OPTIONS vs FUTURES DTE floor. */
        public LvrInstrumentType instrumentType;

        /** Trading sessions left in the current monthly series ({@code 0} on expiry day). */
        public Integer optionDteSessions;

        /** True when today is the monthly expiry session of the current series. */
        public Boolean expiryDayToday;

        /** Fresh-entry floor for OPTIONS, in trading sessions. */
        public Integer optionMinDteSessions;

        /** Fresh-entry floor for FUTURES, in trading sessions ({@code 0} = legacy behaviour). */
        public Integer futuresMinDteSessions;

        // ---- Index-alignment regime gate (gate INDEX_ALIGNMENT) ----

        /**
         * Master switch. Consulted only alongside a live index read: a {@code null}/{@code false}
         * switch, or a missing index level, skips the gate (fail-open).
         */
        public Boolean indexAlignmentEnabled;

        /** Live index level at trigger time. {@code null} = unavailable → fail-open. */
        public Double indexSpot;

        /** Exchange previous close of the index. {@code null} or {@code <= 0} → fail-open. */
        public Double indexPrevClose;

        /** Dead-band in basis points the index must clear the previous close by. Clamped at 0. */
        public double indexAlignmentBufferBps;
    }

    private static EntryGateDecision deny(
            EntryGateDisposition disposition, String gate, String reason) {
        return new EntryGateDecision(disposition, gate, reason);
    }

    /**
     * L6: THE single pre-entry decision. Pure and stateless — the live trigger path and {@link
     * LowestVolumeReversalService#replaySession} both evaluate this exact function, so a gate can
     * never pass live and fail replay (or vice versa) for identical inputs. Evaluation order
     * mirrors the live chain: ban-period → slippage → index-alignment → VWAP → 15m range → PDH/PDL
     * → stand-down → breaker → cutoff → expiry/DTE → attempts → concurrency → registry → budget.
     *
     * <p>Note on index-alignment: the live path collects the index level at the moment the trigger
     * is touched. A replay harness has no live index quote, so it leaves those fields {@code null}
     * and the gate is skipped there — the same mechanism ({@code null} = not evaluated) every other
     * optional input already uses, and never a divergence in the other direction.
     */
    public static EntryGateDecision evaluateEntryGate(EntryGateInput in) {
        // 0. F&O ban-period hard block (NCL/CMPT/70085; SEBI/HO/MRD/TPD-1/P/CIR/2025/79).
        //
        // Evaluated FIRST, ahead of the price chain: ban status is an absolute regulatory block,
        // independent of price, so a symbol that can never be traded today must not burn a 60s
        // slippage cooldown before being rejected. During ban, every trade must reduce the
        // entity's end-of-day Futures-equivalent OI — LVR's long option/futures entries always
        // increase it, so there is no compliant way to enter.
        if (Boolean.TRUE.equals(in.banPeriodEnabled)) {
            if (Boolean.TRUE.equals(in.symbolBanned)) {
                return deny(
                        EntryGateDisposition.EXHAUST,
                        "BAN_PERIOD",
                        in.symbol
                                + " is in the NSE F&O ban period — only risk-reducing trades are"
                                + " permitted, so no fresh LVR entry (discarded for the day)");
            }
            if (Boolean.TRUE.equals(in.banListUnavailable)) {
                if (Boolean.TRUE.equals(in.banPeriodFailClosed)) {
                    // Transient by nature: RETRY, never EXHAUST. A failed fetch must not
                    // permanently discard symbols for the day, and runNseBanListFetch already
                    // emitted the WARN plus the degraded-fetch Telegram alert.
                    return deny(
                            EntryGateDisposition.RETRY,
                            "BAN_PERIOD",
                            "F&O ban list unavailable and ban-period-fail-closed=true — deferring"
                                    + " fresh entries until the list can be fetched");
                }
                // Fail-open (default): a network blip must not shut the strategy down. The
                // degraded-fetch alert was already raised once at fetch time, not per tick.
                log.warn(
                        "[LVR] [NSE-BAN] Ban list unavailable — proceeding unfiltered"
                                + " (ban-period-fail-closed=false).");
            }
        }

        // 1. Slippage band (price-data dependent → cooldown retry).
        if (in.maxSlippagePct > 0
                && in.triggerPrice != null
                && in.entryPrice != null
                && in.direction != null) {
            boolean excessive;
            String band;
            if (in.direction == LowestVolumeDirection.LONG) {
                BigDecimal maxAllowed =
                        in.triggerPrice.multiply(
                                BigDecimal.valueOf(1.0 + (in.maxSlippagePct / 100.0)));
                excessive = in.entryPrice.compareTo(maxAllowed) > 0;
                band = "max " + String.format(BotLocale.INSTANCE, "%.2f", maxAllowed);
            } else {
                BigDecimal minAllowed =
                        in.triggerPrice.multiply(
                                BigDecimal.valueOf(1.0 - (in.maxSlippagePct / 100.0)));
                excessive = in.entryPrice.compareTo(minAllowed) < 0;
                band = "min " + String.format(BotLocale.INSTANCE, "%.2f", minAllowed);
            }
            if (excessive) {
                return deny(
                        EntryGateDisposition.RETRY_COOLDOWN,
                        "SLIPPAGE",
                        String.format(
                                BotLocale.INSTANCE,
                                "excessive slippage: entry price %s outside %s%% band from"
                                        + " trigger %s (%s)",
                                in.entryPrice,
                                String.format(BotLocale.INSTANCE, "%.2f", in.maxSlippagePct),
                                in.triggerPrice,
                                band));
            }
        }

        // 1b. Index-alignment regime gate.
        //
        // A LONG requires the index to be above its previous close by the dead-band, a SHORT below
        // it by the same. This closes the gap where a watchlist was fixed on a morning read but the
        // index has since reversed: every stock-level confirmation below (VWAP, 15m, PDH) can be
        // satisfied while the broader market has already turned against the trade — which is
        // exactly the "NIFTY reverses after 10am and the opportunity is missed" failure.
        //
        // The reference is the exchange PREVIOUS CLOSE rather than an intraday anchor (a 09:25
        // print, VWAP): it is stationary, computed once, never ambiguous, and is the conventional
        // risk-on/risk-off read. The buffer is a dead-band — without it a sub-0.05% wobble around
        // the reference would flip-flop and randomly veto valid breakouts.
        //
        // Fail-OPEN, deliberately: a missing index quote must never block trading, mirroring the
        // ban-list policy (a data outage stops nothing). The denial is RETRY_COOLDOWN, not EXHAUST,
        // for the same reason as every price-relative check below — the index crosses its previous
        // close routinely, so "wrong side at this tick" is not grounds to discard the symbol.
        if (Boolean.TRUE.equals(in.indexAlignmentEnabled)
                && in.direction != null
                && in.indexSpot != null
                && in.indexSpot > 0.0
                && in.indexPrevClose != null
                && in.indexPrevClose > 0.0) {
            double bufferBps = Math.max(0.0, in.indexAlignmentBufferBps);
            double moveBps = ((in.indexSpot - in.indexPrevClose) / in.indexPrevClose) * 10_000.0;
            boolean aligned =
                    in.direction == LowestVolumeDirection.LONG
                            ? moveBps >= bufferBps
                            : moveBps <= -bufferBps;
            if (!aligned) {
                return deny(
                        EntryGateDisposition.RETRY_COOLDOWN,
                        "INDEX_ALIGNMENT",
                        String.format(
                                BotLocale.INSTANCE,
                                "index at %.2f is %+.2f bps vs previous close %.2f — not aligned"
                                        + " with %s (buffer %.2f bps, re-checking after cooldown)",
                                in.indexSpot,
                                moveBps,
                                in.indexPrevClose,
                                in.direction,
                                bufferBps));
            }
        }

        // 2. VWAP confirmation (unavailable → fail-closed retry; wrong side → cooldown retry).
        //
        // Every price-relative check below deliberately denies with RETRY_COOLDOWN rather than
        // EXHAUST. These conditions are functions of the live quote, and the gate is rebuilt from
        // a fresh quote on every 30-second tick, so "wrong side of VWAP at this instant" is no
        // more permanent than "VWAP unavailable". Returning EXHAUST here would discard the symbol
        // for the rest of the day because it happened to be a few paise the wrong side of a
        // moving average on the single tick its trigger fired — the armed setup, its correctly
        // computed trigger, SL and target, and both of its attempts would all be thrown away.
        // EXHAUST is reserved for conditions that genuinely cannot recover: a missing registry
        // entry, and PDH/PDL that is still unavailable after the bounded N5 retries above.
        if (Boolean.TRUE.equals(in.vwapEnabled)) {
            if (in.vwap == null || in.vwap <= 0.0) {
                return deny(
                        EntryGateDisposition.RETRY,
                        "VWAP",
                        "VWAP confirmation enabled but no valid VWAP available (fail-closed)");
            }
            if (in.decisionPrice != null && in.direction != null) {
                boolean confirmed;
                if (in.direction == LowestVolumeDirection.LONG) {
                    confirmed = in.decisionPrice.compareTo(BigDecimal.valueOf(in.vwap)) > 0;
                } else {
                    confirmed = in.decisionPrice.compareTo(BigDecimal.valueOf(in.vwap)) < 0;
                }
                if (!confirmed) {
                    return deny(
                            EntryGateDisposition.RETRY_COOLDOWN,
                            "VWAP",
                            String.format(
                                    BotLocale.INSTANCE,
                                    "Spot %s failed VWAP %s confirmation for %s direction"
                                            + " (re-checking after cooldown)",
                                    in.decisionPrice,
                                    String.format(BotLocale.INSTANCE, "%.2f", in.vwap),
                                    in.direction));
                }
            }
        }

        // 3. Opening 15-minute range breakout (unavailable → fail-closed retry; inside range →
        // cooldown retry).
        if (Boolean.TRUE.equals(in.range15mEnabled)) {
            if (in.first15mHigh == null || in.first15mLow == null) {
                return deny(
                        EntryGateDisposition.RETRY,
                        "15M_RANGE",
                        "Opening 15-minute range unavailable (fail-closed)");
            }
            if (in.decisionPrice != null && in.direction != null) {
                boolean confirmed;
                if (in.direction == LowestVolumeDirection.LONG) {
                    confirmed = in.decisionPrice.compareTo(in.first15mHigh) > 0;
                } else {
                    confirmed = in.decisionPrice.compareTo(in.first15mLow) < 0;
                }
                if (!confirmed) {
                    return deny(
                            EntryGateDisposition.RETRY_COOLDOWN,
                            "15M_RANGE",
                            String.format(
                                    BotLocale.INSTANCE,
                                    "Spot %s inside 15-min range [%s - %s] for %s"
                                            + " (re-checking after cooldown)",
                                    in.decisionPrice,
                                    in.first15mLow,
                                    in.first15mHigh,
                                    in.direction));
                }
            }
        }

        // 4. PDH/PDL breakout (fail-closed when values are missing).
        //
        // Unlike the other price-relative checks this one stays EXHAUST, because the live path has
        // already burned its bounded N5 lazy-fetch retries (initPdhPdlForSetup / MAX_PDH_FETCH_
        // ATTEMPTS) before this gate ever sees a null. Reaching here means the daily candles are
        // genuinely unavailable, not momentarily missing.
        if (Boolean.TRUE.equals(in.pdhPdlEnabled)) {
            if (in.pdh == null || in.pdl == null) {
                return deny(
                        EntryGateDisposition.EXHAUST,
                        "PDH_PDL",
                        "PDH/PDL unavailable after bounded fetch retries (fail-closed)");
            }
            if (in.decisionPrice != null && in.direction != null) {
                boolean confirmed;
                if (in.direction == LowestVolumeDirection.LONG) {
                    confirmed = in.decisionPrice.compareTo(in.pdh) > 0;
                } else {
                    confirmed = in.decisionPrice.compareTo(in.pdl) < 0;
                }
                if (!confirmed) {
                    return deny(
                            EntryGateDisposition.RETRY_COOLDOWN,
                            "PDH_PDL",
                            String.format(
                                    BotLocale.INSTANCE,
                                    "Spot %s inside PDH-PDL range [%s - %s] for %s"
                                            + " (re-checking after cooldown)",
                                    in.decisionPrice,
                                    in.pdl,
                                    in.pdh,
                                    in.direction));
                }

                // 4b. PDH extension cap: reject entries that are already too far extended
                // beyond PDH/PDL, since the "pullback" that hits the SL is likely a genuine
                // mean-reversion (e.g. +5.9% above PDH as seen with HCLTECH on Oct 13).
                if (in.maxPdhExtensionPct > 0) {
                    if (in.direction == LowestVolumeDirection.LONG) {
                        double extensionPct =
                                in.decisionPrice
                                        .subtract(in.pdh)
                                        .divide(in.pdh, 6, java.math.RoundingMode.HALF_UP)
                                        .multiply(BigDecimal.valueOf(100))
                                        .doubleValue();
                        if (extensionPct > in.maxPdhExtensionPct) {
                            return deny(
                                    EntryGateDisposition.RETRY_COOLDOWN,
                                    "PDH_EXTENSION",
                                    String.format(
                                            BotLocale.INSTANCE,
                                            "Spot %s is %.2f%% above PDH %s (max allowed: %.1f%%) — entry too extended, re-checking after cooldown",
                                            in.decisionPrice,
                                            extensionPct,
                                            in.pdh,
                                            in.maxPdhExtensionPct));
                        }
                    } else {
                        double extensionPct =
                                in.pdl
                                        .subtract(in.decisionPrice)
                                        .divide(in.pdl, 6, java.math.RoundingMode.HALF_UP)
                                        .multiply(BigDecimal.valueOf(100))
                                        .doubleValue();
                        if (extensionPct > in.maxPdhExtensionPct) {
                            return deny(
                                    EntryGateDisposition.RETRY_COOLDOWN,
                                    "PDH_EXTENSION",
                                    String.format(
                                            BotLocale.INSTANCE,
                                            "Spot %s is %.2f%% below PDL %s (max allowed: %.1f%%) — entry too extended, re-checking after cooldown",
                                            in.decisionPrice,
                                            extensionPct,
                                            in.pdl,
                                            in.maxPdhExtensionPct));
                        }
                    }
                }
            }
        }

        // 5. PCR (Put-Call Ratio, ATM ± PCR_STRIKE_WINDOW) Neutral No-Trade Gate.
        //    The band [neutralMin, neutralMax] (default 0.85–1.15) is a NO-TRADE zone that blocks
        //    BOTH directions: LONG needs PCR above the band (bullish), SHORT needs PCR below it
        //    (bearish). Anything inside the band is indecision and never triggers an entry.
        if (Boolean.TRUE.equals(in.pcrEnabled)) {
            if (in.pcr == null || in.pcr <= 0.0) {
                return deny(
                        EntryGateDisposition.RETRY,
                        "PCR",
                        "PCR filter enabled but no valid PCR (ATM ± 4) available (fail-closed)");
            }
            double neutralMin = in.pcrNeutralMin != null ? in.pcrNeutralMin : 0.85;
            double neutralMax = in.pcrNeutralMax != null ? in.pcrNeutralMax : 1.15;

            if (in.pcr >= neutralMin && in.pcr <= neutralMax) {
                return deny(
                        EntryGateDisposition.RETRY_COOLDOWN,
                        "PCR",
                        String.format(
                                BotLocale.INSTANCE,
                                "PCR %.2f (ATM ± 4) is inside the neutral no-trade band "
                                        + "[%.2f, %.2f] — indecision, re-checking after cooldown",
                                in.pcr,
                                neutralMin,
                                neutralMax));
            }
            if (in.direction == LowestVolumeDirection.LONG && in.pcr < neutralMin) {
                return deny(
                        EntryGateDisposition.RETRY_COOLDOWN,
                        "PCR",
                        String.format(
                                BotLocale.INSTANCE,
                                "PCR %.2f (ATM ± 4) is below %.2f — bearish, not confirmed for"
                                        + " LONG (re-checking after cooldown)",
                                in.pcr,
                                neutralMin));
            }
            if (in.direction == LowestVolumeDirection.SHORT && in.pcr > neutralMax) {
                return deny(
                        EntryGateDisposition.RETRY_COOLDOWN,
                        "PCR",
                        String.format(
                                BotLocale.INSTANCE,
                                "PCR %.2f (ATM ± 4) is above %.2f — bullish, not confirmed for"
                                        + " SHORT (re-checking after cooldown)",
                                in.pcr,
                                neutralMax));
            }
        }

        // 6. Option Chain Support & Resistance Filter (Block LONG near/above Resistance, Block
        // SHORT near/below Support)
        if (Boolean.TRUE.equals(in.optionSrFilterEnabled)
                && in.decisionPrice != null
                && in.direction != null) {
            // The level for this direction. It is null when the chain could not be read — no
            // open interest on that side, a synthetic or empty chain, or a fetch that failed
            // closed. The old checks were guarded by `strike != null`, so a missing level
            // silently skipped the whole filter and the entry went ahead with no resistance or
            // support test at all. Fail closed instead.
            BigDecimal level =
                    in.direction == LowestVolumeDirection.SHORT
                            ? in.optionSupportStrike
                            : in.optionResistanceStrike;
            if (level == null) {
                // The chain is re-read on every tick (resolveLiveOptionChainLevels), so an
                // unavailable level recovers like any other missing input — retry, don't discard.
                return deny(
                        EntryGateDisposition.RETRY,
                        "OPTION_SR",
                        String.format(
                                BotLocale.INSTANCE,
                                "Option %s strike unavailable (no usable open interest on that"
                                        + " side) — refusing to enter without it (fail-closed,"
                                        + " retrying)",
                                in.direction == LowestVolumeDirection.SHORT
                                        ? "Support"
                                        : "Resistance"));
            }

            if (in.direction == LowestVolumeDirection.LONG) {
                double buffer = in.optionSrBufferPct != null ? in.optionSrBufferPct : 0.0;
                BigDecimal effectiveResistance =
                        level.multiply(BigDecimal.valueOf(1.0 - (buffer / 100.0)));
                if (in.decisionPrice.compareTo(effectiveResistance) >= 0) {
                    return deny(
                            EntryGateDisposition.RETRY_COOLDOWN,
                            "OPTION_SR",
                            String.format(
                                    BotLocale.INSTANCE,
                                    "Spot %s is at or above Option Resistance (Max Call OI Strike"
                                            + " %s) — re-checking after cooldown",
                                    in.decisionPrice,
                                    level));
                }
            } else {
                double buffer = in.optionSrBufferPct != null ? in.optionSrBufferPct : 0.0;
                BigDecimal effectiveSupport =
                        level.multiply(BigDecimal.valueOf(1.0 + (buffer / 100.0)));
                if (in.decisionPrice.compareTo(effectiveSupport) <= 0) {
                    return deny(
                            EntryGateDisposition.RETRY_COOLDOWN,
                            "OPTION_SR",
                            String.format(
                                    BotLocale.INSTANCE,
                                    "Spot %s is at or below Option Support (Max Put OI Strike %s)"
                                            + " — re-checking after cooldown",
                                    in.decisionPrice,
                                    level));
                }
            }
        }

        // 5. Stand-down (post-cutoff or sentiment collapse).
        if (Boolean.TRUE.equals(in.standDown)) {
            return deny(EntryGateDisposition.RETRY, "STAND_DOWN", "stand-down active for the day");
        }

        // 6. Latched daily circuit breaker.
        if (Boolean.TRUE.equals(in.breakerTripped)) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "BREAKER",
                    "daily max loss circuit breaker is active");
        }

        // 7. Entry cutoff window.
        if (in.nowTime != null && in.entryCutoff != null && !in.nowTime.isBefore(in.entryCutoff)) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "CUTOFF",
                    "entry cutoff " + in.entryCutoff + " reached (now " + in.nowTime + ")");
        }

        // 8. Expiry / DTE for a fresh entry in the current monthly series.
        //
        // Session-static (a series' DTE cannot improve intraday), so a breach is EXHAUST — the
        // same reasoning as BAN_PERIOD and REGISTRY. Measured in trading sessions, so weekends and
        // holiday-rolled expiries need no special case. A floor of 0 (the FUTURES default) keeps
        // the legacy behaviour: this gate is inert for the currently-configured live strategy.
        if (Boolean.TRUE.equals(in.expiryDteEnabled) && in.instrumentType != null) {
            int minDte =
                    in.instrumentType == LvrInstrumentType.FUTURES
                            ? (in.futuresMinDteSessions != null ? in.futuresMinDteSessions : 0)
                            : (in.optionMinDteSessions != null ? in.optionMinDteSessions : 0);
            if (minDte > 0) {
                if (Boolean.TRUE.equals(in.expiryDayToday)) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "EXPIRY_DTE",
                            in.symbol
                                    + " — monthly expiry session; no fresh entry on expiry day"
                                    + " (discarded for the day)");
                }
                if (in.optionDteSessions == null) {
                    // The series could not be resolved for this symbol, so its contract cannot be
                    // traded (the exact failure mode that made an unresolvable option series skip
                    // entries silently). Permanent for the day → EXHAUST, like REGISTRY, and loud.
                    log.warn(
                            "[LVR] Series DTE unavailable for {} with the expiry/DTE gate active —"
                                    + " failing closed (discarded for the day).",
                            in.symbol);
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "EXPIRY_DTE",
                            "current series DTE unknown for " + in.symbol + " (fail-closed)");
                }
                if (in.optionDteSessions < minDte) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "EXPIRY_DTE",
                            "only "
                                    + in.optionDteSessions
                                    + " trading session(s) left in the current monthly series"
                                    + " (minimum "
                                    + minDte
                                    + ") — discarded for the day");
                }
            }
        }

        // 9. Per-symbol attempt budget.
        if (in.tradeAttempts != null
                && in.maxAttempts != null
                && in.tradeAttempts >= in.maxAttempts) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "ATTEMPTS",
                    "max " + in.maxAttempts + " attempt(s) already used");
        }

        // 10. Max concurrent trades.
        if (in.openConcurrent != null
                && in.maxConcurrent != null
                && in.openConcurrent >= in.maxConcurrent) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "CONCURRENCY",
                    "max concurrent trades (" + in.maxConcurrent + ") reached");
        }

        // 11. F&O registry (static for the day — a miss is permanent → exhaust).
        if (Boolean.FALSE.equals(in.hasRegistryEntry)) {
            return deny(
                    EntryGateDisposition.EXHAUST,
                    "REGISTRY",
                    "F&O registry miss for " + in.symbol + " — lot size/strike step unknown");
        }

        // 12. M2 pre-trade daily budget.
        if (in.plannedRisk != null
                && in.remainingBudget != null
                && in.plannedRisk.doubleValue() > in.remainingBudget) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "BUDGET",
                    String.format(
                            BotLocale.INSTANCE,
                            "planned risk %s exceeds remaining daily loss budget %s",
                            String.format(BotLocale.INSTANCE, "%.2f", in.plannedRisk.doubleValue()),
                            String.format(
                                    BotLocale.INSTANCE,
                                    "%.2f",
                                    Math.max(0.0, in.remainingBudget))));
        }

        return new EntryGateDecision(EntryGateDisposition.ALLOWED, "OK", "all gates passed");
    }
}

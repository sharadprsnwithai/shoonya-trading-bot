package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LvrInstrumentType;
import com.tradingbot.service.LvrEntryGate.EntryGateDecision;
import com.tradingbot.service.LvrEntryGate.EntryGateDisposition;
import com.tradingbot.service.LvrEntryGate.EntryGateInput;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L6: the shared entry gate is the ONLY pre-entry decision used by both the live trigger path and
 * {@code replaySession}. These tests pin every gate's outcome and assert live/replay inputs with
 * identical values decide identically.
 */
class LvrEntryGateTest {

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private EntryGateInput baseInput() {
        EntryGateInput in = new EntryGateInput();
        in.symbol = "SUNPHARMA";
        in.direction = LowestVolumeDirection.LONG;
        in.triggerPrice = bd(1000);
        in.decisionPrice = bd(1001);
        in.entryPrice = bd(1001);
        in.maxSlippagePct = 0.12;
        in.vwapEnabled = false;
        in.range15mEnabled = false;
        in.pdhPdlEnabled = false;
        in.pcrEnabled = false;
        in.optionSrFilterEnabled = false;
        in.standDown = false;
        in.breakerTripped = false;
        in.nowTime = LocalTime.of(10, 30);
        in.entryCutoff = LocalTime.of(11, 30);
        in.tradeAttempts = 0;
        in.maxAttempts = 2;
        in.openConcurrent = 0;
        in.maxConcurrent = 5;
        in.hasRegistryEntry = true;
        in.plannedRisk = bd(5000);
        in.remainingBudget = 15000.0;
        // P2/P3: the regulatory gates start inactive so every table test above keeps its meaning;
        // the ban and expiry/DTE tests below flip exactly the field under test.
        in.banPeriodEnabled = false;
        in.symbolBanned = false;
        in.banListUnavailable = false;
        in.banPeriodFailClosed = false;
        in.expiryDteEnabled = false;
        in.instrumentType = LvrInstrumentType.FUTURES;
        in.optionDteSessions = 10;
        in.expiryDayToday = false;
        in.optionMinDteSessions = 2;
        in.futuresMinDteSessions = 0;
        return in;
    }

    @Test
    @DisplayName("All gates satisfied → ALLOWED")
    void testBaseInputAllowed() {
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(baseInput());
        assertThat(d.allowed()).isTrue();
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.ALLOWED);
    }

    @Test
    @DisplayName("Slippage: LONG entry above the band → RETRY_COOLDOWN (gate SLIPPAGE)")
    void testSlippageLongExcessive() {
        EntryGateInput in = baseInput();
        in.entryPrice = bd(1005); // > trigger × 1.0012 = 1001.2
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("SLIPPAGE");
    }

    @Test
    @DisplayName("Slippage: SHORT entry below the band → RETRY_COOLDOWN (gate SLIPPAGE)")
    void testSlippageShortExcessive() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.SHORT;
        in.entryPrice = bd(995); // < trigger × 0.9988 = 998.8
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("SLIPPAGE");
    }

    @Test
    @DisplayName("Index-alignment: LONG with the index below previous close → RETRY_COOLDOWN")
    void testIndexAlignmentLongMisaligned() {
        EntryGateInput in = baseInput();
        in.indexAlignmentEnabled = true;
        in.indexAlignmentBufferBps = 10.0;
        in.indexPrevClose = 24000.0;
        in.indexSpot = 23950.0; // -20.8 bps: the index has reversed against the long
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("INDEX_ALIGNMENT");
    }

    @Test
    @DisplayName("Index-alignment: SHORT with the index above previous close → RETRY_COOLDOWN")
    void testIndexAlignmentShortMisaligned() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.SHORT;
        in.indexAlignmentEnabled = true;
        in.indexAlignmentBufferBps = 10.0;
        in.indexPrevClose = 24000.0;
        in.indexSpot = 24050.0; // +20.8 bps: the index has rallied against the short
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("INDEX_ALIGNMENT");
    }

    @Test
    @DisplayName("Index-alignment: dead-band vetoes a LONG inside the buffer, boundary allows it")
    void testIndexAlignmentDeadBand() {
        // Inside the band (+4.2 bps < the 10 bps buffer) the index says nothing, so a coin-flip
        // read must not veto a valid breakout. The boundary itself (+10.0 bps) is aligned.
        EntryGateInput inside = baseInput();
        inside.indexAlignmentEnabled = true;
        inside.indexAlignmentBufferBps = 10.0;
        inside.indexPrevClose = 24000.0;
        inside.indexSpot = 24010.0;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(inside);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("INDEX_ALIGNMENT");

        EntryGateInput boundary = baseInput();
        boundary.indexAlignmentEnabled = true;
        boundary.indexAlignmentBufferBps = 10.0;
        boundary.indexPrevClose = 24000.0;
        boundary.indexSpot = 24024.0; // exactly +10 bps
        assertThat(LvrEntryGate.evaluateEntryGate(boundary).allowed()).isTrue();
    }

    @Test
    @DisplayName("Index-alignment: aligned index → ALLOWED for both directions")
    void testIndexAlignmentAlignedAllowed() {
        EntryGateInput longIn = baseInput();
        longIn.indexAlignmentEnabled = true;
        longIn.indexAlignmentBufferBps = 10.0;
        longIn.indexPrevClose = 24000.0;
        longIn.indexSpot = 24100.0; // +41.7 bps
        assertThat(LvrEntryGate.evaluateEntryGate(longIn).allowed()).isTrue();

        EntryGateInput shortIn = baseInput();
        shortIn.direction = LowestVolumeDirection.SHORT;
        shortIn.indexAlignmentEnabled = true;
        shortIn.indexAlignmentBufferBps = 10.0;
        shortIn.indexPrevClose = 24000.0;
        shortIn.indexSpot = 23900.0; // -41.7 bps
        assertThat(LvrEntryGate.evaluateEntryGate(shortIn).allowed()).isTrue();
    }

    @Test
    @DisplayName("Index-alignment: fail-open — a missing or invalid index read never blocks entry")
    void testIndexAlignmentFailOpen() {
        // No level quoted (outage / unresolvable token): the gate must not stand in for the data.
        EntryGateInput missing = baseInput();
        missing.indexAlignmentEnabled = true;
        missing.indexAlignmentBufferBps = 10.0;
        missing.indexPrevClose = 24000.0;
        missing.indexSpot = null;
        assertThat(LvrEntryGate.evaluateEntryGate(missing).allowed()).isTrue();

        // Prev close of 0 would make the basis-point move meaningless — treat as unavailable.
        EntryGateInput zeroPrev = baseInput();
        zeroPrev.indexAlignmentEnabled = true;
        zeroPrev.indexAlignmentBufferBps = 10.0;
        zeroPrev.indexPrevClose = 0.0;
        zeroPrev.indexSpot = 24000.0;
        assertThat(LvrEntryGate.evaluateEntryGate(zeroPrev).allowed()).isTrue();
    }

    @Test
    @DisplayName("Index-alignment: flag off → misaligned index is ignored (gate skipped)")
    void testIndexAlignmentDisabledSkipsGate() {
        EntryGateInput in = baseInput();
        in.indexAlignmentEnabled = false;
        in.indexAlignmentBufferBps = 10.0;
        in.indexPrevClose = 24000.0;
        in.indexSpot = 23950.0; // would have denied had the flag been on
        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("Index-alignment: identical live and replay inputs still decide identically")
    void testIndexAlignmentLiveReplayParity() {
        EntryGateInput live = baseInput();
        live.indexAlignmentEnabled = true;
        live.indexAlignmentBufferBps = 10.0;
        live.indexPrevClose = 24000.0;
        live.indexSpot = 23950.0;

        EntryGateInput replay = baseInput();
        replay.indexAlignmentEnabled = true;
        replay.indexAlignmentBufferBps = 10.0;
        replay.indexPrevClose = 24000.0;
        replay.indexSpot = 23950.0;

        EntryGateDecision liveDecision = LvrEntryGate.evaluateEntryGate(live);
        assertThat(LvrEntryGate.evaluateEntryGate(replay)).isEqualTo(liveDecision);
        assertThat(liveDecision.gate()).isEqualTo("INDEX_ALIGNMENT");
    }

    @Test
    @DisplayName("VWAP unavailable while enabled → RETRY fail-closed (gate VWAP)")
    void testVwapUnavailableRetries() {
        EntryGateInput in = baseInput();
        in.vwapEnabled = true;
        in.vwap = null;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("VWAP");
    }

    @Test
    @DisplayName("VWAP wrong side → RETRY_COOLDOWN (gate VWAP); right side → ALLOWED")
    void testVwapConfirmation() {
        EntryGateInput wrong = baseInput();
        wrong.vwapEnabled = true;
        wrong.vwap = 1005.0; // LONG requires decisionPrice > VWAP
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(wrong);
        // Transient by nature: VWAP moves every tick and this input is rebuilt from a fresh quote
        // each pass. Denying with EXHAUST would discard the symbol for the day on one bad tick.
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d1.gate()).isEqualTo("VWAP");

        EntryGateInput right = baseInput();
        right.vwapEnabled = true;
        right.vwap = 990.0;
        assertThat(LvrEntryGate.evaluateEntryGate(right).allowed()).isTrue();
    }

    @Test
    @DisplayName("15-min range: spot inside the opening range → RETRY_COOLDOWN (gate 15M_RANGE)")
    void testFifteenMinuteRangeInside() {
        EntryGateInput in = baseInput();
        in.range15mEnabled = true;
        in.first15mHigh = bd(1010);
        in.first15mLow = bd(990);
        in.decisionPrice = bd(1001);
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("15M_RANGE");

        in.decisionPrice = bd(1011); // above the high
        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("15-min range: missing values fail closed (gate 15M_RANGE)")
    void testFifteenMinuteRangeUnavailableFailsClosed() {
        EntryGateInput in = baseInput();
        in.range15mEnabled = true;
        in.first15mHigh = null;
        in.first15mLow = null;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("15M_RANGE");
    }

    @Test
    @DisplayName("PDH/PDL: missing values exhaust (bounded retries done); inside range retries")
    void testPdhPdlGates() {
        EntryGateInput missing = baseInput();
        missing.pdhPdlEnabled = true;
        missing.pdh = null;
        missing.pdl = null;
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(missing);
        // The only price-relative gate that legitimately exhausts: the live path has already
        // burned its bounded N5 lazy-fetch retries before this gate ever sees a null.
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("PDH_PDL");

        EntryGateInput inside = baseInput();
        inside.pdhPdlEnabled = true;
        inside.pdh = bd(1010);
        inside.pdl = bd(990);
        inside.decisionPrice = bd(1001); // LONG must be > PDH
        // Inside the range is a function of the live quote, not a permanent verdict.
        assertThat(LvrEntryGate.evaluateEntryGate(inside).disposition())
                .isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);

        inside.decisionPrice = bd(1011);
        assertThat(LvrEntryGate.evaluateEntryGate(inside).allowed()).isTrue();
    }

    @Test
    @DisplayName("PCR unavailable while enabled → RETRY fail-closed (gate PCR)")
    void testPcrUnavailableRetries() {
        EntryGateInput in = baseInput();
        in.pcrEnabled = true;
        in.pcr = null;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("PCR");
    }

    @Test
    @DisplayName("PCR gate for LONG: only PCR above the neutral band allows (gate PCR)")
    void testPcrLongGate() {
        EntryGateInput inBand = baseInput();
        inBand.pcrEnabled = true;
        inBand.pcrNeutralMin = 0.85;
        inBand.pcrNeutralMax = 1.15;
        inBand.pcr = 1.10; // Inside neutral band -> RETRY_COOLDOWN
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(inBand);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d1.gate()).isEqualTo("PCR");

        EntryGateInput bearish = baseInput();
        bearish.pcrEnabled = true;
        bearish.pcrNeutralMin = 0.85;
        bearish.pcrNeutralMax = 1.15;
        bearish.pcr = 0.70; // Below band (bearish) -> RETRY_COOLDOWN
        EntryGateDecision d2 = LvrEntryGate.evaluateEntryGate(bearish);
        assertThat(d2.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d2.gate()).isEqualTo("PCR");

        EntryGateInput right = baseInput();
        right.pcrEnabled = true;
        right.pcrNeutralMin = 0.85;
        right.pcrNeutralMax = 1.15;
        right.pcr = 1.30; // Above band (bullish) -> allowed
        assertThat(LvrEntryGate.evaluateEntryGate(right).allowed()).isTrue();
    }

    @Test
    @DisplayName("PCR gate for SHORT: only PCR below the neutral band allows (gate PCR)")
    void testPcrShortGate() {
        EntryGateInput inBand = baseInput();
        inBand.direction = LowestVolumeDirection.SHORT;
        inBand.pcrEnabled = true;
        inBand.pcrNeutralMin = 0.85;
        inBand.pcrNeutralMax = 1.15;
        inBand.pcr = 0.90; // Inside neutral band -> RETRY_COOLDOWN
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(inBand);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d1.gate()).isEqualTo("PCR");

        EntryGateInput bullish = baseInput();
        bullish.direction = LowestVolumeDirection.SHORT;
        bullish.pcrEnabled = true;
        bullish.pcrNeutralMin = 0.85;
        bullish.pcrNeutralMax = 1.15;
        bullish.pcr = 1.40; // Above band (bullish) -> RETRY_COOLDOWN
        EntryGateDecision d2 = LvrEntryGate.evaluateEntryGate(bullish);
        assertThat(d2.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d2.gate()).isEqualTo("PCR");

        EntryGateInput right = baseInput();
        right.direction = LowestVolumeDirection.SHORT;
        right.pcrEnabled = true;
        right.pcrNeutralMin = 0.85;
        right.pcrNeutralMax = 1.15;
        right.pcr = 0.70; // Below band (bearish) -> allowed
        assertThat(LvrEntryGate.evaluateEntryGate(right).allowed()).isTrue();
    }

    @Test
    @DisplayName("PCR 0.85-1.15 neutral band blocks BOTH directions (no trade)")
    void testNeutralPcrBandBlocksBothDirections() {
        for (double pcr : new double[] {0.85, 1.00, 1.15}) {
            for (LowestVolumeDirection dir :
                    new LowestVolumeDirection[] {
                        LowestVolumeDirection.LONG, LowestVolumeDirection.SHORT
                    }) {
                EntryGateInput in = baseInput();
                in.direction = dir;
                in.pcrEnabled = true;
                in.pcrNeutralMin = 0.85;
                in.pcrNeutralMax = 1.15;
                in.pcr = pcr;

                EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
                assertThat(d.disposition())
                        .as("PCR %.2f / %s must be blocked", pcr, dir)
                        .isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
                assertThat(d.gate()).isEqualTo("PCR");
                assertThat(d.allowed()).as("PCR %.2f / %s must never pass", pcr, dir).isFalse();
            }
        }
    }

    @Test
    @DisplayName("Option S&R: LONG entry blocked at or above Max Call OI resistance")
    void testOptionSrBlocksLongNearResistance() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.LONG;
        in.optionSrFilterEnabled = true;
        in.optionResistanceStrike = bd(1000);
        in.decisionPrice = bd(1000); // at resistance -> RETRY_COOLDOWN
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d1.gate()).isEqualTo("OPTION_SR");

        in.decisionPrice = bd(990); // safely below resistance -> ALLOWED
        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("Option S&R: SHORT entry blocked at or below Max Put OI support")
    void testOptionSrBlocksShortNearSupport() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.SHORT;
        in.optionSrFilterEnabled = true;
        in.optionSupportStrike = bd(1000);
        in.decisionPrice = bd(1000); // at support -> RETRY_COOLDOWN
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d1.gate()).isEqualTo("OPTION_SR");

        in.decisionPrice = bd(1010); // safely above support -> ALLOWED
        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("Option S&R: a missing level fails closed instead of skipping the filter")
    void testOptionSrFailsClosedWhenLevelUnavailable() {
        // A null level means the chain could not be read for that side. The filter used to be
        // guarded by `strike != null`, so a missing level silently skipped the check and the
        // entry went ahead with no resistance or support test at all.
        EntryGateInput longIn = baseInput();
        longIn.direction = LowestVolumeDirection.LONG;
        longIn.optionSrFilterEnabled = true;
        longIn.optionSupportStrike = bd(900); // the WRONG side is populated
        longIn.decisionPrice = bd(950);
        EntryGateDecision longDenied = LvrEntryGate.evaluateEntryGate(longIn);
        assertThat(longDenied.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(longDenied.gate()).isEqualTo("OPTION_SR");

        EntryGateInput shortIn = baseInput();
        shortIn.direction = LowestVolumeDirection.SHORT;
        shortIn.optionSrFilterEnabled = true;
        shortIn.optionResistanceStrike = bd(1100); // the WRONG side is populated
        shortIn.decisionPrice = bd(1050);
        EntryGateDecision shortDenied = LvrEntryGate.evaluateEntryGate(shortIn);
        assertThat(shortDenied.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(shortDenied.gate()).isEqualTo("OPTION_SR");

        // Only the level for the direction being traded is required, so a populated level for
        // this direction is enough even when the other side is absent.
        EntryGateInput okIn = baseInput();
        okIn.direction = LowestVolumeDirection.LONG;
        okIn.optionSrFilterEnabled = true;
        okIn.optionResistanceStrike = bd(1000);
        okIn.decisionPrice = bd(950);
        assertThat(LvrEntryGate.evaluateEntryGate(okIn).allowed()).isTrue();
    }

    @Test
    @DisplayName("Stand-down → RETRY (gate STAND_DOWN); breaker → RETRY (gate BREAKER)")
    void testStandDownAndBreaker() {
        EntryGateInput sd = baseInput();
        sd.standDown = true;
        EntryGateDecision d1 = LvrEntryGate.evaluateEntryGate(sd);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d1.gate()).isEqualTo("STAND_DOWN");

        EntryGateInput br = baseInput();
        br.breakerTripped = true;
        EntryGateDecision d2 = LvrEntryGate.evaluateEntryGate(br);
        assertThat(d2.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d2.gate()).isEqualTo("BREAKER");
    }

    @Test
    @DisplayName("Entry cutoff reached → RETRY (gate CUTOFF)")
    void testEntryCutoff() {
        EntryGateInput in = baseInput();
        in.nowTime = LocalTime.of(11, 30);
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("CUTOFF");
    }

    @Test
    @DisplayName("Attempt budget exhausted → RETRY (gate ATTEMPTS)")
    void testAttemptsExhausted() {
        EntryGateInput in = baseInput();
        in.tradeAttempts = 2;
        in.maxAttempts = 2;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("ATTEMPTS");
    }

    @Test
    @DisplayName("Max concurrent trades reached → RETRY (gate CONCURRENCY)")
    void testConcurrencyLimit() {
        EntryGateInput in = baseInput();
        in.openConcurrent = 5;
        in.maxConcurrent = 5;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("CONCURRENCY");
    }

    @Test
    @DisplayName("F&O registry miss → EXHAUST (gate REGISTRY) — never fabricate lot size")
    void testRegistryMiss() {
        EntryGateInput in = baseInput();
        in.hasRegistryEntry = false;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("REGISTRY");
    }

    @Test
    @DisplayName(
            "Regression: price-relative denials are RETRY/RETRY_COOLDOWN, never a day-long EXHAUST")
    void testPriceRelativeDenialsNeverExhaust() {
        // Each gate below is recomputed from a FRESH quote on every 30-second tick, so "the wrong
        // side at this instant" is not a permanent verdict. Denying any of them with EXHAUST
        // discarded the symbol for the rest of the day — its armed setup, its correctly computed
        // trigger, SL and target, and both of its attempts — because it happened to sit a few paise
        // the wrong side of a moving average on the single tick its trigger fired. Exhaustion is
        // reserved for what genuinely cannot recover: a registry miss, and PDH/PDL still missing
        // after the bounded N5 fetch retries.
        record Case(String name, java.util.function.Consumer<EntryGateInput> configure) {}

        List<Case> cases =
                List.of(
                        new Case(
                                "VWAP wrong side",
                                in -> {
                                    in.vwapEnabled = true;
                                    in.vwap = 1005.0;
                                }),
                        new Case(
                                "15m range inside",
                                in -> {
                                    in.range15mEnabled = true;
                                    in.first15mHigh = bd(1010);
                                    in.first15mLow = bd(990);
                                    in.decisionPrice = bd(1001);
                                }),
                        new Case(
                                "PDH/PDL inside",
                                in -> {
                                    in.pdhPdlEnabled = true;
                                    in.pdh = bd(1010);
                                    in.pdl = bd(990);
                                    in.decisionPrice = bd(1001);
                                }),
                        new Case(
                                "PDH extension beyond the cap",
                                in -> {
                                    in.pdhPdlEnabled = true;
                                    in.pdh = bd(1000);
                                    in.pdl = bd(990);
                                    in.maxPdhExtensionPct = 5.0;
                                    in.decisionPrice = bd(1100); // 10% above PDH
                                }),
                        new Case(
                                "PCR in the neutral band",
                                in -> {
                                    in.pcrEnabled = true;
                                    in.pcrNeutralMin = 0.85;
                                    in.pcrNeutralMax = 1.15;
                                    in.pcr = 1.00;
                                }),
                        new Case(
                                "Option S&R at resistance",
                                in -> {
                                    in.optionSrFilterEnabled = true;
                                    in.optionResistanceStrike = bd(1000);
                                    in.decisionPrice = bd(1000);
                                }));

        for (Case c : cases) {
            EntryGateInput in = baseInput();
            c.configure().accept(in);
            EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
            assertThat(d.allowed()).as("%s must be denied", c.name()).isFalse();
            assertThat(d.disposition())
                    .as("%s must not permanently discard the symbol for the day", c.name())
                    .isIn(EntryGateDisposition.RETRY, EntryGateDisposition.RETRY_COOLDOWN);
        }
    }

    @Test
    @DisplayName("Planned risk above remaining daily budget → RETRY (gate BUDGET, M2)")
    void testBudgetExceeded() {
        EntryGateInput in = baseInput();
        in.plannedRisk = bd(20000);
        in.remainingBudget = 15000.0;
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("BUDGET");
    }

    @Test
    @DisplayName("Gate order: slippage is evaluated before VWAP (first failing gate wins)")
    void testGateOrderSlippageBeforeVwap() {
        EntryGateInput in = baseInput();
        in.entryPrice = bd(1005); // slippage breach
        in.vwapEnabled = true;
        in.vwap = 1005.0; // also VWAP-failing
        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
        assertThat(d.gate()).isEqualTo("SLIPPAGE");
    }

    @Test
    @DisplayName(
            "L6 parity: live-style and replay-style inputs with identical values decide identically")
    void testLiveReplayInputParity() {
        // Live constructs: decisionPrice = spot LTP at touch, entryPrice = same spot.
        EntryGateInput live = baseInput();
        live.decisionPrice = bd(1001);
        live.entryPrice = bd(1001);

        // Replay constructs: decisionPrice = trigger (first touch), entryPrice = candle open.
        // Same VALUES → same decision (the function is caller-agnostic).
        EntryGateInput replay = baseInput();
        replay.decisionPrice = bd(1001);
        replay.entryPrice = bd(1001);

        EntryGateDecision liveDecision = LvrEntryGate.evaluateEntryGate(live);
        EntryGateDecision replayDecision = LvrEntryGate.evaluateEntryGate(replay);
        assertThat(replayDecision).isEqualTo(liveDecision);

        // And the same holds for a denial: both see the identical gate + disposition.
        live.entryPrice = bd(1005);
        replay.entryPrice = bd(1005);
        assertThat(LvrEntryGate.evaluateEntryGate(replay))
                .isEqualTo(LvrEntryGate.evaluateEntryGate(live));

        // P2/P3 parity: the regulatory gates decide identically too, for both a ban denial …
        for (EntryGateInput in : List.of(live, replay)) {
            in.entryPrice = bd(1001);
            in.banPeriodEnabled = true;
            in.symbolBanned = true;
            in.banListUnavailable = false;
        }
        EntryGateDecision liveBan = LvrEntryGate.evaluateEntryGate(live);
        assertThat(liveBan.gate()).isEqualTo("BAN_PERIOD");
        assertThat(LvrEntryGate.evaluateEntryGate(replay)).isEqualTo(liveBan);

        // … and a DTE denial.
        for (EntryGateInput in : List.of(live, replay)) {
            in.banPeriodEnabled = false;
            in.symbolBanned = false;
            in.expiryDteEnabled = true;
            in.instrumentType = LvrInstrumentType.OPTIONS;
            in.optionDteSessions = 1;
            in.optionMinDteSessions = 2;
        }
        EntryGateDecision liveDte = LvrEntryGate.evaluateEntryGate(live);
        assertThat(liveDte.gate()).isEqualTo("EXPIRY_DTE");
        assertThat(LvrEntryGate.evaluateEntryGate(replay)).isEqualTo(liveDte);
    }

    @Test
    @DisplayName("Symbol in F&O ban → EXHAUST (gate BAN_PERIOD)")
    void testSymbolBannedExhausts() {
        EntryGateInput in = baseInput();
        in.banPeriodEnabled = true;
        in.symbolBanned = true;

        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);

        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("BAN_PERIOD");
        assertThat(d.reason()).contains("ban period");
    }

    @Test
    @DisplayName("Ban is absolute and price-independent: BAN_PERIOD is evaluated before SLIPPAGE")
    void testBanEvaluatedBeforeSlippage() {
        EntryGateInput in = baseInput();
        in.banPeriodEnabled = true;
        in.symbolBanned = true;
        in.entryPrice = bd(1005); // also an SLIPPAGE breach
        in.vwapEnabled = true;
        in.vwap = 1005.0; // also a VWAP failure

        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);

        // A symbol that can never be traded must not burn a 60s slippage cooldown first.
        assertThat(d.gate()).isEqualTo("BAN_PERIOD");
    }

    @Test
    @DisplayName("Ban list unavailable + fail-open (default) → ALLOWED")
    void testBanUnavailableFailsOpen() {
        EntryGateInput in = baseInput();
        in.banPeriodEnabled = true;
        in.symbolBanned = false;
        in.banListUnavailable = true;
        in.banPeriodFailClosed = false;

        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed())
                .as("a network blip must not shut the strategy down")
                .isTrue();
    }

    @Test
    @DisplayName("Ban list unavailable + fail-closed → RETRY, never EXHAUST (gate BAN_PERIOD)")
    void testBanUnavailableFailsClosed() {
        EntryGateInput in = baseInput();
        in.banPeriodEnabled = true;
        in.symbolBanned = false;
        in.banListUnavailable = true;
        in.banPeriodFailClosed = true;

        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);

        // A transient fetch failure must not permanently discard symbols for the day.
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("BAN_PERIOD");
    }

    @Test
    @DisplayName("Ban gate off (kill switch / log-only mode) → ALLOWED even for a banned symbol")
    void testBanGateOff() {
        EntryGateInput in = baseInput();
        in.banPeriodEnabled = false;
        in.symbolBanned = true;
        in.banListUnavailable = true;

        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("OPTIONS on the monthly expiry session → EXHAUST (gate EXPIRY_DTE)")
    void testExpiryDayBlocksOptions() {
        EntryGateInput in = baseInput();
        in.expiryDteEnabled = true;
        in.instrumentType = LvrInstrumentType.OPTIONS;
        in.expiryDayToday = true;
        in.optionDteSessions = 0;

        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);

        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("EXPIRY_DTE");
        assertThat(d.reason()).contains("expiry session");
    }

    @Test
    @DisplayName("OPTIONS inside the DTE floor → EXHAUST; at the floor → ALLOWED (gate EXPIRY_DTE)")
    void testDteFloorBlocksNearExpiry() {
        EntryGateInput in = baseInput();
        in.expiryDteEnabled = true;
        in.instrumentType = LvrInstrumentType.OPTIONS;
        in.optionMinDteSessions = 2;
        in.optionDteSessions = 1;
        in.expiryDayToday = false;

        EntryGateDecision denied = LvrEntryGate.evaluateEntryGate(in);

        assertThat(denied.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(denied.gate()).isEqualTo("EXPIRY_DTE");
        assertThat(denied.reason()).contains("trading session(s) left");

        // Two sessions left is exactly the floor — not below it, so the entry proceeds.
        in.optionDteSessions = 2;
        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("FUTURES pass the DTE gate at the default floor of 0, even on expiry day")
    void testFuturesUnaffectedByDefaultFloor() {
        EntryGateInput in = baseInput();
        in.expiryDteEnabled = true;
        in.instrumentType = LvrInstrumentType.FUTURES;
        in.futuresMinDteSessions = 0;
        in.expiryDayToday = true;
        in.optionDteSessions = 0;

        assertThat(LvrEntryGate.evaluateEntryGate(in).allowed())
                .as("the live FUTURES strategy must not be silently disabled")
                .isTrue();
    }

    @Test
    @DisplayName("FUTURES honour futures-min-dte-sessions once it is raised above 0")
    void testFuturesFloorCanBeRaised() {
        EntryGateInput in = baseInput();
        in.expiryDteEnabled = true;
        in.instrumentType = LvrInstrumentType.FUTURES;
        in.futuresMinDteSessions = 2;
        in.expiryDayToday = false;
        in.optionDteSessions = 0;

        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);

        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("EXPIRY_DTE");
    }

    @Test
    @DisplayName(
            "Unresolvable series with the DTE gate active → EXHAUST fail-closed (gate EXPIRY_DTE)")
    void testUnknownDteFailsClosed() {
        EntryGateInput in = baseInput();
        in.expiryDteEnabled = true;
        in.instrumentType = LvrInstrumentType.OPTIONS;
        in.optionDteSessions = null;

        EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);

        // Mirrors REGISTRY: a contract we cannot resolve cannot be traded, and the miss is
        // permanent for the day.
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("EXPIRY_DTE");
    }

    @Test
    @DisplayName("Gate order: CUTOFF is evaluated before EXPIRY_DTE")
    void testCutoffBeforeExpiryDte() {
        EntryGateInput in = baseInput();
        in.nowTime = LocalTime.of(11, 30); // == cutoff → CUTOFF fires
        in.expiryDteEnabled = true;
        in.instrumentType = LvrInstrumentType.OPTIONS;
        in.expiryDayToday = true;
        in.optionDteSessions = 0;

        assertThat(LvrEntryGate.evaluateEntryGate(in).gate()).isEqualTo("CUTOFF");
    }

    @Test
    @DisplayName(
            "Complement of the price-relative rule: day-static regulatory/data denials ARE EXHAUST")
    void testDayStaticDenialsAreExhaust() {
        record Case(
                String name, String gate, java.util.function.Consumer<EntryGateInput> configure) {}

        List<Case> cases =
                List.of(
                        new Case(
                                "F&O ban",
                                "BAN_PERIOD",
                                in -> {
                                    in.banPeriodEnabled = true;
                                    in.symbolBanned = true;
                                }),
                        new Case(
                                "series DTE below the floor",
                                "EXPIRY_DTE",
                                in -> {
                                    in.expiryDteEnabled = true;
                                    in.instrumentType = LvrInstrumentType.OPTIONS;
                                    in.optionDteSessions = 1;
                                }),
                        new Case("registry miss", "REGISTRY", in -> in.hasRegistryEntry = false));

        for (Case c : cases) {
            EntryGateInput in = baseInput();
            c.configure().accept(in);
            EntryGateDecision d = LvrEntryGate.evaluateEntryGate(in);
            assertThat(d.disposition())
                    .as("%s is session-static and cannot recover intraday", c.name())
                    .isEqualTo(EntryGateDisposition.EXHAUST);
            assertThat(d.gate()).as("%s must name its own gate", c.name()).isEqualTo(c.gate());
        }
    }
}

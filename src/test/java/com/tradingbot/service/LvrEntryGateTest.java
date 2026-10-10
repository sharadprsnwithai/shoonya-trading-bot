package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.service.LowestVolumeReversalService.EntryGateDecision;
import com.tradingbot.service.LowestVolumeReversalService.EntryGateDisposition;
import com.tradingbot.service.LowestVolumeReversalService.EntryGateInput;
import java.math.BigDecimal;
import java.time.LocalTime;
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
        return in;
    }

    @Test
    @DisplayName("All gates satisfied → ALLOWED")
    void testBaseInputAllowed() {
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(baseInput());
        assertThat(d.allowed()).isTrue();
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.ALLOWED);
    }

    @Test
    @DisplayName("Slippage: LONG entry above the band → RETRY_COOLDOWN (gate SLIPPAGE)")
    void testSlippageLongExcessive() {
        EntryGateInput in = baseInput();
        in.entryPrice = bd(1005); // > trigger × 1.0012 = 1001.2
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("SLIPPAGE");
    }

    @Test
    @DisplayName("Slippage: SHORT entry below the band → RETRY_COOLDOWN (gate SLIPPAGE)")
    void testSlippageShortExcessive() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.SHORT;
        in.entryPrice = bd(995); // < trigger × 0.9988 = 998.8
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY_COOLDOWN);
        assertThat(d.gate()).isEqualTo("SLIPPAGE");
    }

    @Test
    @DisplayName("VWAP unavailable while enabled → RETRY fail-closed (gate VWAP)")
    void testVwapUnavailableRetries() {
        EntryGateInput in = baseInput();
        in.vwapEnabled = true;
        in.vwap = null;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("VWAP");
    }

    @Test
    @DisplayName("VWAP wrong side → EXHAUST (gate VWAP); right side → ALLOWED")
    void testVwapConfirmation() {
        EntryGateInput wrong = baseInput();
        wrong.vwapEnabled = true;
        wrong.vwap = 1005.0; // LONG requires decisionPrice > VWAP
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(wrong);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("VWAP");

        EntryGateInput right = baseInput();
        right.vwapEnabled = true;
        right.vwap = 990.0;
        assertThat(LowestVolumeReversalService.evaluateEntryGate(right).allowed()).isTrue();
    }

    @Test
    @DisplayName("15-min range: spot inside the opening range → EXHAUST (gate 15M_RANGE)")
    void testFifteenMinuteRangeInside() {
        EntryGateInput in = baseInput();
        in.range15mEnabled = true;
        in.first15mHigh = bd(1010);
        in.first15mLow = bd(990);
        in.decisionPrice = bd(1001);
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("15M_RANGE");

        in.decisionPrice = bd(1011); // above the high
        assertThat(LowestVolumeReversalService.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("15-min range: missing values fail closed (gate 15M_RANGE)")
    void testFifteenMinuteRangeUnavailableFailsClosed() {
        EntryGateInput in = baseInput();
        in.range15mEnabled = true;
        in.first15mHigh = null;
        in.first15mLow = null;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("15M_RANGE");
    }

    @Test
    @DisplayName("PDH/PDL: missing values fail closed (gate PDH_PDL); inside range exhausts")
    void testPdhPdlGates() {
        EntryGateInput missing = baseInput();
        missing.pdhPdlEnabled = true;
        missing.pdh = null;
        missing.pdl = null;
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(missing);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("PDH_PDL");

        EntryGateInput inside = baseInput();
        inside.pdhPdlEnabled = true;
        inside.pdh = bd(1010);
        inside.pdl = bd(990);
        inside.decisionPrice = bd(1001); // LONG must be > PDH
        assertThat(LowestVolumeReversalService.evaluateEntryGate(inside).disposition())
                .isEqualTo(EntryGateDisposition.EXHAUST);

        inside.decisionPrice = bd(1011);
        assertThat(LowestVolumeReversalService.evaluateEntryGate(inside).allowed()).isTrue();
    }

    @Test
    @DisplayName("PCR unavailable while enabled → RETRY fail-closed (gate PCR)")
    void testPcrUnavailableRetries() {
        EntryGateInput in = baseInput();
        in.pcrEnabled = true;
        in.pcr = null;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("PCR");
    }

    @Test
    @DisplayName("PCR gate for LONG: PCR below min threshold exhausts, above allows")
    void testPcrLongGate() {
        EntryGateInput wrong = baseInput();
        wrong.pcrEnabled = true;
        wrong.pcrMinLong = 0.85;
        wrong.pcr = 0.70; // Below 0.85 -> EXHAUST
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(wrong);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("PCR");

        EntryGateInput right = baseInput();
        right.pcrEnabled = true;
        right.pcrMinLong = 0.85;
        right.pcr = 1.10;
        assertThat(LowestVolumeReversalService.evaluateEntryGate(right).allowed()).isTrue();
    }

    @Test
    @DisplayName("PCR gate for SHORT: PCR above max threshold exhausts, below allows")
    void testPcrShortGate() {
        EntryGateInput wrong = baseInput();
        wrong.direction = LowestVolumeDirection.SHORT;
        wrong.pcrEnabled = true;
        wrong.pcrMaxShort = 1.15;
        wrong.pcr = 1.40; // Above 1.15 -> EXHAUST
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(wrong);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("PCR");

        EntryGateInput right = baseInput();
        right.direction = LowestVolumeDirection.SHORT;
        right.pcrEnabled = true;
        right.pcrMaxShort = 1.15;
        right.pcr = 0.90;
        assertThat(LowestVolumeReversalService.evaluateEntryGate(right).allowed()).isTrue();
    }

    @Test
    @DisplayName("Option S&R: LONG entry blocked at or above Max Call OI resistance")
    void testOptionSrBlocksLongNearResistance() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.LONG;
        in.optionSrFilterEnabled = true;
        in.optionResistanceStrike = bd(1000);
        in.decisionPrice = bd(1000); // at resistance -> EXHAUST
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("OPTION_SR");

        in.decisionPrice = bd(990); // safely below resistance -> ALLOWED
        assertThat(LowestVolumeReversalService.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("Option S&R: SHORT entry blocked at or below Max Put OI support")
    void testOptionSrBlocksShortNearSupport() {
        EntryGateInput in = baseInput();
        in.direction = LowestVolumeDirection.SHORT;
        in.optionSrFilterEnabled = true;
        in.optionSupportStrike = bd(1000);
        in.decisionPrice = bd(1000); // at support -> EXHAUST
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d1.gate()).isEqualTo("OPTION_SR");

        in.decisionPrice = bd(1010); // safely above support -> ALLOWED
        assertThat(LowestVolumeReversalService.evaluateEntryGate(in).allowed()).isTrue();
    }

    @Test
    @DisplayName("Stand-down → RETRY (gate STAND_DOWN); breaker → RETRY (gate BREAKER)")
    void testStandDownAndBreaker() {
        EntryGateInput sd = baseInput();
        sd.standDown = true;
        EntryGateDecision d1 = LowestVolumeReversalService.evaluateEntryGate(sd);
        assertThat(d1.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d1.gate()).isEqualTo("STAND_DOWN");

        EntryGateInput br = baseInput();
        br.breakerTripped = true;
        EntryGateDecision d2 = LowestVolumeReversalService.evaluateEntryGate(br);
        assertThat(d2.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d2.gate()).isEqualTo("BREAKER");
    }

    @Test
    @DisplayName("Entry cutoff reached → RETRY (gate CUTOFF)")
    void testEntryCutoff() {
        EntryGateInput in = baseInput();
        in.nowTime = LocalTime.of(11, 30);
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("CUTOFF");
    }

    @Test
    @DisplayName("Attempt budget exhausted → RETRY (gate ATTEMPTS)")
    void testAttemptsExhausted() {
        EntryGateInput in = baseInput();
        in.tradeAttempts = 2;
        in.maxAttempts = 2;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("ATTEMPTS");
    }

    @Test
    @DisplayName("Max concurrent trades reached → RETRY (gate CONCURRENCY)")
    void testConcurrencyLimit() {
        EntryGateInput in = baseInput();
        in.openConcurrent = 5;
        in.maxConcurrent = 5;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.RETRY);
        assertThat(d.gate()).isEqualTo("CONCURRENCY");
    }

    @Test
    @DisplayName("F&O registry miss → EXHAUST (gate REGISTRY) — never fabricate lot size")
    void testRegistryMiss() {
        EntryGateInput in = baseInput();
        in.hasRegistryEntry = false;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
        assertThat(d.disposition()).isEqualTo(EntryGateDisposition.EXHAUST);
        assertThat(d.gate()).isEqualTo("REGISTRY");
    }

    @Test
    @DisplayName("Planned risk above remaining daily budget → RETRY (gate BUDGET, M2)")
    void testBudgetExceeded() {
        EntryGateInput in = baseInput();
        in.plannedRisk = bd(20000);
        in.remainingBudget = 15000.0;
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
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
        EntryGateDecision d = LowestVolumeReversalService.evaluateEntryGate(in);
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

        EntryGateDecision liveDecision = LowestVolumeReversalService.evaluateEntryGate(live);
        EntryGateDecision replayDecision = LowestVolumeReversalService.evaluateEntryGate(replay);
        assertThat(replayDecision).isEqualTo(liveDecision);

        // And the same holds for a denial: both see the identical gate + disposition.
        live.entryPrice = bd(1005);
        replay.entryPrice = bd(1005);
        assertThat(LowestVolumeReversalService.evaluateEntryGate(replay))
                .isEqualTo(LowestVolumeReversalService.evaluateEntryGate(live));
    }
}

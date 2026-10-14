package com.tradingbot.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the per-instrument state accumulator. */
class FeedTickTest {

    /**
     * @param json the frame to parse
     */
    private static JsonNode frame(String json) {
        return ShoonyaFeedProtocol.parse(json);
    }

    @Test
    @DisplayName("a full snapshot establishes every quote field")
    void testApplyFull() {
        FeedTick tick = new FeedTick("NSE", "2885");

        boolean ok =
                tick.applyFull(
                        frame(
                                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\","
                                        + "\"c\":\"1400.00\",\"o\":\"1410.00\",\"h\":\"1455.00\","
                                        + "\"l\":\"1405.00\",\"ap\":\"1430.00\",\"v\":\"250000\"}"));

        assertThat(ok).isTrue();
        assertThat(tick.isEstablished()).isTrue();
        assertThat(tick.lastPrice()).isEqualTo(1449.00);
        assertThat(tick.prevClose()).isEqualTo(1400.00);
        assertThat(tick.open()).isEqualTo(1410.00);
        assertThat(tick.avgPrice()).isEqualTo(1430.00);
        assertThat(tick.volume()).isEqualTo(250000L);
        assertThat(tick.pctChange()).isEqualTo(3.5, within(1e-9));
    }

    @Test
    @DisplayName("the headline regression: a diff never zeroes the previous close")
    void testDiffDoesNotDestroyPrevClose() {
        // A tf frame carries only the fields that changed. Applying it as a full snapshot would
        // blank the previous close, and a zero previous close silently breaks the advance/decline
        // breadth gate that decides whether the bot trades at all today.
        FeedTick tick = new FeedTick("NSE", "2885");
        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\",\"c\":\"1400.00\"}"));

        tick.applyDiff(frame("{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1455.00\"}"));

        assertThat(tick.lastPrice()).isEqualTo(1455.00);
        assertThat(tick.prevClose()).isEqualTo(1400.00);
        assertThat(tick.pctChange()).isEqualTo(3.9285714285714284, within(1e-9));
    }

    @Test
    @DisplayName("a diff arriving before any snapshot is refused rather than invented")
    void testDiffBeforeSnapshotIsRefused() {
        FeedTick tick = new FeedTick("NSE", "2885");

        boolean merged =
                tick.applyDiff(
                        frame("{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1455.00\"}"));

        assertThat(merged).isFalse();
        assertThat(tick.isEstablished()).isFalse();
        assertThat(tick.lastPrice()).isZero();
    }

    @Test
    @DisplayName("a later snapshot overwrites rather than merges, so stale values cannot linger")
    void testSecondFullSnapshotOverwrites() {
        FeedTick tick = new FeedTick("NSE", "2885");
        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\",\"c\":\"1400.00\",\"v\":\"999\"}"));

        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1470.00\",\"c\":\"1400.00\"}"));

        assertThat(tick.lastPrice()).isEqualTo(1470.00);
        assertThat(tick.volume()).isZero();
        assertThat(tick.prevClose()).isEqualTo(1400.00);
    }

    @Test
    @DisplayName("frames without a usable last price are refused and leave the state untouched")
    void testFrameWithoutPriceIsRefused() {
        FeedTick tick = new FeedTick("NSE", "2885");
        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\",\"c\":\"1400.00\"}"));

        assertThat(tick.applyFull(frame("{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\"}"))).isFalse();
        assertThat(tick.lastPrice()).isEqualTo(1449.00);

        assertThat(
                        tick.applyDiff(
                                frame(
                                        "{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"0.00\"}")))
                .isFalse();
        assertThat(tick.lastPrice()).isEqualTo(1449.00);
    }

    @Test
    @DisplayName("open interest percentage matches the pre-existing GetQuotes convention")
    void testOiPctChange() {
        FeedTick tick = new FeedTick("NFO", "12345");

        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NFO\",\"tk\":\"12345\",\"lp\":\"512.00\",\"oi\":\"148\",\"poi\":\"100\"}"));

        assertThat(tick.openInterest()).isEqualTo(148L);
        assertThat(tick.prevDayOpenInterest()).isEqualTo(100L);
        assertThat(tick.oiPctChange()).isEqualTo(48.0);
    }

    @Test
    @DisplayName("open interest percentage is zero when the previous OI is unknown")
    void testOiPctChangeUnknownPrev() {
        FeedTick tick = new FeedTick("NFO", "12345");
        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NFO\",\"tk\":\"12345\",\"lp\":\"512.00\",\"oi\":\"148\"}"));

        assertThat(tick.oiPctChange()).isZero();
    }

    @Test
    @DisplayName(
            "percentage change is zero when the previous close is unknown rather than infinite")
    void testPctChangeUnknownPrevClose() {
        FeedTick tick = new FeedTick("NSE", "2885");
        tick.applyFull(frame("{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\"}"));

        assertThat(tick.pctChange()).isZero();
    }

    @Test
    @DisplayName("freshness is measured against the local receive time")
    void testIsFresh() throws InterruptedException {
        FeedTick tick = new FeedTick("NSE", "2885");
        assertThat(tick.isFresh(Duration.ofSeconds(5))).isFalse();

        tick.applyFull(frame("{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\"}"));
        assertThat(tick.isFresh(Duration.ofSeconds(5))).isTrue();
        assertThat(tick.isFresh(Duration.ZERO)).isTrue();

        Thread.sleep(20);
        assertThat(tick.isFresh(Duration.ofMillis(5))).isFalse();
    }

    @Test
    @DisplayName("the feed timestamp is captured so callers can measure staleness")
    void testFeedTimeIsCaptured() {
        FeedTick tick = new FeedTick("NSE", "2885");

        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\",\"ft\":\"1760000000\"}"));

        assertThat(tick.feedTime()).isEqualTo(Instant.ofEpochSecond(1760000000L));
        assertThat(tick.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("snapshot conversion mirrors the GetQuotes field mapping exactly")
    void testToSnapshot() {
        FeedTick tick = new FeedTick("NSE", "2885");
        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\",\"c\":\"1400.00\","
                                + "\"o\":\"1410.00\",\"ap\":\"1430.00\",\"v\":\"250000\"}"));

        StockQuoteSnapshot snapshot = tick.toSnapshot("DMART");

        assertThat(snapshot.symbol()).isEqualTo("DMART");
        assertThat(snapshot.ltp()).isEqualTo(1449.00);
        assertThat(snapshot.prevClose()).isEqualTo(1400.00);
        assertThat(snapshot.open()).isEqualTo(1410.00);
        assertThat(snapshot.pctChange()).isEqualTo(3.5, within(1e-9));
        assertThat(snapshot.volume()).isEqualTo(250000L);
        assertThat(snapshot.vwap()).isEqualTo(1430.00);
    }

    @Test
    @DisplayName("open falls back to the last price when the gateway omits it")
    void testToSnapshotOpenFallback() {
        FeedTick tick = new FeedTick("NSE", "2885");
        tick.applyFull(
                frame(
                        "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"1449.00\",\"c\":\"1400.00\"}"));

        assertThat(tick.toSnapshot("DMART").open()).isEqualTo(1449.00);
    }

    @Test
    @DisplayName("no snapshot is produced before a price has been received")
    void testToSnapshotWithoutPrice() {
        assertThat(new FeedTick("NSE", "2885").toSnapshot("DMART")).isNull();
        assertThat(new FeedTick("NSE", "2885").toSnapshot(null)).isNull();
    }

    @Test
    @DisplayName("identity is reported as the canonical subscription key")
    void testKey() {
        FeedTick tick = new FeedTick("NSE", "26000");

        assertThat(tick.key()).isEqualTo("NSE|26000");
        assertThat(tick.exchange()).isEqualTo("NSE");
        assertThat(tick.token()).isEqualTo("26000");
    }
}

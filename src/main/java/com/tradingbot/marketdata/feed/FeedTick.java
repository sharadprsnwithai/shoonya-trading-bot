package com.tradingbot.marketdata.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.time.Duration;
import java.time.Instant;

/**
 * Mutable, thread-safe accumulator of the streamed state for a single instrument.
 *
 * <p>Identity fields are fixed at construction; quote fields are updated by the feed's single
 * listener thread and read concurrently by scanners and the live-price loop, so every mutator is
 * {@code synchronized} while every accessor reads a {@code volatile} field directly and takes no
 * lock.
 *
 * <h2>Full snapshot versus diff</h2>
 *
 * <p>A {@code tk} frame carries the complete state and overwrites every field it names. A {@code
 * tf} frame carries only the fields that changed and must merge onto the existing state. Applying a
 * diff as if it were a full snapshot would zero the previous close, and a zero previous close
 * silently destroys the advance/decline breadth calculation that gates the whole trading day — so a
 * diff is refused until a full snapshot has established the baseline.
 *
 * @author trading-bot
 * @since 1.0
 */
public final class FeedTick {

    private final String exchange;
    private final String token;

    private volatile double lastPrice;
    private volatile double prevClose;
    private volatile double open;
    private volatile double high;
    private volatile double low;
    private volatile double avgPrice;
    private volatile double bid1;
    private volatile double ask1;
    private volatile long volume;
    private volatile long openInterest;
    private volatile long prevDayOpenInterest;
    private volatile long feedTimeEpochSeconds;

    /** Wall-clock instant the frame was merged locally. */
    private volatile Instant receivedAt;

    /** True once a full snapshot has established the baseline. */
    private volatile boolean established;

    /**
     * Creates a tick holder with no state yet.
     *
     * @param exchange exchange code, e.g. {@code NSE}
     * @param token instrument token
     */
    public FeedTick(String exchange, String token) {
        this.exchange = exchange;
        this.token = token;
        this.receivedAt = null;
    }

    /**
     * Applies a full snapshot frame, establishing or refreshing the complete state.
     *
     * <p>Validation happens before the existing state is cleared: a frame that carries the
     * instrument identity but no usable price must not destroy the quote already held, otherwise a
     * single malformed snapshot would blank out a live symbol mid-scan.
     *
     * @param node the {@code tk} frame
     * @return true when the frame carried a usable last price
     */
    public synchronized boolean applyFull(JsonNode node) {
        if (!hasUsablePrice(node)) {
            return false;
        }
        clearState();
        applyFields(node);
        established = true;
        return true;
    }

    /**
     * Applies an incremental diff frame onto the existing state.
     *
     * @param node the {@code tf} frame
     * @return true when the diff merged, false when it was refused as out of order
     */
    public synchronized boolean applyDiff(JsonNode node) {
        if (!established || !hasUsablePrice(node)) {
            // A diff before any snapshot has no baseline to merge onto. Dropping it is safe: the
            // next tk re-establishes complete state, whereas inventing one would fabricate a
            // previous close.
            return false;
        }
        applyFields(node);
        return true;
    }

    /**
     * Reports whether a frame carries a price the feed can act on. The gateway emits
     * heartbeat-shaped frames and out-of-book rejections that carry the instrument identity but no
     * price; merging those would leave a hollow quote.
     *
     * @param node the frame
     * @return true when a positive last price is present
     */
    private static boolean hasUsablePrice(JsonNode node) {
        return node != null && ShoonyaFeedProtocol.doubleField(node, "lp", 0.0) > 0.0;
    }

    /**
     * Merges the fields the frame names.
     *
     * @param node the frame; must have passed {@link #hasUsablePrice(JsonNode)}
     */
    private void applyFields(JsonNode node) {
        this.lastPrice = readDouble(node, "lp", this.lastPrice);
        this.prevClose = readDouble(node, "c", this.prevClose);
        this.open = readDouble(node, "o", this.open);
        this.high = readDouble(node, "h", this.high);
        this.low = readDouble(node, "l", this.low);
        this.avgPrice = readDouble(node, "ap", this.avgPrice);
        this.bid1 = readDouble(node, "bp1", this.bid1);
        this.ask1 = readDouble(node, "sp1", this.ask1);
        this.volume = readLong(node, "v", this.volume);
        this.openInterest = readLong(node, "oi", this.openInterest);
        this.prevDayOpenInterest = readLong(node, "poi", this.prevDayOpenInterest);

        Instant feedTime = ShoonyaFeedProtocol.feedTime(node);
        this.feedTimeEpochSeconds =
                feedTime == null ? this.feedTimeEpochSeconds : feedTime.getEpochSecond();
        this.receivedAt = Instant.now();
    }

    /** Blanks every quote field so a partial full-snapshot cannot leak the previous values. */
    private void clearState() {
        this.lastPrice = 0.0;
        this.prevClose = 0.0;
        this.open = 0.0;
        this.high = 0.0;
        this.low = 0.0;
        this.avgPrice = 0.0;
        this.bid1 = 0.0;
        this.ask1 = 0.0;
        this.volume = 0L;
        this.openInterest = 0L;
        this.prevDayOpenInterest = 0L;
        this.feedTimeEpochSeconds = 0L;
    }

    /**
     * @param node the frame
     * @param field the field name
     * @param fallback value used when the field is absent
     * @return the parsed double
     */
    private static double readDouble(JsonNode node, String field, double fallback) {
        return ShoonyaFeedProtocol.doubleField(node, field, fallback);
    }

    /**
     * @param node the frame
     * @param field the field name
     * @param fallback value used when the field is absent
     * @return the parsed long
     */
    private static long readLong(JsonNode node, String field, long fallback) {
        return ShoonyaFeedProtocol.longField(node, field, fallback);
    }

    /**
     * @return the exchange code
     */
    public String exchange() {
        return exchange;
    }

    /**
     * @return the instrument token
     */
    public String token() {
        return token;
    }

    /**
     * @return the {@code EXCHANGE|TOKEN} subscription key
     */
    public String key() {
        return ShoonyaFeedProtocol.instrumentKey(exchange, token);
    }

    /**
     * @return true when a full snapshot has established the baseline
     */
    public boolean isEstablished() {
        return established;
    }

    /**
     * @return last traded price, or 0 when none has been received
     */
    public double lastPrice() {
        return lastPrice;
    }

    /**
     * @return previous close, or 0 when none has been received
     */
    public double prevClose() {
        return prevClose;
    }

    /**
     * @return session open
     */
    public double open() {
        return open;
    }

    /**
     * @return session high
     */
    public double high() {
        return high;
    }

    /**
     * @return session low
     */
    public double low() {
        return low;
    }

    /**
     * @return gateway average traded price, an approximation of session VWAP
     */
    public double avgPrice() {
        return avgPrice;
    }

    /**
     * @return best bid
     */
    public double bid1() {
        return bid1;
    }

    /**
     * @return best ask
     */
    public double ask1() {
        return ask1;
    }

    /**
     * @return cumulative traded volume
     */
    public long volume() {
        return volume;
    }

    /**
     * @return open interest; absent on cash-equity instruments
     */
    public long openInterest() {
        return openInterest;
    }

    /**
     * @return previous open interest; absent on cash-equity instruments
     */
    public long prevDayOpenInterest() {
        return prevDayOpenInterest;
    }

    /**
     * @return instant this state was last merged locally
     */
    public Instant receivedAt() {
        return receivedAt;
    }

    /**
     * @return instant the exchange reported, or null when the frame carried none
     */
    public Instant feedTime() {
        return feedTimeEpochSeconds <= 0 ? null : Instant.ofEpochSecond(feedTimeEpochSeconds);
    }

    /**
     * @return percent change against the previous close, or 0 when the previous close is unknown
     */
    public double pctChange() {
        double prev = this.prevClose;
        if (prev <= 0.0) {
            return 0.0;
        }
        return (lastPrice - prev) / prev * 100.0;
    }

    /**
     * @return percent change in open interest, or 0 when the previous OI is unknown
     */
    public double oiPctChange() {
        if (prevDayOpenInterest <= 0L) {
            return 0.0;
        }
        return ((openInterest - prevDayOpenInterest) / (double) prevDayOpenInterest) * 100.0;
    }

    /**
     * Reports whether the tick is fresh enough to act on.
     *
     * @param maxAge the maximum acceptable age; non-positive ages are always satisfied
     * @return true when a snapshot has been received within {@code maxAge}
     */
    public boolean isFresh(Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return true;
        }
        Instant at = this.receivedAt;
        return at != null && at.plus(maxAge).isAfter(Instant.now());
    }

    /**
     * Converts this tick into the snapshot shape the scanners already consume.
     *
     * <p>The field mapping mirrors the {@code GetQuotes} path exactly — previous close from {@code
     * c}, percentage computed from {@code (lp - c) / c} — so a symbol sourced from the feed and a
     * symbol sourced from REST are indistinguishable downstream.
     *
     * @param symbol the trading symbol the snapshot is reported under
     * @return the snapshot, or null when no last price has been received
     */
    public StockQuoteSnapshot toSnapshot(String symbol) {
        if (symbol == null || lastPrice <= 0.0) {
            return null;
        }
        return new StockQuoteSnapshot(
                symbol,
                lastPrice,
                prevClose,
                open > 0.0 ? open : lastPrice,
                pctChange(),
                volume,
                avgPrice,
                openInterest,
                prevDayOpenInterest);
    }
}

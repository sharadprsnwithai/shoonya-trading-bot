package com.tradingbot.strategy.bollingerha.feeder;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Real-time OHLC tick accumulator that rolls up to a fixed N-minute bucket. */
public class BollingerHaCandleBuilder {

    private static final Logger log = LoggerFactory.getLogger(BollingerHaCandleBuilder.class);

    private final String token;
    private final String symbol;
    private final String optionType;
    private final int timeframeMinutes;
    private final Consumer<CompletedCandleEvent> candleConsumer;

    private Instant currentBucket;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    public BollingerHaCandleBuilder(
            String token,
            String symbol,
            String optionType,
            Consumer<CompletedCandleEvent> candleConsumer) {
        this(token, symbol, optionType, 1, candleConsumer);
    }

    /**
     * @param timeframeMinutes bucket width in minutes; a tick at 09:17:xx belongs to the 09:15
     *     bucket when this is 3
     */
    public BollingerHaCandleBuilder(
            String token,
            String symbol,
            String optionType,
            int timeframeMinutes,
            Consumer<CompletedCandleEvent> candleConsumer) {
        this.token = token;
        this.symbol = symbol;
        this.optionType = optionType;
        this.timeframeMinutes = timeframeMinutes > 0 ? timeframeMinutes : 1;
        this.candleConsumer = candleConsumer;
    }

    /**
     * Processes an incoming price tick.
     *
     * @param price Current traded price
     * @param tickVolume Volume delta since the previous tick for this contract
     * @param timestamp Timestamp of the tick
     */
    public synchronized void onTick(BigDecimal price, long tickVolume, Instant timestamp) {
        if (price == null || timestamp == null) {
            return;
        }

        Instant bucket = bucketFor(timestamp);

        if (currentBucket == null) {
            startBucket(bucket, price, tickVolume);
        } else if (bucket.isAfter(currentBucket)) {
            // Bucket boundary crossed: finalize the previous candle
            emitCurrentCandle(timestamp);
            startBucket(bucket, price, tickVolume);
        } else if (bucket.isBefore(currentBucket)) {
            // D9: late/out-of-order tick — a delayed frame must not mutate the candle that is
            // already being built, otherwise the OHLC window gets a price from the past.
            log.debug(
                    "[CANDLE-BUILDER] Ignoring out-of-order tick for {} (bucket {} < current {})",
                    symbol,
                    bucket,
                    currentBucket);
        } else {
            // Update ongoing candle
            if (price.compareTo(high) > 0) {
                high = price;
            }
            if (price.compareTo(low) < 0) {
                low = price;
            }
            close = price;
            volume += tickVolume;
        }
    }

    /** Rounds an instant down to the start of its N-minute bucket. */
    private Instant bucketFor(Instant timestamp) {
        long secondsPerBucket = timeframeMinutes * 60L;
        long bucketEpoch = Math.floorDiv(timestamp.getEpochSecond(), secondsPerBucket);
        return Instant.ofEpochSecond(bucketEpoch * secondsPerBucket);
    }

    private void startBucket(Instant bucket, BigDecimal price, long tickVolume) {
        currentBucket = bucket;
        open = price;
        high = price;
        low = price;
        close = price;
        volume = tickVolume;
    }

    /** Manually completes the current in-progress candle (e.g. at market cutoff). */
    public synchronized void flush(Instant completionTime) {
        if (currentBucket != null) {
            emitCurrentCandle(completionTime != null ? completionTime : Instant.now());
            currentBucket = null;
        }
    }

    private void emitCurrentCandle(Instant completedAt) {
        Candle candle =
                new Candle(
                        symbol,
                        String.valueOf(timeframeMinutes),
                        currentBucket,
                        open,
                        high,
                        low,
                        close,
                        volume);
        log.debug(
                "[CANDLE-BUILDER] Completed {}m candle for {}: O={} H={} L={} C={} V={}",
                timeframeMinutes,
                symbol,
                open,
                high,
                low,
                close,
                volume);
        if (candleConsumer != null) {
            candleConsumer.accept(
                    new CompletedCandleEvent(token, symbol, optionType, candle, completedAt));
        }
    }

    public int getTimeframeMinutes() {
        return timeframeMinutes;
    }

    public String getToken() {
        return token;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getOptionType() {
        return optionType;
    }
}

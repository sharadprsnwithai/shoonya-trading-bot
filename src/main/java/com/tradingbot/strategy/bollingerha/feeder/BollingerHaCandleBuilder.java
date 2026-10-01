package com.tradingbot.strategy.bollingerha.feeder;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Real-time 1-minute OHLC tick accumulator for an option contract. */
public class BollingerHaCandleBuilder {

    private static final Logger log = LoggerFactory.getLogger(BollingerHaCandleBuilder.class);

    private final String token;
    private final String symbol;
    private final String optionType;
    private final Consumer<CompletedCandleEvent> candleConsumer;

    private Instant currentMinuteBucket;
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
        this.token = token;
        this.symbol = symbol;
        this.optionType = optionType;
        this.candleConsumer = candleConsumer;
    }

    /**
     * Processes an incoming price tick.
     *
     * @param price Current traded price
     * @param tickVolume Volume delta or tick volume
     * @param timestamp Timestamp of the tick
     */
    public synchronized void onTick(BigDecimal price, long tickVolume, Instant timestamp) {
        if (price == null || timestamp == null) {
            return;
        }

        Instant minuteBucket = timestamp.truncatedTo(ChronoUnit.MINUTES);

        if (currentMinuteBucket == null) {
            // First tick initialization
            currentMinuteBucket = minuteBucket;
            open = price;
            high = price;
            low = price;
            close = price;
            volume = tickVolume;
        } else if (minuteBucket.isAfter(currentMinuteBucket)) {
            // Minute boundary crossed: finalize previous candle
            emitCurrentCandle(timestamp);

            // Start new candle
            currentMinuteBucket = minuteBucket;
            open = price;
            high = price;
            low = price;
            close = price;
            volume = tickVolume;
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

    /** Manually completes the current in-progress candle (e.g. at market cutoff). */
    public synchronized void flush(Instant completionTime) {
        if (currentMinuteBucket != null) {
            emitCurrentCandle(completionTime != null ? completionTime : Instant.now());
            currentMinuteBucket = null;
        }
    }

    private void emitCurrentCandle(Instant completedAt) {
        Candle candle =
                new Candle(symbol, "1", currentMinuteBucket, open, high, low, close, volume);
        log.debug(
                "[CANDLE-BUILDER] Completed 1m candle for {}: O={} H={} L={} C={} V={}",
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

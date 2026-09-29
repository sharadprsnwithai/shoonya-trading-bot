package com.tradingbot.strategy.car;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Computes weekly GTT trigger price (max high of completed week), limit price (+0.10 buffer), and
 * ceil lot sizing.
 */
@Component
public class CarWeeklyTriggerGenerator {

    private final BigDecimal triggerBuffer;

    public record TriggerCalculation(
            String symbol, BigDecimal triggerPrice, BigDecimal limitPrice, int quantity) {}

    public CarWeeklyTriggerGenerator() {
        this(new BigDecimal("0.10"));
    }

    public CarWeeklyTriggerGenerator(BigDecimal triggerBuffer) {
        this.triggerBuffer = triggerBuffer != null ? triggerBuffer : new BigDecimal("0.10");
    }

    public TriggerCalculation calculateTrigger(
            String symbol, List<Candle> previousWeekCandles, BigDecimal unitSize) {
        if (previousWeekCandles == null || previousWeekCandles.isEmpty()) {
            return new TriggerCalculation(symbol, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        BigDecimal weeklyHigh = BigDecimal.ZERO;
        for (Candle c : previousWeekCandles) {
            if (c.high() != null && c.high().compareTo(weeklyHigh) > 0) {
                weeklyHigh = c.high();
            }
        }

        BigDecimal triggerPrice = weeklyHigh.setScale(2, RoundingMode.HALF_UP);
        BigDecimal rawLimit = triggerPrice.add(triggerBuffer);
        // Round limit to nearest 0.05 NSE tick
        BigDecimal limitPrice = roundToTick(rawLimit, 0.05);

        int quantity = 1;
        if (unitSize != null
                && unitSize.compareTo(BigDecimal.ZERO) > 0
                && triggerPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rawQty = unitSize.divide(triggerPrice, 4, RoundingMode.HALF_UP);
            quantity = (int) Math.ceil(rawQty.doubleValue());
            if (quantity <= 0) quantity = 1;
        }

        return new TriggerCalculation(symbol, triggerPrice, limitPrice, quantity);
    }

    private BigDecimal roundToTick(BigDecimal val, double tickSize) {
        double rounded = Math.round(val.doubleValue() / tickSize) * tickSize;
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
    }
}

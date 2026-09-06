package com.tradingbot.model.indicator;

import java.util.List;

/** REST response payload containing technical analysis indicator series. */
public record IndicatorResponse(
        String symbol,
        String interval,
        int count,
        IndicatorSnapshot latest,
        List<IndicatorSnapshot> data) {
    public static IndicatorResponse of(
            String symbol, String interval, List<IndicatorSnapshot> data) {
        IndicatorSnapshot latest =
                (data != null && !data.isEmpty()) ? data.get(data.size() - 1) : null;
        int count = (data != null) ? data.size() : 0;
        return new IndicatorResponse(symbol, interval, count, latest, data);
    }
}

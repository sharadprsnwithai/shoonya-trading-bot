package com.tradingbot.strategy.car.gtt;

import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttStatus;
import java.util.List;

public interface GttExecutionGateway {
    String getBrokerName();

    /**
     * Places a GTT and returns the broker-side trigger id, or {@code null} when placement failed.
     * Implementations must never return an empty or blank id.
     */
    String placeGtt(CarGttOrder order);

    /**
     * Modifies an existing trigger in place where the broker supports it, otherwise falls back to
     * cancel-then-place.
     *
     * @return the effective trigger id after the operation (unchanged id when modified in place,
     *     new id when recreated) or {@code null} when the operation failed. Callers must keep the
     *     previous state entry untouched when {@code null} is returned so the next run can retry.
     */
    String modifyGtt(String gttId, CarGttOrder newOrder);

    boolean cancelGtt(String gttId);

    GttStatus getGttStatus(String gttId);

    /**
     * Lists the triggers currently active on this broker. Gateways that have no server-side trigger
     * view (paper watcher) return an empty list.
     */
    default List<LiveGttTrigger> listActiveGtts() {
        return List.of();
    }

    /**
     * Reads the fill of a trigger that has already fired.
     *
     * @return executed quantity and average price, or {@code null} when the fill cannot be
     *     determined (unknown id, broker error, partial read)
     */
    default GttFill getTriggerFill(String gttId) {
        return null;
    }

    java.util.List<com.tradingbot.model.execution.BrokerPosition> getHoldings();
}

package com.tradingbot.strategy.car.gtt;

import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttStatus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Emulated local GTT watcher for Shoonya and paper trading mode. */
@Component
public class LocalGttWatcherGateway implements GttExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalGttWatcherGateway.class);
    static final String PREFIX = "LOCAL_GTT_";
    private final Map<String, CarGttOrder> activeWatchers = new ConcurrentHashMap<>();

    @Override
    public String getBrokerName() {
        return "SHOONYA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        String id = PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        activeWatchers.put(id, order);
        log.info("[LOCAL-GTT] Registered virtual GTT watcher for {} (ID: {})", order.symbol(), id);
        return id;
    }

    @Override
    public String modifyGtt(String gttId, CarGttOrder newOrder) {
        if (gttId == null || gttId.isBlank()) {
            return placeGtt(newOrder);
        }
        activeWatchers.put(gttId, newOrder);
        return gttId;
    }

    @Override
    public boolean cancelGtt(String gttId) {
        if (gttId == null) return false;
        activeWatchers.remove(gttId);
        return true;
    }

    @Override
    public GttStatus getGttStatus(String gttId) {
        if (gttId == null || gttId.isBlank()) {
            return null;
        }
        if (activeWatchers.containsKey(gttId)) {
            return GttStatus.PENDING;
        }
        // The watcher is in-memory, but the persisted portfolio state is the source of truth for
        // ids it minted itself - otherwise every restart would report all paper triggers as
        // cancelled and silently drop them.
        if (gttId.startsWith(PREFIX)) {
            return GttStatus.PENDING;
        }
        return GttStatus.CANCELLED;
    }

    @Override
    public java.util.List<com.tradingbot.model.execution.BrokerPosition> getHoldings() {
        return java.util.List.of();
    }
}

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
    private final Map<String, CarGttOrder> activeWatchers = new ConcurrentHashMap<>();

    @Override
    public String getBrokerName() {
        return "SHOONYA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        String id = "LOCAL_GTT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        activeWatchers.put(id, order);
        log.info("[LOCAL-GTT] Registered virtual GTT watcher for {} (ID: {})", order.symbol(), id);
        return id;
    }

    @Override
    public boolean modifyGtt(String gttId, CarGttOrder newOrder) {
        if (gttId == null) return false;
        activeWatchers.put(gttId, newOrder);
        return true;
    }

    @Override
    public boolean cancelGtt(String gttId) {
        if (gttId == null) return false;
        activeWatchers.remove(gttId);
        return true;
    }

    @Override
    public GttStatus getGttStatus(String gttId) {
        return activeWatchers.containsKey(gttId) ? GttStatus.PENDING : GttStatus.CANCELLED;
    }
}

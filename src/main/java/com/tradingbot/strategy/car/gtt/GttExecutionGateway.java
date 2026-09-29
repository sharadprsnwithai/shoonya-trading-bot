package com.tradingbot.strategy.car.gtt;

import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttStatus;

public interface GttExecutionGateway {
    String getBrokerName();

    String placeGtt(CarGttOrder order);

    boolean modifyGtt(String gttId, CarGttOrder newOrder);

    boolean cancelGtt(String gttId);

    GttStatus getGttStatus(String gttId);

    java.util.List<com.tradingbot.model.execution.BrokerPosition> getHoldings();
}

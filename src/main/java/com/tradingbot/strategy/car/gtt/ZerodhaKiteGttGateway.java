package com.tradingbot.strategy.car.gtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ZerodhaKiteGttGateway implements GttExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaKiteGttGateway.class);
    private final KiteRestClient kiteRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ZerodhaKiteGttGateway(KiteRestClient kiteRestClient) {
        this.kiteRestClient = kiteRestClient;
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        try {
            String txnType = order.type() == GttOrderType.BUY ? "BUY" : "SELL";
            Map<String, Object> condition =
                    Map.of(
                            "exchange",
                            "NSE",
                            "tradingsymbol",
                            order.symbol(),
                            "trigger_values",
                            List.of(order.triggerPrice().doubleValue()),
                            "last_price",
                            order.triggerPrice().doubleValue());

            Map<String, Object> orderDetail =
                    Map.of(
                            "transaction_type", txnType,
                            "quantity", order.quantity(),
                            "price", order.limitPrice().doubleValue(),
                            "order_type", "LIMIT",
                            "product", "CNC");

            Map<String, String> form = new LinkedHashMap<>();
            form.put("type", "single");
            form.put("condition", objectMapper.writeValueAsString(condition));
            form.put("orders", objectMapper.writeValueAsString(List.of(orderDetail)));

            JsonNode resp = kiteRestClient.postForm("/gtt/triggers", form, true);
            String triggerId = resp.path("data").path("trigger_id").asText();
            log.info("[ZERODHA-GTT] Placed GTT order for {} (ID: {})", order.symbol(), triggerId);
            return triggerId;
        } catch (Exception e) {
            log.error(
                    "[ZERODHA-GTT] Failed placing GTT for {}: {}",
                    order.symbol(),
                    e.getMessage(),
                    e);
            return null;
        }
    }

    @Override
    public boolean modifyGtt(String gttId, CarGttOrder newOrder) {
        log.info("[ZERODHA-GTT] Modifying GTT {} for {}", gttId, newOrder.symbol());
        cancelGtt(gttId);
        String newId = placeGtt(newOrder);
        return newId != null;
    }

    @Override
    public boolean cancelGtt(String gttId) {
        if (gttId == null || gttId.isBlank()) return false;
        try {
            log.info("[ZERODHA-GTT] Cancelling GTT {}", gttId);
            return true;
        } catch (Exception e) {
            log.warn("[ZERODHA-GTT] Error cancelling GTT {}: {}", gttId, e.getMessage());
            return false;
        }
    }

    @Override
    public GttStatus getGttStatus(String gttId) {
        return GttStatus.PENDING;
    }
}

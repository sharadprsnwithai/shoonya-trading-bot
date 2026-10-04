package com.tradingbot.strategy.car.gtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ZerodhaKiteGttGateway implements GttExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaKiteGttGateway.class);
    private final KiteRestClient kiteRestClient;
    private final KiteAuthService kiteAuthService;
    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Autowired
    public ZerodhaKiteGttGateway(
            KiteRestClient kiteRestClient,
            @Autowired(required = false) KiteAuthService kiteAuthService) {
        this.kiteRestClient = kiteRestClient;
        this.kiteAuthService = kiteAuthService;
    }

    public ZerodhaKiteGttGateway(KiteRestClient kiteRestClient) {
        this(kiteRestClient, null);
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
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
                                "transaction_type",
                                txnType,
                                "quantity",
                                order.quantity(),
                                "price",
                                order.limitPrice().doubleValue(),
                                "order_type",
                                "LIMIT",
                                "product",
                                "CNC");

                Map<String, String> form = new LinkedHashMap<>();
                form.put("type", "single");
                form.put("condition", objectMapper.writeValueAsString(condition));
                form.put("orders", objectMapper.writeValueAsString(List.of(orderDetail)));

                JsonNode resp = kiteRestClient.postForm("/gtt/triggers", form, true);
                String triggerId = resp.path("data").path("trigger_id").asText();
                log.info(
                        "[ZERODHA-GTT] Placed GTT order for {} (ID: {})",
                        order.symbol(),
                        triggerId);
                return triggerId;
            } catch (Exception e) {
                if (attempt < maxAttempts && isTokenException(e) && kiteAuthService != null) {
                    log.warn(
                            "[ZERODHA-GTT] Kite session invalid or expired placing GTT for {} (attempt {}). Re-authenticating...",
                            order.symbol(),
                            attempt);
                    if (kiteAuthService.reAuthenticate()) {
                        continue;
                    }
                }
                log.error(
                        "[ZERODHA-GTT] Failed placing GTT for {}: {}",
                        order.symbol(),
                        e.getMessage(),
                        e);
                return null;
            }
        }
        return null;
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

    @Override
    public List<com.tradingbot.model.execution.BrokerPosition> getHoldings() {
        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<com.tradingbot.model.execution.BrokerPosition> holdings = new ArrayList<>();
            try {
                JsonNode data = kiteRestClient.holdings();
                if (data != null && data.isArray()) {
                    for (JsonNode row : data) {
                        long qty = row.path("quantity").asLong(0L);
                        if (qty <= 0L) continue;
                        String tsym = row.path("tradingsymbol").asText("");
                        String exch = row.path("exchange").asText("NSE");
                        double avgPrice = row.path("average_price").asDouble(0.0);
                        double lastPrice = row.path("last_price").asDouble(0.0);
                        double pnl = row.path("pnl").asDouble(0.0);

                        holdings.add(
                                com.tradingbot.model.execution.BrokerPosition.of(
                                        "ZERODHA",
                                        exch,
                                        tsym,
                                        tsym,
                                        "CNC",
                                        qty,
                                        java.math.BigDecimal.valueOf(avgPrice),
                                        java.math.BigDecimal.valueOf(lastPrice),
                                        java.math.BigDecimal.valueOf(pnl),
                                        java.math.BigDecimal.ZERO));
                    }
                }
                return holdings;
            } catch (Exception e) {
                if (attempt < maxAttempts && isTokenException(e) && kiteAuthService != null) {
                    log.warn(
                            "[ZERODHA-GTT] Kite session invalid or expired fetching holdings (attempt {}). Re-authenticating...",
                            attempt);
                    if (kiteAuthService.reAuthenticate()) {
                        continue;
                    }
                }
                log.error("[ZERODHA-GTT] Error fetching Kite holdings: {}", e.getMessage(), e);
                return holdings;
            }
        }
        return List.of();
    }

    private boolean isTokenException(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("tokenexception")
                    || lower.contains("403")
                    || lower.contains("401")
                    || lower.contains("access_token")
                    || lower.contains("api_key")
                    || lower.contains("incorrect `api_key` or `access_token`")) {
                return true;
            }
        }
        return isTokenException(t.getCause());
    }
}

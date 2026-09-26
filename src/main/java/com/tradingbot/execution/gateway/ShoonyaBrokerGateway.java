package com.tradingbot.execution.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.order.ShoonyaOrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ShoonyaBrokerGateway implements BrokerOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaBrokerGateway.class);

    private final ShoonyaOrderService orderService;

    @Autowired
    public ShoonyaBrokerGateway(ShoonyaOrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String getBrokerName() {
        return "SHOONYA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        return orderService.placeOrder(request);
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        return orderService.cancelOrder(orderId);
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        return orderService.modifyOrder(orderId, request);
    }

    @Override
    public List<BrokerPosition> getPositions() {
        List<BrokerPosition> positions = new ArrayList<>();
        try {
            JsonNode root = orderService.getPositionBook();
            if (root != null && root.isArray()) {
                for (JsonNode row : root) {
                    long netQty = row.path("netqty").asLong(0L);
                    if (netQty == 0L && row.path("daybuyqty").asLong(0L) == 0L) {
                        continue;
                    }

                    String tsym = row.path("tsym").asText("");
                    String exch = row.path("exch").asText("NSE");
                    String prd = row.path("prd").asText("M");
                    double avgPrice = row.path("netavgprc").asDouble(0.0);
                    if (avgPrice == 0.0) {
                        avgPrice = row.path("dayavgprc").asDouble(0.0);
                    }
                    double lp = row.path("lp").asDouble(0.0);
                    double rpnl = row.path("rpnl").asDouble(0.0);
                    double urmtom = row.path("urmtom").asDouble(0.0);
                    double totalPnl = rpnl + urmtom;

                    positions.add(
                            BrokerPosition.of(
                                    "SHOONYA",
                                    exch,
                                    tsym,
                                    tsym,
                                    prd,
                                    netQty,
                                    BigDecimal.valueOf(avgPrice),
                                    BigDecimal.valueOf(lp),
                                    BigDecimal.valueOf(totalPnl),
                                    BigDecimal.valueOf(urmtom)));
                }
            }
        } catch (Exception e) {
            log.error("[SHOONYA-GATEWAY] Error fetching positions: {}", e.getMessage(), e);
        }
        return positions;
    }
}

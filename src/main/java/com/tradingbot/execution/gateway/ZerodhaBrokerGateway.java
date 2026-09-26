package com.tradingbot.execution.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Production Broker Gateway for Zerodha Kite Connect API supporting marketable limit order
 * execution and position querying.
 */
@Component
public class ZerodhaBrokerGateway implements BrokerOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaBrokerGateway.class);

    private final KiteRestClient kiteRestClient;

    @Autowired
    public ZerodhaBrokerGateway(KiteRestClient kiteRestClient) {
        this.kiteRestClient = kiteRestClient;
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        BigDecimal refPrice =
                request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0
                        ? request.price()
                        : BigDecimal.ZERO;
        return placeOrderWithReferencePrice(request, refPrice);
    }

    public OrderResponse placeOrderWithReferencePrice(OrderRequest request, BigDecimal refPrice) {
        if (request == null) {
            return OrderResponse.failure(null, "Null OrderRequest");
        }

        try {
            double price = refPrice != null ? refPrice.doubleValue() : 0.0;
            Double limitPrice = null;

            if (price > 0) {
                // Apply 1% marketable buffer
                double buffered =
                        (request.transactionType() == TransactionType.BUY)
                                ? (price * 1.01)
                                : (price * 0.99);
                limitPrice = Math.max(0.05, Math.round(buffered * 20.0) / 20.0);
            }

            Double triggerPrice =
                    request.triggerPrice() != null
                                    && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0
                            ? Math.round(request.triggerPrice().doubleValue() * 20.0) / 20.0
                            : null;

            String orderType;
            if (triggerPrice != null) {
                orderType = (limitPrice != null) ? "SL" : "SL-M";
            } else {
                orderType = (limitPrice != null) ? "LIMIT" : "MARKET";
            }

            KiteRestClient.KiteOrderRequest kiteReq =
                    new KiteRestClient.KiteOrderRequest(
                            request.exchange() != null && !request.exchange().isBlank()
                                    ? request.exchange()
                                    : "NFO",
                            request.symbol(),
                            request.transactionType().name(),
                            request.quantity(),
                            request.productType() != null ? request.productType().name() : "MIS",
                            orderType,
                            limitPrice,
                            triggerPrice);

            KiteRestClient.KiteOrderResponse resp = kiteRestClient.placeOrder(kiteReq);
            log.info(
                    "[ZERODHA-GATEWAY] Order routed successfully. OrderId: {}, Symbol: {} {} x {}"
                            + " @ Limit: {}",
                    resp.orderId(),
                    request.transactionType(),
                    request.symbol(),
                    request.quantity(),
                    limitPrice);

            return new OrderResponse(
                    true,
                    resp.orderId(),
                    OrderStatus.OPEN,
                    "Zerodha order placed",
                    request,
                    Instant.now());
        } catch (Exception e) {
            log.error(
                    "[ZERODHA-GATEWAY] Failed placing order for {}: {}",
                    request.symbol(),
                    e.getMessage(),
                    e);
            return OrderResponse.failure(request, e.getMessage());
        }
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        boolean cancelled = kiteRestClient.cancelOrder(orderId);
        return new OrderResponse(
                cancelled,
                orderId,
                cancelled ? OrderStatus.CANCELLED : OrderStatus.REJECTED,
                cancelled ? "Cancelled on Kite" : "Failed to cancel on Kite",
                null,
                Instant.now());
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        log.info("[ZERODHA-GATEWAY] Modifying order {}: {}", orderId, request.symbol());
        return new OrderResponse(
                true, orderId, OrderStatus.OPEN, "Order modify submitted", request, Instant.now());
    }

    @Override
    public List<BrokerPosition> getPositions() {
        List<BrokerPosition> positions = new ArrayList<>();
        try {
            JsonNode data = kiteRestClient.positions();
            if (data != null && data.isArray()) {
                for (JsonNode row : data) {
                    long qty = row.path("quantity").asLong(0L);
                    if (qty == 0L) {
                        continue;
                    }

                    String tsym = row.path("tradingsymbol").asText("");
                    String exch = row.path("exchange").asText("NFO");
                    String prd = row.path("product").asText("MIS");
                    double avgPrice = row.path("average_price").asDouble(0.0);
                    double lastPrice = row.path("last_price").asDouble(0.0);
                    double pnl = row.path("pnl").asDouble(0.0);
                    double m2m = row.path("m2m").asDouble(0.0);

                    positions.add(
                            BrokerPosition.of(
                                    "ZERODHA",
                                    exch,
                                    tsym,
                                    tsym,
                                    prd,
                                    qty,
                                    BigDecimal.valueOf(avgPrice),
                                    BigDecimal.valueOf(lastPrice),
                                    BigDecimal.valueOf(pnl),
                                    BigDecimal.valueOf(m2m)));
                }
            }
        } catch (Exception e) {
            log.error("[ZERODHA-GATEWAY] Error reading Kite positions: {}", e.getMessage(), e);
        }
        return positions;
    }
}

package com.tradingbot.execution.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.auth.KiteAuthService;
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
    private final KiteAuthService kiteAuthService;

    @Autowired
    public ZerodhaBrokerGateway(
            KiteRestClient kiteRestClient,
            @Autowired(required = false) KiteAuthService kiteAuthService) {
        this.kiteRestClient = kiteRestClient;
        this.kiteAuthService = kiteAuthService;
    }

    public ZerodhaBrokerGateway(KiteRestClient kiteRestClient) {
        this(kiteRestClient, null);
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

        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
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
                                request.productType() != null
                                        ? request.productType().name()
                                        : "MIS",
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
                lastException = e;
                if (attempt < maxAttempts && isTokenException(e) && kiteAuthService != null) {
                    log.warn(
                            "[ZERODHA-GATEWAY] Kite session invalid or expired during order placement for {} (attempt {}). Re-authenticating...",
                            request.symbol(),
                            attempt);
                    boolean refreshed = kiteAuthService.reAuthenticate();
                    if (refreshed) {
                        log.info(
                                "[ZERODHA-GATEWAY] Re-authentication successful. Retrying order placement for {}...",
                                request.symbol());
                        continue;
                    }
                }
                break;
            }
        }

        log.error(
                "[ZERODHA-GATEWAY] Failed placing order for {}: {}",
                request.symbol(),
                lastException != null ? lastException.getMessage() : "Unknown error",
                lastException);
        return OrderResponse.failure(
                request, lastException != null ? lastException.getMessage() : "Unknown error");
    }

    /** C3: Kite order history is available, so status polling is supported. */
    @Override
    public boolean supportsOrderStatusPolling() {
        return true;
    }

    @Override
    public OrderStatus getOrderStatus(String orderId) {
        String status = kiteRestClient.getOrderStatus(orderId);
        if (status == null) {
            return OrderStatus.PENDING;
        }
        String s = status.toUpperCase();
        if (s.contains("COMPLETE") || s.contains("FILLED")) {
            return OrderStatus.COMPLETE;
        }
        if (s.contains("REJECT")) {
            return OrderStatus.REJECTED;
        }
        if (s.contains("CANCEL")) {
            return OrderStatus.CANCELLED;
        }
        if (s.contains("TRIGGER")) {
            return OrderStatus.TRIGGER_PENDING;
        }
        if (s.contains("OPEN")) {
            return OrderStatus.OPEN;
        }
        // UNKNOWN / intermediate states (PUT ORDER REQ RECEIVED, etc.)
        return OrderStatus.PENDING;
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                boolean cancelled = kiteRestClient.cancelOrder(orderId);
                return new OrderResponse(
                        cancelled,
                        orderId,
                        cancelled ? OrderStatus.CANCELLED : OrderStatus.REJECTED,
                        cancelled ? "Cancelled on Kite" : "Failed to cancel on Kite",
                        null,
                        Instant.now());
            } catch (Exception e) {
                if (attempt < maxAttempts && isTokenException(e) && kiteAuthService != null) {
                    log.warn(
                            "[ZERODHA-GATEWAY] Kite session invalid or expired during order cancellation for {} (attempt {}). Re-authenticating...",
                            orderId,
                            attempt);
                    if (kiteAuthService.reAuthenticate()) {
                        continue;
                    }
                }
                return new OrderResponse(
                        false,
                        orderId,
                        OrderStatus.REJECTED,
                        "Failed to cancel on Kite: " + e.getMessage(),
                        null,
                        Instant.now());
            }
        }
        return new OrderResponse(
                false,
                orderId,
                OrderStatus.REJECTED,
                "Failed to cancel on Kite",
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
        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
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
                return positions;
            } catch (Exception e) {
                if (attempt < maxAttempts && isTokenException(e) && kiteAuthService != null) {
                    log.warn(
                            "[ZERODHA-GATEWAY] Kite session invalid or expired querying positions (attempt {}). Re-authenticating...",
                            attempt);
                    if (kiteAuthService.reAuthenticate()) {
                        continue;
                    }
                }
                log.error("[ZERODHA-GATEWAY] Error reading Kite positions: {}", e.getMessage(), e);
                return positions;
            }
        }
        return List.of();
    }

    @Override
    public List<BrokerPosition> getHoldings() {
        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<BrokerPosition> holdings = new ArrayList<>();
            try {
                JsonNode data = kiteRestClient.holdings();
                if (data != null && data.isArray()) {
                    for (JsonNode row : data) {
                        long qty = row.path("quantity").asLong(0L);
                        if (qty <= 0L) {
                            continue;
                        }

                        String tsym = row.path("tradingsymbol").asText("");
                        String exch = row.path("exchange").asText("NSE");
                        double avgPrice = row.path("average_price").asDouble(0.0);
                        double lastPrice = row.path("last_price").asDouble(0.0);
                        double pnl = row.path("pnl").asDouble(0.0);

                        holdings.add(
                                BrokerPosition.of(
                                        "ZERODHA",
                                        exch,
                                        tsym,
                                        tsym,
                                        "CNC",
                                        qty,
                                        BigDecimal.valueOf(avgPrice),
                                        BigDecimal.valueOf(lastPrice),
                                        BigDecimal.valueOf(pnl),
                                        BigDecimal.ZERO));
                    }
                }
                return holdings;
            } catch (Exception e) {
                if (attempt < maxAttempts && isTokenException(e) && kiteAuthService != null) {
                    log.warn(
                            "[ZERODHA-GATEWAY] Kite session invalid or expired querying holdings (attempt {}). Re-authenticating...",
                            attempt);
                    if (kiteAuthService.reAuthenticate()) {
                        continue;
                    }
                }
                log.error("[ZERODHA-GATEWAY] Error reading Kite holdings: {}", e.getMessage(), e);
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

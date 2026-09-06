package com.tradingbot.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for live order routing, modification, cancellation, and book queries on Shoonya
 * (NorenAPI).
 */
@Service
public class ShoonyaOrderService {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaOrderService.class);

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public ShoonyaOrderService(ShoonyaConfig config, ShoonyaAuthenticator authenticator) {
        this(
                config,
                authenticator,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public ShoonyaOrderService(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Places a new order (Market, Limit, or SL-LMT) on Shoonya. */
    public OrderResponse placeOrder(OrderRequest request) {
        if (!config.isEnabled()) {
            String mockId = "MOCK_ORD_" + System.currentTimeMillis();
            log.info(
                    "[MOCK-ORDER] Broker disabled. Placed mock order {} for {} (Qty: {})",
                    mockId,
                    request.symbol(),
                    request.quantity());
            return OrderResponse.success(mockId, request, "Mock order placed successfully");
        }

        try {
            String sessionToken = authenticator.getOrAuthenticateToken();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("uid", config.getUserId());
            payload.put("actid", config.getUserId());
            payload.put("exch", request.exchange() != null ? request.exchange() : "NFO");
            payload.put("tsym", request.symbol());
            payload.put("qty", String.valueOf(request.quantity()));
            payload.put(
                    "prd", request.productType() != null ? request.productType().getCode() : "M");
            payload.put("trantype", request.transactionType().getCode());
            payload.put("prctyp", request.orderType().getCode());
            payload.put("ret", "DAY");

            if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
                payload.put("prc", request.price().toPlainString());
            } else {
                payload.put("prc", "0");
            }

            if (request.triggerPrice() != null
                    && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0) {
                payload.put("trgprc", request.triggerPrice().toPlainString());
            }

            if (request.tag() != null && !request.tag().isBlank()) {
                payload.put("remarks", request.tag());
            }

            String formBody =
                    "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + sessionToken;

            HttpRequest httpReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/PlaceOrder"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            formBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());

            if ("Ok".equalsIgnoreCase(root.path("stat").asText())) {
                String norenordno = root.path("norenordno").asText();
                log.info(
                        "[SHOONYA-ORDER] Order Placed Successfully! OrderId: {} | {} {} Qty: {}",
                        norenordno,
                        request.transactionType(),
                        request.symbol(),
                        request.quantity());
                return OrderResponse.success(norenordno, request, "Order placed successfully");
            } else {
                String emsg = root.path("emsg").asText(resp.body());
                log.error("[SHOONYA-ORDER] Order Placement Rejected: {}", emsg);
                return OrderResponse.failure(request, emsg);
            }

        } catch (Exception e) {
            log.error("[SHOONYA-ORDER] Order placement exception: {}", e.getMessage(), e);
            return OrderResponse.failure(request, e.getMessage());
        }
    }

    /** Modifies an existing open or trigger-pending order. */
    public OrderResponse modifyOrder(
            String orderId,
            String symbol,
            String exchange,
            int quantity,
            BigDecimal newPrice,
            BigDecimal newTriggerPrice,
            OrderType newType) {
        if (!config.isEnabled()) {
            return OrderResponse.success(orderId, null, "Mock order modified");
        }

        try {
            String sessionToken = authenticator.getOrAuthenticateToken();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("uid", config.getUserId());
            payload.put("actid", config.getUserId());
            payload.put("norenordno", orderId);
            payload.put("tsym", symbol);
            payload.put("exch", exchange != null ? exchange : "NFO");
            payload.put("qty", String.valueOf(quantity));
            payload.put("prctyp", newType != null ? newType.getCode() : "LMT");
            payload.put("ret", "DAY");

            if (newPrice != null && newPrice.compareTo(BigDecimal.ZERO) > 0) {
                payload.put("prc", newPrice.toPlainString());
            }

            if (newTriggerPrice != null && newTriggerPrice.compareTo(BigDecimal.ZERO) > 0) {
                payload.put("trgprc", newTriggerPrice.toPlainString());
            }

            String formBody =
                    "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + sessionToken;

            HttpRequest httpReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/ModifyOrder"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            formBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());

            if ("Ok".equalsIgnoreCase(root.path("stat").asText())) {
                return OrderResponse.success(orderId, null, "Order modified successfully");
            } else {
                return OrderResponse.failure(null, root.path("emsg").asText(resp.body()));
            }

        } catch (Exception e) {
            return OrderResponse.failure(null, e.getMessage());
        }
    }

    /** Cancels an open or trigger-pending order on Shoonya. */
    public OrderResponse cancelOrder(String orderId) {
        if (!config.isEnabled()) {
            return OrderResponse.success(orderId, null, "Mock order cancelled");
        }

        try {
            String sessionToken = authenticator.getOrAuthenticateToken();

            Map<String, Object> payload = Map.of("uid", config.getUserId(), "norenordno", orderId);

            String formBody =
                    "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + sessionToken;

            HttpRequest httpReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/CancelOrder"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            formBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());

            if ("Ok".equalsIgnoreCase(root.path("stat").asText())) {
                log.info("[SHOONYA-ORDER] Cancelled order {}", orderId);
                return new OrderResponse(
                        true,
                        orderId,
                        OrderStatus.CANCELLED,
                        "Cancelled",
                        null,
                        java.time.Instant.now());
            } else {
                return OrderResponse.failure(null, root.path("emsg").asText(resp.body()));
            }

        } catch (Exception e) {
            return OrderResponse.failure(null, e.getMessage());
        }
    }

    /** Fetches current order book from Shoonya. */
    public JsonNode getOrderBook() {
        return postShoonyaJson("/NorenWClientAPI/OrderBook", Map.of("uid", config.getUserId()));
    }

    /** Fetches current position book from Shoonya. */
    public JsonNode getPositionBook() {
        return postShoonyaJson(
                "/NorenWClientAPI/PositionBook",
                Map.of(
                        "uid", config.getUserId(),
                        "actid", config.getUserId()));
    }

    private JsonNode postShoonyaJson(String endpoint, Map<String, Object> payload) {
        try {
            String sessionToken = authenticator.getOrAuthenticateToken();
            String formBody =
                    "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + sessionToken;

            HttpRequest httpReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + endpoint))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            formBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            log.error("Failed to query Shoonya endpoint {}: {}", endpoint, e.getMessage());
            return objectMapper.createArrayNode();
        }
    }
}

package com.tradingbot.kite.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.auth.KiteSession;
import com.tradingbot.kite.config.KiteProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** REST client interacting directly with Zerodha Kite Connect API v3. */
@Component
public class KiteRestClient {

    private static final Logger log = LoggerFactory.getLogger(KiteRestClient.class);
    private static final String BASE_URL = "https://api.kite.trade";

    private final KiteProperties kiteProperties;
    private final KiteSession kiteSession;
    private final RestClient restClient;

    public record KiteOrderRequest(
            String exchange,
            String tradingSymbol,
            String transactionType,
            int quantity,
            String product,
            String orderType,
            Double price,
            Double triggerPrice) {}

    public record KiteOrderResponse(String orderId) {}

    public KiteRestClient(KiteProperties kiteProperties, KiteSession kiteSession) {
        this(
                kiteProperties,
                kiteSession,
                RestClient.builder()
                        .baseUrl(BASE_URL)
                        .defaultHeader("X-Kite-Version", "3")
                        .build());
    }

    public KiteRestClient(
            KiteProperties kiteProperties, KiteSession kiteSession, RestClient restClient) {
        this.kiteProperties = kiteProperties;
        this.kiteSession = kiteSession;
        this.restClient = restClient;
    }

    public String loginUrl() {
        String redirect =
                java.net.URLEncoder.encode(kiteProperties.redirectUri(), StandardCharsets.UTF_8);
        return "https://kite.zerodha.com/connect/login?v=3&api_key="
                + kiteProperties.apiKey()
                + "&redirect_uri="
                + redirect;
    }

    public JsonNode profile() {
        return get("/user/profile");
    }

    public JsonNode positions() {
        return get("/portfolio/positions").path("data").path("net");
    }

    public List<JsonNode> orderHistory(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return List.of();
        }
        try {
            JsonNode data = get("/orders/" + orderId).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<JsonNode> list = new ArrayList<>();
            for (JsonNode node : data) {
                list.add(node);
            }
            return list;
        } catch (Exception e) {
            log.warn(
                    "[KITE-REST] Failed to fetch order history for {}: {}",
                    orderId,
                    e.getMessage());
            return List.of();
        }
    }

    public String getOrderStatus(String orderId) {
        List<JsonNode> history = orderHistory(orderId);
        if (history.isEmpty()) {
            return "UNKNOWN";
        }
        JsonNode latest = history.get(history.size() - 1);
        return latest.path("status").asText("UNKNOWN");
    }

    public String getOrderRejectionReason(String orderId) {
        List<JsonNode> history = orderHistory(orderId);
        if (history.isEmpty()) {
            return "Unknown rejection";
        }
        JsonNode latest = history.get(history.size() - 1);
        return latest.path("status_message").asText("Unknown rejection");
    }

    public int getNetPositionQuantity(String tradingSymbol) {
        if (tradingSymbol == null || tradingSymbol.isBlank()) {
            return 0;
        }
        try {
            JsonNode net = positions();
            if (net == null || !net.isArray()) {
                return 0;
            }
            for (JsonNode pos : net) {
                String sym = pos.path("tradingsymbol").asText("");
                if (tradingSymbol.equalsIgnoreCase(sym)) {
                    return pos.path("quantity").asInt(0);
                }
            }
        } catch (Exception e) {
            log.warn("[KITE-REST] Failed to read Kite positions: {}", e.getMessage());
        }
        return 0;
    }

    public boolean cancelOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        try {
            restClient
                    .delete()
                    .uri("/orders/regular/" + orderId)
                    .header("Authorization", authorizationHeader())
                    .retrieve()
                    .toBodilessEntity();
            log.info("[KITE-REST] Cancelled Kite order: {}", orderId);
            return true;
        } catch (Exception e) {
            log.warn("[KITE-REST] Failed to cancel Kite order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    public KiteOrderResponse placeOrder(KiteOrderRequest req) {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put(
                "exchange",
                req.exchange() != null && !req.exchange().isBlank() ? req.exchange() : "NFO");
        form.put("tradingsymbol", req.tradingSymbol());
        form.put("transaction_type", req.transactionType());
        form.put("quantity", String.valueOf(req.quantity()));
        form.put(
                "product",
                req.product() != null && !req.product().isBlank() ? req.product() : "MIS");
        form.put(
                "order_type",
                req.orderType() != null && !req.orderType().isBlank() ? req.orderType() : "LIMIT");
        form.put("validity", "DAY");
        if (req.price() != null && req.price() > 0) {
            double tickRounded = Math.round(req.price() * 20.0) / 20.0;
            form.put("price", String.format(Locale.US, "%.2f", tickRounded));
        }
        if (req.triggerPrice() != null && req.triggerPrice() > 0) {
            double tickRounded = Math.round(req.triggerPrice() * 20.0) / 20.0;
            form.put("trigger_price", String.format(Locale.US, "%.2f", tickRounded));
        }

        JsonNode body = postForm("/orders/regular", form);
        String status = body.path("status").asText("");
        if ("error".equalsIgnoreCase(status)) {
            String errorMsg = body.path("message").asText("Unknown Kite error");
            throw new IllegalStateException("Kite order rejected: " + errorMsg);
        }
        String orderId = body.path("data").path("order_id").asText();
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException("Kite returned no order_id: " + body);
        }
        log.info(
                "[KITE-REST] Placed Kite Order: ID={}, Symbol={}, Type={}, Qty={}",
                orderId,
                req.tradingSymbol(),
                req.transactionType(),
                req.quantity());
        return new KiteOrderResponse(orderId);
    }

    private JsonNode get(String path) {
        return restClient
                .get()
                .uri(path)
                .header("Authorization", authorizationHeader())
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode postForm(String path, Map<String, String> form) {
        return postForm(path, form, true);
    }

    public JsonNode postForm(String path, Map<String, String> form, boolean authenticated) {
        String body =
                String.join(
                        "&",
                        form.entrySet().stream()
                                .map(
                                        entry -> {
                                            String k =
                                                    java.net.URLEncoder.encode(
                                                            entry.getKey(), StandardCharsets.UTF_8);
                                            String v =
                                                    java.net.URLEncoder.encode(
                                                            entry.getValue() != null
                                                                    ? entry.getValue()
                                                                    : "",
                                                            StandardCharsets.UTF_8);
                                            return k + "=" + v;
                                        })
                                .toList());

        var spec = restClient.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (authenticated) {
            spec.header("Authorization", authorizationHeader());
        }
        return spec.body(body).retrieve().body(JsonNode.class);
    }

    public String authorizationHeader() {
        String token =
                kiteSession.accessToken() != null
                        ? kiteSession.accessToken()
                        : kiteProperties.accessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Kite access token not available; authenticate first");
        }
        return "token " + kiteProperties.apiKey() + ":" + token;
    }
}

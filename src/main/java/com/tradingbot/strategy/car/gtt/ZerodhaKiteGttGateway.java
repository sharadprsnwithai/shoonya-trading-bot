package com.tradingbot.strategy.car.gtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ZerodhaKiteGttGateway implements GttExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaKiteGttGateway.class);

    /** NSE cash tick size. */
    private static final BigDecimal TICK = new BigDecimal("0.05");

    private static final DateTimeFormatter KITE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KiteRestClient kiteRestClient;
    private final KiteAuthService kiteAuthService;
    private final com.tradingbot.marketdata.ShoonyaMarketDataService marketDataService;
    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Autowired
    public ZerodhaKiteGttGateway(
            KiteRestClient kiteRestClient,
            @Autowired(required = false) KiteAuthService kiteAuthService,
            @Autowired(required = false)
                    com.tradingbot.marketdata.ShoonyaMarketDataService marketDataService) {
        this.kiteRestClient = kiteRestClient;
        this.kiteAuthService = kiteAuthService;
        this.marketDataService = marketDataService;
    }

    public ZerodhaKiteGttGateway(KiteRestClient kiteRestClient) {
        this(kiteRestClient, null, null);
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        if (!isValidOrder(order)) {
            return null;
        }
        int maxAttempts = (kiteAuthService != null) ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, String> form = buildGttForm(order);

                JsonNode resp = kiteRestClient.postForm("/gtt/triggers", form, true);
                String status = resp.path("status").asText("");
                if ("error".equalsIgnoreCase(status)) {
                    log.error(
                            "[ZERODHA-GTT] Kite rejected GTT for {}: {}",
                            order.symbol(),
                            resp.path("message").asText("unknown error"));
                    return null;
                }
                String triggerId = resp.path("data").path("trigger_id").asText(null);
                if (triggerId == null || triggerId.isBlank()) {
                    // Never surface a blank id as a success - the caller would store "" as a
                    // permanent trigger id and silently stop re-arming this symbol.
                    log.error(
                            "[ZERODHA-GTT] Kite returned no trigger_id for {}: {}",
                            order.symbol(),
                            resp);
                    return null;
                }
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
    public String modifyGtt(String gttId, CarGttOrder newOrder) {
        if (!isValidOrder(newOrder)) {
            return null;
        }
        if (gttId == null || gttId.isBlank()) {
            return placeGtt(newOrder);
        }

        log.info("[ZERODHA-GTT] Modifying GTT {} for {}", gttId, newOrder.symbol());
        try {
            Map<String, String> form = buildGttForm(newOrder);
            if (kiteRestClient.modifyGtt(gttId, form)) {
                return gttId;
            }
        } catch (Exception e) {
            log.warn("[ZERODHA-GTT] In-place modify of GTT {} failed: {}", gttId, e.getMessage());
        }

        // Fallback: cancel then recreate. The caller keeps the previous state entry when null is
        // returned, so a failure here is retried on the next run instead of losing the trigger.
        log.info("[ZERODHA-GTT] Falling back to cancel+place for GTT {}", gttId);
        cancelGtt(gttId);
        String newId = placeGtt(newOrder);
        if (newId == null) {
            log.error(
                    "[ZERODHA-GTT] Recreate after cancel failed for {} - trigger {} is now gone,"
                            + " next run will retry.",
                    newOrder.symbol(),
                    gttId);
        }
        return newId;
    }

    @Override
    public boolean cancelGtt(String gttId) {
        if (gttId == null || gttId.isBlank()) return false;
        try {
            log.info("[ZERODHA-GTT] Cancelling GTT trigger {}", gttId);
            return kiteRestClient.cancelGtt(gttId);
        } catch (Exception e) {
            log.warn("[ZERODHA-GTT] Error cancelling GTT {}: {}", gttId, e.getMessage());
            return false;
        }
    }

    @Override
    public GttStatus getGttStatus(String gttId) {
        if (gttId == null || gttId.isBlank()) {
            return null;
        }
        JsonNode trigger = kiteRestClient.getGttTrigger(gttId);
        if (trigger == null) {
            // Unknown or unreadable - the caller must not guess a terminal status from this.
            return null;
        }
        return mapStatus(trigger.path("status").asText(""));
    }

    @Override
    public List<LiveGttTrigger> listActiveGtts() {
        List<LiveGttTrigger> triggers = new ArrayList<>();
        JsonNode data = kiteRestClient.getGttTriggers();
        if (data == null || !data.isArray()) {
            return List.of();
        }
        for (JsonNode node : data) {
            long id = node.path("id").asLong(0L);
            if (id <= 0L) {
                continue;
            }
            JsonNode condition = node.path("condition");
            String symbol = condition.path("tradingsymbol").asText("");
            if (symbol.isBlank()) {
                continue;
            }
            JsonNode orders = node.path("orders");
            String transactionType = "";
            int quantity = 0;
            if (orders.isArray() && orders.size() > 0) {
                transactionType = orders.get(0).path("transaction_type").asText("");
                quantity = orders.get(0).path("quantity").asInt(0);
            }
            triggers.add(
                    new LiveGttTrigger(
                            String.valueOf(id),
                            symbol,
                            transactionType,
                            firstTriggerValue(condition),
                            quantity,
                            node.path("status").asText(""),
                            parseInstant(node.path("created_at").asText(""))));
        }
        return triggers;
    }

    @Override
    public GttFill getTriggerFill(String gttId) {
        if (gttId == null || gttId.isBlank()) {
            return null;
        }
        try {
            List<String> orderIds = kiteRestClient.getGttOrderIds(gttId);
            for (String orderId : orderIds) {
                List<JsonNode> history = kiteRestClient.orderHistory(orderId);
                if (history.isEmpty()) {
                    continue;
                }
                JsonNode latest = history.get(history.size() - 1);
                String status = latest.path("status").asText("");
                if (!"COMPLETE".equalsIgnoreCase(status) && !"OPEN".equalsIgnoreCase(status)) {
                    continue;
                }
                double avgPrice = latest.path("average_price").asDouble(0.0);
                int qty = latest.path("filled_quantity").asInt(0);
                if (qty <= 0) {
                    qty = latest.path("quantity").asInt(0);
                }
                if (avgPrice > 0.0 && qty > 0) {
                    return new GttFill(
                            qty, BigDecimal.valueOf(avgPrice).setScale(2, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception e) {
            log.warn("[ZERODHA-GTT] Could not read fill for GTT {}: {}", gttId, e.getMessage());
        }
        return null;
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

    /** Kite exposes a trigger-specific {@code last_price} that the trigger is validated against. */
    private Map<String, String> buildGttForm(CarGttOrder order) throws Exception {
        double liveLtp = 0.0;
        try {
            liveLtp = kiteRestClient.getLtp("NSE", order.symbol());
        } catch (Exception ignored) {
        }
        if (liveLtp <= 0.0 && marketDataService != null) {
            try {
                String tok = marketDataService.resolveToken(order.symbol());
                if (tok != null) {
                    JsonNode q = marketDataService.fetchQuote("NSE", tok);
                    if (q != null && q.has("lp")) {
                        liveLtp = q.get("lp").asDouble(0.0);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        BigDecimal trigPrice = order.triggerPrice();
        double lastPrice = computeLastPrice(order.type(), trigPrice, liveLtp);

        Map<String, Object> condition =
                Map.of(
                        "exchange",
                        "NSE",
                        "tradingsymbol",
                        order.symbol(),
                        "trigger_values",
                        List.of(trigPrice.doubleValue()),
                        "last_price",
                        lastPrice);

        Map<String, Object> orderDetail =
                Map.of(
                        "transaction_type",
                        order.type() == GttOrderType.BUY ? "BUY" : "SELL",
                        "quantity",
                        order.quantity(),
                        "price",
                        order.limitPrice().doubleValue(),
                        "order_type",
                        "LIMIT",
                        "product",
                        "CNC");

        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("type", "single");
        form.put("condition", objectMapper.writeValueAsString(condition));
        form.put("orders", objectMapper.writeValueAsString(List.of(orderDetail)));
        return form;
    }

    /**
     * Derives the {@code last_price} Kite validates a trigger against.
     *
     * <p>Kite requires {@code triggerPrice > last_price} for a BUY trigger and {@code triggerPrice
     * < last_price} for a SELL trigger. The result is always clamped at least one tick inside the
     * trigger, because tick-rounding a live quote that sits within one tick of the trigger would
     * otherwise round exactly onto it and get the order rejected - and the breakout setup this
     * strategy targets is precisely a price hovering just below last week's high.
     */
    private double computeLastPrice(GttOrderType type, BigDecimal triggerPrice, double liveLtp) {
        if (type == GttOrderType.BUY) {
            BigDecimal upper = floorToTick(triggerPrice.subtract(TICK));
            if (upper.signum() <= 0) {
                return 0.0;
            }
            BigDecimal candidate;
            if (liveLtp > 0.0 && BigDecimal.valueOf(liveLtp).compareTo(triggerPrice) < 0) {
                candidate = floorToTick(BigDecimal.valueOf(liveLtp));
            } else {
                candidate = floorToTick(triggerPrice.multiply(new BigDecimal("0.995")));
            }
            return candidate.min(upper).doubleValue();
        }

        BigDecimal lower = ceilToTick(triggerPrice.add(TICK));
        BigDecimal candidate;
        if (liveLtp > 0.0 && BigDecimal.valueOf(liveLtp).compareTo(triggerPrice) > 0) {
            candidate = ceilToTick(BigDecimal.valueOf(liveLtp));
        } else {
            candidate = ceilToTick(triggerPrice.multiply(new BigDecimal("1.005")));
        }
        return candidate.max(lower).doubleValue();
    }

    /**
     * Ticks are decimal-exact; BigDecimal division avoids the binary rounding that makes {@code
     * Math.floor(2499.95 / 0.05)} evaluate to 49998 and silently shave two ticks off the reference
     * price.
     */
    private static BigDecimal floorToTick(BigDecimal value) {
        return value.divide(TICK, 0, RoundingMode.FLOOR).multiply(TICK);
    }

    private static BigDecimal ceilToTick(BigDecimal value) {
        return value.divide(TICK, 0, RoundingMode.CEILING).multiply(TICK);
    }

    private boolean isValidOrder(CarGttOrder order) {
        if (order == null || order.symbol() == null || order.symbol().isBlank()) {
            log.error("[ZERODHA-GTT] Rejecting GTT with no symbol.");
            return false;
        }
        if (order.triggerPrice() == null || order.triggerPrice().signum() <= 0) {
            log.error(
                    "[ZERODHA-GTT] Rejecting GTT for {} with invalid trigger price.",
                    order.symbol());
            return false;
        }
        if (order.limitPrice() == null || order.limitPrice().signum() <= 0) {
            log.error(
                    "[ZERODHA-GTT] Rejecting GTT for {} with invalid limit price.", order.symbol());
            return false;
        }
        if (order.quantity() <= 0) {
            log.error(
                    "[ZERODHA-GTT] Rejecting GTT for {} with non-positive quantity.",
                    order.symbol());
            return false;
        }
        return true;
    }

    private static BigDecimal firstTriggerValue(JsonNode condition) {
        JsonNode values = condition.path("trigger_values");
        if (values.isArray() && values.size() > 0) {
            double v = values.get(0).asDouble(0.0);
            if (v > 0.0) {
                return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, KITE_TS).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static GttStatus mapStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.toLowerCase()) {
            case "active" -> GttStatus.PENDING;
            case "triggered" -> GttStatus.TRIGGERED;
            case "cancelled", "deleted" -> GttStatus.CANCELLED;
            case "rejected" -> GttStatus.REJECTED;
            case "expired" -> GttStatus.EXPIRED;
            case "disabled" -> GttStatus.DISABLED;
            default -> null;
        };
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

package com.tradingbot.strategy.car.gtt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ZerodhaKiteGttGatewayTest {

    private KiteRestClient mockRestClient;
    private ZerodhaKiteGttGateway gateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(KiteRestClient.class);
        gateway = new ZerodhaKiteGttGateway(mockRestClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testPlaceBuyGttCallsKiteGttApi() {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_98765");

        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);

        String gttId = gateway.placeGtt(buyOrder("RELIANCE", "2500.00", "2500.10", 10));
        assertEquals("GTT_98765", gttId);
        verify(mockRestClient, times(1)).postForm(eq("/gtt/triggers"), anyMap(), eq(true));
    }

    @Test
    void testPlaceSellGttCallsKiteGttApi() {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_SELL_123");

        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);

        String gttId = gateway.placeGtt(sellOrder("RELIANCE", "2657.00", 10));
        assertEquals("GTT_SELL_123", gttId);
        verify(mockRestClient, times(1)).postForm(eq("/gtt/triggers"), anyMap(), eq(true));
    }

    @Test
    void testBlankTriggerIdIsTreatedAsFailureNotSuccess() {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "");

        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);

        // Returning "" used to be stored as the permanent trigger id, which silently stopped the
        // symbol from ever being re-armed.
        assertNull(gateway.placeGtt(buyOrder("RELIANCE", "2500.00", "2500.10", 10)));
    }

    @Test
    void testMissingTriggerIdIsTreatedAsFailure() {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data");

        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);

        assertNull(gateway.placeGtt(buyOrder("RELIANCE", "2500.00", "2500.10", 10)));
    }

    @Test
    void testOrderWithInvalidTriggerIsRejectedBeforeCallingKite() {
        assertNull(gateway.placeGtt(buyOrder("RELIANCE", "0", "2500.10", 10)));
        assertNull(gateway.placeGtt(buyOrder("RELIANCE", "2500.00", "2500.10", 0)));
        verify(mockRestClient, never()).postForm(any(), anyMap(), anyBoolean());
    }

    @Test
    void testLastPriceIsRoundedDownBelowTheBuyTrigger() throws Exception {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_1");
        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);
        // 2499.98 rounds to 2500.00 on a nearest-tick basis, which equals the trigger and would
        // be rejected. It must be floored to the tick below instead.
        when(mockRestClient.getLtp("NSE", "RELIANCE")).thenReturn(2499.98);

        gateway.placeGtt(buyOrder("RELIANCE", "2500.00", "2500.10", 10));

        double lastPrice = lastPriceOf(capturedForm());
        assertTrue(lastPrice < 2500.00, "last_price " + lastPrice + " must be below the trigger");
        assertEquals(2499.95, lastPrice, 0.0001);
    }

    @Test
    void testLastPriceIsRoundedUpAboveTheSellTrigger() throws Exception {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_2");
        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);
        when(mockRestClient.getLtp("NSE", "RELIANCE")).thenReturn(2657.02);

        gateway.placeGtt(sellOrder("RELIANCE", "2657.00", 50));

        double lastPrice = lastPriceOf(capturedForm());
        assertTrue(lastPrice > 2657.00, "last_price " + lastPrice + " must be above the trigger");
        assertEquals(2657.05, lastPrice, 0.0001);
    }

    @Test
    void testLastPriceFallsBackToJustInsideTheTriggerWhenQuoteUnavailable() throws Exception {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_3");
        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);
        when(mockRestClient.getLtp("NSE", "RELIANCE")).thenReturn(0.0);

        gateway.placeGtt(buyOrder("RELIANCE", "2500.00", "2500.10", 10));

        double lastPrice = lastPriceOf(capturedForm());
        assertTrue(lastPrice < 2500.00);
        assertTrue(lastPrice > 0.0);
    }

    @Test
    void testModifyGttUsesInPlacePutWhenAccepted() {
        when(mockRestClient.modifyGtt(eq("100"), anyMap())).thenReturn(true);

        String id = gateway.modifyGtt("100", buyOrder("RELIANCE", "2510.00", "2510.10", 10));

        assertEquals("100", id);
        verify(mockRestClient, never()).cancelGtt("100");
        verify(mockRestClient, never()).postForm(any(), anyMap(), anyBoolean());
    }

    @Test
    void testModifyGttFallsBackToCancelThenPlaceWhenPutIsRejected() {
        when(mockRestClient.modifyGtt(eq("100"), anyMap())).thenReturn(false);
        when(mockRestClient.cancelGtt("100")).thenReturn(true);
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "200");
        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true))).thenReturn(mockResp);
        when(mockRestClient.getLtp("NSE", "RELIANCE")).thenReturn(2500.0);

        String id = gateway.modifyGtt("100", buyOrder("RELIANCE", "2510.00", "2510.10", 10));

        assertEquals("200", id, "the caller needs the new id to update state");
        verify(mockRestClient).cancelGtt("100");
        verify(mockRestClient).postForm(eq("/gtt/triggers"), anyMap(), eq(true));
    }

    @Test
    void testModifyGttReturnsNullWhenRecreateFailsAfterCancel() {
        when(mockRestClient.modifyGtt(eq("100"), anyMap())).thenReturn(false);
        when(mockRestClient.cancelGtt("100")).thenReturn(true);
        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true)))
                .thenThrow(new RuntimeException("boom"));

        assertNull(gateway.modifyGtt("100", buyOrder("RELIANCE", "2510.00", "2510.10", 10)));
    }

    @Test
    void testGetGttStatusMapsBrokerStatuses() {
        when(mockRestClient.getGttTrigger("1")).thenReturn(statusNode("active"));
        when(mockRestClient.getGttTrigger("2")).thenReturn(statusNode("triggered"));
        when(mockRestClient.getGttTrigger("3")).thenReturn(statusNode("cancelled"));
        when(mockRestClient.getGttTrigger("4")).thenReturn(statusNode("expired"));
        when(mockRestClient.getGttTrigger("5")).thenReturn(statusNode("disabled"));
        when(mockRestClient.getGttTrigger("6")).thenReturn(statusNode("rejected"));
        when(mockRestClient.getGttTrigger("7")).thenReturn(statusNode("something-new"));

        assertEquals(GttStatus.PENDING, gateway.getGttStatus("1"));
        assertEquals(GttStatus.TRIGGERED, gateway.getGttStatus("2"));
        assertEquals(GttStatus.CANCELLED, gateway.getGttStatus("3"));
        assertEquals(GttStatus.EXPIRED, gateway.getGttStatus("4"));
        assertEquals(GttStatus.DISABLED, gateway.getGttStatus("5"));
        assertEquals(GttStatus.REJECTED, gateway.getGttStatus("6"));
        // Unknown statuses must not be guessed into a terminal state - that would drop live
        // orders from the portfolio.
        assertNull(gateway.getGttStatus("7"));
        assertNull(gateway.getGttStatus("missing"));
        assertNull(gateway.getGttStatus(""));
    }

    @Test
    void testListActiveGttsParsesSymbolTypeValueAndQuantity() {
        when(mockRestClient.getGttTriggers()).thenReturn(triggersNode());

        List<LiveGttTrigger> triggers = gateway.listActiveGtts();

        assertEquals(2, triggers.size());
        LiveGttTrigger buy = triggers.get(0);
        assertEquals("123", buy.triggerId());
        assertEquals("RELIANCE", buy.symbol());
        assertEquals("BUY", buy.transactionType());
        assertEquals(new BigDecimal("2500.00"), buy.triggerValue());
        assertEquals(10, buy.quantity());
        assertEquals("active", buy.status());
        assertNotNull(buy.createdAt());

        LiveGttTrigger sell = triggers.get(1);
        assertEquals("SELL", sell.transactionType());
        assertEquals(new BigDecimal("2657.00"), sell.triggerValue());
        assertEquals(50, sell.quantity());
    }

    @Test
    void testListActiveGttsReturnsEmptyOnBrokerError() {
        when(mockRestClient.getGttTriggers()).thenReturn(null);
        assertTrue(gateway.listActiveGtts().isEmpty());
    }

    @Test
    void testGetTriggerFillReturnsNullWhenTriggerHasNoOrders() {
        ObjectNode trigger = objectMapper.createObjectNode();
        trigger.putArray("orders");
        when(mockRestClient.getGttTrigger("9")).thenReturn(trigger);

        assertNull(gateway.getTriggerFill("9"));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> capturedForm() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockRestClient).postForm(eq("/gtt/triggers"), captor.capture(), eq(true));
        return captor.getValue();
    }

    private double lastPriceOf(Map<String, String> form) throws Exception {
        JsonNode condition = objectMapper.readTree(form.get("condition"));
        return condition.path("last_price").asDouble();
    }

    private static ObjectNode statusNode(String status) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("status", status);
        return node;
    }

    private JsonNode triggersNode() {
        try {
            return objectMapper.readTree(
                    """
                    [
                      {"id":123,"status":"active","created_at":"2026-09-28 09:15:00",
                       "condition":{"tradingsymbol":"RELIANCE","trigger_values":[2500.00]},
                       "orders":[{"transaction_type":"BUY","quantity":10}]},
                      {"id":456,"status":"active","created_at":"2026-09-28 09:16:00",
                       "condition":{"tradingsymbol":"RELIANCE","trigger_values":[2657.00]},
                       "orders":[{"transaction_type":"SELL","quantity":50}]}
                    ]
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CarGttOrder buyOrder(String symbol, String trigger, String limit, int qty) {
        return new CarGttOrder(
                null,
                "ZERODHA",
                symbol,
                GttOrderType.BUY,
                new BigDecimal(trigger),
                new BigDecimal(limit),
                qty,
                GttStatus.PENDING,
                LocalDate.now(),
                Instant.now());
    }

    private static CarGttOrder sellOrder(String symbol, String trigger, int qty) {
        return new CarGttOrder(
                null,
                "ZERODHA",
                symbol,
                GttOrderType.SELL_TARGET,
                new BigDecimal(trigger),
                new BigDecimal(trigger),
                qty,
                GttStatus.PENDING,
                LocalDate.now(),
                Instant.now());
    }
}

package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class ShoonyaMarketDataServiceEncodingTest {

    @Test
    void testFormBodyEncodingHandlesAmpersand() {
        String jData = "{\"uid\":\"FA12345\",\"stext\":\"ARE&M\"}";
        String sessionToken = "TOKEN123";
        String formBody = ShoonyaMarketDataService.buildFormBody(jData, sessionToken);

        assertTrue(formBody.contains("jData="));
        assertTrue(formBody.contains("&jKey="));
        assertEquals("jData=" + jData + "&jKey=" + sessionToken, formBody);
    }
}

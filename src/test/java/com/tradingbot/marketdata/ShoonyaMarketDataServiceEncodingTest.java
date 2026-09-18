package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class ShoonyaMarketDataServiceEncodingTest {

    @Test
    void testFormBodyEncodingHandlesAmpersand() {
        String jData = "{\"uid\":\"FA12345\",\"stext\":\"ARE&M\"}";
        String sessionToken = "TOKEN123";
        String formBody = ShoonyaMarketDataService.buildFormBody(jData, sessionToken);

        assertTrue(formBody.contains("jData="));
        assertTrue(formBody.contains("&jKey="));
        // Verify jData doesn't contain raw '&' breaking key-value pairs
        String[] parts = formBody.split("&jKey=");
        assertEquals(2, parts.length);
        String decodedJData = URLDecoder.decode(parts[0].replace("jData=", ""), StandardCharsets.UTF_8);
        assertEquals(jData, decodedJData);
        String decodedJKey = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        assertEquals(sessionToken, decodedJKey);
    }
}

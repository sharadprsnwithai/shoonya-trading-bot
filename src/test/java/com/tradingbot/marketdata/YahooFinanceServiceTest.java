package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class YahooFinanceServiceTest {

    private YahooFinanceService service;

    @BeforeEach
    void setUp() {
        service = new YahooFinanceService(new ObjectMapper());
    }

    @Test
    void testToYahooTicker() {
        assertEquals("RELIANCE.NS", service.toYahooTicker("RELIANCE"));
        assertEquals("M%26M.NS", service.toYahooTicker("M&M"));
        assertEquals("ARE%26M.NS", service.toYahooTicker("ARE&M"));
        assertEquals("%5ENSEI", service.toYahooTicker("NIFTY 50"));
        assertEquals("%5ENSEI", service.toYahooTicker("NIFTY50"));
        assertEquals("%5ENSEI", service.toYahooTicker("NIFTY"));
        assertEquals("%5ENSEI", service.toYahooTicker("^NSEI"));
        assertEquals("TCS.NS", service.toYahooTicker("NSE:TCS"));
    }

    @Test
    void testParseYahooChartResponse() throws Exception {
        String json =
                """
        {
          "chart": {
            "result": [
              {
                "meta": { "symbol": "RELIANCE.NS" },
                "timestamp": [1726531200, 1726617600],
                "indicators": {
                  "quote": [
                    {
                      "open": [2900.0, 2920.0],
                      "high": [2950.0, 2960.0],
                      "low": [2890.0, 2910.0],
                      "close": [2940.0, 2955.0],
                      "volume": [1000000, 1500000]
                    }
                  ]
                }
              }
            ],
            "error": null
          }
        }
        """;

        List<Candle> candles = service.parseChartResponse("RELIANCE", json);
        assertNotNull(candles);
        assertEquals(2, candles.size());
        assertEquals("RELIANCE", candles.get(0).symbol());
        assertEquals("D", candles.get(0).timeframe());
        assertEquals(2900.0, candles.get(0).open().doubleValue(), 0.01);
        assertEquals(2950.0, candles.get(0).high().doubleValue(), 0.01);
        assertEquals(2890.0, candles.get(0).low().doubleValue(), 0.01);
        assertEquals(2940.0, candles.get(0).close().doubleValue(), 0.01);
        assertEquals(1000000L, candles.get(0).volume());

        assertEquals(2955.0, candles.get(1).close().doubleValue(), 0.01);
        assertEquals(1500000L, candles.get(1).volume());
    }

    @Test
    void testParseYahooChartResponseWithNulls() throws Exception {
        String json =
                """
        {
          "chart": {
            "result": [
              {
                "meta": { "symbol": "TCS.NS" },
                "timestamp": [1726531200, 1726617600, 1726704000],
                "indicators": {
                  "quote": [
                    {
                      "open": [3500.0, null, 3550.0],
                      "high": [3550.0, null, 3580.0],
                      "low": [3480.0, null, 3530.0],
                      "close": [3520.0, null, 3570.0],
                      "volume": [800000, null, 950000]
                    }
                  ]
                }
              }
            ],
            "error": null
          }
        }
        """;

        List<Candle> candles = service.parseChartResponse("TCS", json);
        assertNotNull(candles);
        assertEquals(2, candles.size(), "Should skip entries with null OHLC data");
        assertEquals(3520.0, candles.get(0).close().doubleValue(), 0.01);
        assertEquals(3570.0, candles.get(1).close().doubleValue(), 0.01);
    }
}

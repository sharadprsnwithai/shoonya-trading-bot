package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShoonyaMarketDataServiceTest {

    @Test
    void testParseShoonyaCandles() {
        ShoonyaConfig config = new ShoonyaConfig();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService service = new ShoonyaMarketDataService(config, auth);

        String sampleResponse =
                """
            [
              {
                "stat": "Ok",
                "time": "04/09/2025 09:15:00",
                "ssboe": "1757043900",
                "into": "24500.50",
                "inth": "24550.00",
                "intl": "24480.25",
                "intc": "24520.10",
                "v": "15000"
              },
              {
                "stat": "Ok",
                "time": "04/09/2025 09:20:00",
                "ssboe": "1757044200",
                "into": "24520.10",
                "inth": "24580.00",
                "intl": "24510.00",
                "intc": "24575.50",
                "v": "22000"
              }
            ]
            """;

        List<Candle> candles = service.parseShoonyaCandles(sampleResponse, "NSE:NIFTY50", "5");

        assertThat(candles).hasSize(2);
        Candle c1 = candles.get(0);
        assertThat(c1.symbol()).isEqualTo("NSE:NIFTY50");
        assertThat(c1.timeframe()).isEqualTo("5");
        assertThat(c1.open()).isEqualByComparingTo(new BigDecimal("24500.50"));
        assertThat(c1.high()).isEqualByComparingTo(new BigDecimal("24550.00"));
        assertThat(c1.low()).isEqualByComparingTo(new BigDecimal("24480.25"));
        assertThat(c1.close()).isEqualByComparingTo(new BigDecimal("24520.10"));
        assertThat(c1.volume()).isEqualTo(15000L);

        Candle c2 = candles.get(1);
        assertThat(c2.open()).isEqualByComparingTo(new BigDecimal("24520.10"));
        assertThat(c2.close()).isEqualByComparingTo(new BigDecimal("24575.50"));
    }

    @Test
    void testParseEmptyOrErrorResponse() {
        ShoonyaConfig config = new ShoonyaConfig();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService service = new ShoonyaMarketDataService(config, auth);

        List<Candle> emptyCandles = service.parseShoonyaCandles("[]", "NSE:NIFTY50", "5");
        assertThat(emptyCandles).isEmpty();

        List<Candle> errorCandles =
                service.parseShoonyaCandles(
                        "{\"stat\":\"Not_Ok\",\"emsg\":\"No Data\"}", "NSE:NIFTY50", "5");
        assertThat(errorCandles).isEmpty();
    }
}

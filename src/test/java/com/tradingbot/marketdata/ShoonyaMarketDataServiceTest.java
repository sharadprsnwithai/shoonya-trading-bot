package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    void testParseShoonyaDailyCandles_DateOnlyTimeFormat() {
        ShoonyaConfig config = new ShoonyaConfig();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService service = new ShoonyaMarketDataService(config, auth);

        String dailyResponse =
                """
            [
              {
                "stat": "Ok",
                "time": "08-01-2024",
                "into": "21500.00",
                "inth": "21600.00",
                "intl": "21450.00",
                "intc": "21550.00",
                "v": "500000"
              },
              {
                "stat": "Ok",
                "time": "09-01-2024",
                "into": "21560.00",
                "inth": "21650.00",
                "intl": "21520.00",
                "intc": "21620.00",
                "v": "600000"
              }
            ]
            """;

        List<Candle> candles = service.parseShoonyaCandles(dailyResponse, "NIFTY 50", "D");
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).timestamp()).isBefore(candles.get(1).timestamp());
        java.time.LocalDate d1 =
                candles.get(0)
                        .timestamp()
                        .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();
        java.time.LocalDate d2 =
                candles.get(1)
                        .timestamp()
                        .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();
        assertThat(d1).isEqualTo(java.time.LocalDate.of(2024, 1, 8));
        assertThat(d2).isEqualTo(java.time.LocalDate.of(2024, 1, 9));
    }

    @Test
    void testWarmTokenCache_PopulatesMajorFnoTokens() {
        ShoonyaConfig config = new ShoonyaConfig();
        ShoonyaAuthenticator auth = mock(ShoonyaAuthenticator.class);
        ShoonyaMarketDataService service = new ShoonyaMarketDataService(config, auth);

        service.warmTokenCache();

        assertThat(service.resolveToken("RELIANCE")).isEqualTo("2885");
        assertThat(service.resolveToken("TCS")).isEqualTo("11536");
        assertThat(service.resolveToken("INFY")).isEqualTo("1594");
        assertThat(service.resolveToken("HDFCBANK")).isEqualTo("1333");
    }

    @Test
    void testResolveToken_RejectsNonNumericToken() {
        ShoonyaConfig config = new ShoonyaConfig();
        ShoonyaAuthenticator auth = mock(ShoonyaAuthenticator.class);
        ShoonyaMarketDataService service = new ShoonyaMarketDataService(config, auth);

        assertThat(service.resolveToken("NON_EXISTENT_TICKER_12345")).isNull();
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

    @Test
    @SuppressWarnings("unchecked")
    void testFetchHistoricalCandlesRetriesOnHttp401SessionExpiry() throws Exception {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");

        ShoonyaAuthenticator mockAuth = mock(ShoonyaAuthenticator.class);
        when(mockAuth.getOrAuthenticateToken()).thenReturn("token_stale", "token_fresh");

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> sessionExpiredResp = mock(HttpResponse.class);
        when(sessionExpiredResp.statusCode()).thenReturn(401);
        when(sessionExpiredResp.body())
                .thenReturn(
                        "{\"stat\":\"Not_Ok\",\"emsg\":\"Session Expired : Invalid Session Key\"}");

        HttpResponse<String> successResp = mock(HttpResponse.class);
        when(successResp.statusCode()).thenReturn(200);
        when(successResp.body())
                .thenReturn(
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
                          }
                        ]
                        """);

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(sessionExpiredResp, successResp);

        ShoonyaMarketDataService service =
                new ShoonyaMarketDataService(config, mockAuth, new ObjectMapper(), mockClient);

        List<Candle> candles =
                service.fetchHistoricalCandles("NSE", "2885", "NSE:RELIANCE", "5", 5);

        assertThat(candles).hasSize(1);
        verify(mockAuth, times(1)).invalidateSession();
    }

    @Test
    void testResolveExchange() {
        ShoonyaConfig config = new ShoonyaConfig();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService service = new ShoonyaMarketDataService(config, auth);

        assertThat(service.resolveExchange("CRUDEOIL")).isEqualTo("MCX");
        assertThat(service.resolveExchange("GOLD")).isEqualTo("MCX");
        assertThat(service.resolveExchange("SILVER")).isEqualTo("MCX");
        assertThat(service.resolveExchange("COPPER")).isEqualTo("MCX");
        assertThat(service.resolveExchange("NATURALGAS")).isEqualTo("MCX");
        assertThat(service.resolveExchange("RELIANCE")).isEqualTo("NSE");
        assertThat(service.resolveExchange("NSE:SBIN")).isEqualTo("NSE");
        assertThat(service.resolveExchange("BSE:TCS")).isEqualTo("BSE");
        assertThat(service.resolveExchange("MCX:CRUDEOIL")).isEqualTo("MCX");
    }
}

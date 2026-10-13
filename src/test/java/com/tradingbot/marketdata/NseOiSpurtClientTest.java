package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.NseOiSpurtItem;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NseOiSpurtClientTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ObjectMapper mapper = new ObjectMapper();
    private String fixture;

    @BeforeEach
    void setUp() throws Exception {
        try (var in = getClass().getResourceAsStream("/nse/oi-spurts-sample.json")) {
            assertThat(in).as("fixture /nse/oi-spurts-sample.json").isNotNull();
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Builds a client pinned inside the fixture's trading day (12-Oct-2026) with no backoff. */
    private NseOiSpurtClient client(HttpClient http) {
        NseOiSpurtClient c = new NseOiSpurtClient(mapper, http);
        c.setClock(
                Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), IST)); // 12-Oct-2026 09:30 IST
        c.setRetryBackoffMs(0);
        return c;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(status).when(resp).statusCode();
        doReturn(body).when(resp).body();
        return resp;
    }

    @Test
    @DisplayName("Payload is parsed, indices excluded and ranked by % OI change")
    void parsesPayloadSortsByPctOiChangeAndExcludesIndices() {
        List<NseOiSpurtItem> items = client(mock(HttpClient.class)).parsePayload(fixture);

        assertThat(items)
                .extracting(NseOiSpurtItem::symbol)
                .containsExactly(
                        "DMART", "HCLTECH", "UJJIVANSFB", "TECHM", "DELHIVERY", "RBLBANK", "LIXIS");
        assertThat(items)
                .noneMatch(
                        i ->
                                Set.of("NIFTY", "BANKNIFTY", "NIFTYFPI", "FINNIFTY", "MIDCPNIFTY")
                                        .contains(i.symbol()));

        NseOiSpurtItem dmart = items.get(0);
        assertThat(dmart.latestOi()).isEqualTo(127822L);
        assertThat(dmart.prevOi()).isEqualTo(85961L);
        assertThat(dmart.changeInOi()).isEqualTo(41861L);
        assertThat(dmart.avgInOi()).isEqualTo(48.7);
        assertThat(dmart.underlyingValue()).isEqualTo(3364.0);
    }

    @Test
    @DisplayName("fetchTopUnderlyings respects the topN limit")
    void respectsTopNLimit() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, fixture)).when(http).send(any(HttpRequest.class), any());

        List<NseOiSpurtItem> top3 = client(http).fetchTopUnderlyings(3);

        assertThat(top3)
                .extracting(NseOiSpurtItem::symbol)
                .containsExactly("DMART", "HCLTECH", "UJJIVANSFB");
    }

    @Test
    @DisplayName("HTTP 403 retries max-attempts times (with cookie warm-up) then returns empty")
    void returnsEmptyOnHttpError() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(403, "Forbidden")).when(http).send(any(HttpRequest.class), any());

        NseOiSpurtClient c = client(http);
        assertThat(c.fetchTopUnderlyings(3)).isEmpty();

        verify(http, times(3))
                .send(
                        argThat(
                                (HttpRequest req) ->
                                        req.uri().toString().contains("oi-spurts-underlyings")),
                        any());
        // Cookie warm-up fires between attempts (before the last one), never on the final try.
        verify(http, times(2))
                .send(
                        argThat(
                                (HttpRequest req) ->
                                        "https://www.nseindia.com".equals(req.uri().toString())),
                        any());
    }

    @Test
    @DisplayName("Malformed JSON returns empty after retries without throwing")
    void returnsEmptyOnMalformedJson() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, "{invalid json")).when(http).send(any(HttpRequest.class), any());

        assertThat(client(http).fetchTopUnderlyings(3)).isEmpty();

        verify(http, times(3))
                .send(
                        argThat(
                                (HttpRequest req) ->
                                        req.uri().toString().contains("oi-spurts-underlyings")),
                        any());
    }

    @Test
    @DisplayName("Stale currTradingDate (previous session) is treated as a failure")
    void treatsStaleTradingDateAsFailure() throws Exception {
        String stale =
                fixture.replaceAll(
                        "\"currTradingDate\"\\s*:\\s*\"12-Oct-2026\"",
                        "\"currTradingDate\": \"09-Oct-2026\"");
        assertThat(stale).as("replace target must exist in the fixture").isNotEqualTo(fixture);
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, stale)).when(http).send(any(HttpRequest.class), any());

        assertThat(client(http).fetchTopUnderlyings(3)).isEmpty();
        assertThat(client(mock(HttpClient.class)).parsePayload(stale)).isEmpty();
    }

    @Test
    @DisplayName("Request timeouts return empty after retries without throwing")
    void returnsEmptyOnTimeoutException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new HttpTimeoutException("request timed out"))
                .when(http)
                .send(any(HttpRequest.class), any());

        assertThat(client(http).fetchTopUnderlyings(3)).isEmpty();

        verify(http, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("topN <= 0 short-circuits without any HTTP call")
    void nonPositiveTopNReturnsEmptyWithoutHttpCall() throws Exception {
        HttpClient http = mock(HttpClient.class);
        assertThat(client(http).fetchTopUnderlyings(0)).isEmpty();
        verify(http, times(0)).send(any(HttpRequest.class), any());
    }
}

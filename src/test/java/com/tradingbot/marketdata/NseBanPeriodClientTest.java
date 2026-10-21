package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NseBanPeriodClientTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Typical NSE ban-list CSV: header row plus "&lt;Sr&gt;,&lt;Symbol&gt;,&lt;details&gt;". */
    private static final String CSV_FIXTURE =
            """
            Sr,Symbol,Scrip Name,Details
            1,RELIANCE,RELIANCE LTD,The derivative contracts in this symbol have crossed 95% of \
            the market-wide position limit
            2,PVRINOX,PVR INOX LTD,The derivative contracts in this symbol have crossed 95% of \
            the market-wide position limit
            """;

    /** The same list rendered as the HTML table NSE serves on the ban-period report page. */
    private static final String HTML_FIXTURE =
            """
            <html><body><table>
            <tr><td>Sr</td><td>Symbol</td><td>Details</td></tr>
            <tr><td>1</td><td>RELIANCE</td><td>Crossed 95% of MWPL</td></tr>
            <tr><td>2</td><td>PVRINOX</td><td>Crossed 95% of MWPL</td></tr>
            </table></body></html>
            """;

    /** A header-only payload: a legitimately empty ban list, NOT a failure. */
    private static final String EMPTY_CSV_FIXTURE = "Sr,Symbol,Scrip Name,Details\n";

    /** Live NSE archives fo_secban.csv format. */
    private static final String LIVE_NSE_FIXTURE =
            """
            Securities in Ban For Trade Date 21-OCT-2026:
            1,AMBUJACEM
            2,KAYNES
            3,SAIL
            """;

    /** Live NSE archives empty ban format. */
    private static final String LIVE_NSE_EMPTY_FIXTURE =
            """
            Securities in Ban For Trade Date 21-OCT-2026:
            """;

    /** An Akamai interstitial / error page: carries no ban-list header, so it is not a list. */
    private static final String ERROR_PAGE_FIXTURE =
            "<html><head><title>Access Denied</title></head><body>Reference #18.6b2c</body></html>";

    /** Builds a client pinned to Tuesday 13-Oct-2026 with no backoff. */
    private NseBanPeriodClient client(HttpClient http) {
        NseBanPeriodClient c = new NseBanPeriodClient(http);
        c.setClock(Clock.fixed(Instant.parse("2026-10-13T04:00:00Z"), IST)); // 09:30 IST
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
    @DisplayName("CSV ban list parses the Symbol column and ignores the free-text details column")
    void parsesCsvBanList() {
        NseBanPeriodClient.BanListResult result =
                client(mock(HttpClient.class)).parseBanList(CSV_FIXTURE);

        assertThat(result.valid()).isTrue();
        assertThat(result.symbols()).containsExactly("RELIANCE", "PVRINOX");
    }

    @Test
    @DisplayName("HTML ban-list table parses the same symbols as the CSV")
    void parsesHtmlBanList() {
        NseBanPeriodClient.BanListResult result =
                client(mock(HttpClient.class)).parseBanList(HTML_FIXTURE);

        assertThat(result.valid()).isTrue();
        assertThat(result.symbols()).containsExactly("RELIANCE", "PVRINOX");
    }

    @Test
    @DisplayName("Header-only payload is a valid, EMPTY ban list — not a failure")
    void emptyBanListIsValidNotFailure() {
        NseBanPeriodClient client = client(mock(HttpClient.class));
        NseBanPeriodClient.BanListResult result = client.parseBanList(EMPTY_CSV_FIXTURE);

        assertThat(result.valid()).isTrue();
        assertThat(result.symbols()).isEmpty();
    }

    @Test
    @DisplayName("Live NSE fo_secban.csv format parses the symbols successfully")
    void parsesLiveNseSecbanFormat() {
        NseBanPeriodClient.BanListResult result =
                client(mock(HttpClient.class)).parseBanList(LIVE_NSE_FIXTURE);

        assertThat(result.valid()).isTrue();
        assertThat(result.symbols()).containsExactly("AMBUJACEM", "KAYNES", "SAIL");
    }

    @Test
    @DisplayName("Live NSE fo_secban.csv with no banned securities reports valid empty list")
    void parsesLiveNseEmptySecban() {
        NseBanPeriodClient.BanListResult result =
                client(mock(HttpClient.class)).parseBanList(LIVE_NSE_EMPTY_FIXTURE);

        assertThat(result.valid()).isTrue();
        assertThat(result.symbols()).isEmpty();
    }

    @Test
    @DisplayName("An error page / interstitial is invalid, so the caller can fail deliberately")
    void errorPageIsInvalid() {
        NseBanPeriodClient client = client(mock(HttpClient.class));

        assertThat(client.parseBanList(ERROR_PAGE_FIXTURE).valid()).isFalse();
        assertThat(client.parseBanList(null).valid()).isFalse();
        assertThat(client.parseBanList("   ").valid()).isFalse();
    }

    @Test
    @DisplayName("fetchBanList requests today's file and reports success")
    void fetchBanListRequestsTodaysFileAndReportsSuccess() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, CSV_FIXTURE)).when(http).send(any(HttpRequest.class), any());

        NseBanPeriodClient client = client(http);
        Set<String> banned = client.fetchBanList();

        assertThat(banned).containsExactly("RELIANCE", "PVRINOX");
        assertThat(client.isLastFetchSuccessful()).isTrue();
        assertThat(client.getLastFetchDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        verify(http, times(1))
                .send(
                        argThat(
                                (HttpRequest req) ->
                                        req.uri().toString().contains("fo_secban.csv")),
                        any());
    }

    @Test
    @DisplayName("A 200 error page is a FAILURE, not an empty ban list")
    void validHeaderGuardPreventsFalseEmptyList() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, ERROR_PAGE_FIXTURE)).when(http).send(any(HttpRequest.class), any());

        NseBanPeriodClient client = client(http);
        assertThat(client.fetchBanList()).isEmpty();

        assertThat(client.isLastFetchSuccessful())
                .as("an unrecognisable 200 body must not assert that nothing is banned")
                .isFalse();
        assertThat(client.getLastFetchDate()).isNull();
    }

    @Test
    @DisplayName("HTTP 403 retries max-attempts times (with cookie warm-up) then reports failure")
    void retriesWithCookieWarmUpOnHttpError() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(response(403, "Forbidden")).when(http).send(any(HttpRequest.class), any());

        NseBanPeriodClient client = client(http);
        assertThat(client.fetchBanList()).isEmpty();
        assertThat(client.isLastFetchSuccessful()).isFalse();

        verify(http, times(3))
                .send(
                        argThat(
                                (HttpRequest req) ->
                                        req.uri().toString().contains("fo_secban.csv")),
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
    @DisplayName("The {date} placeholder is filled with ddMMyyyy and defaults are honoured")
    void buildUrlFillsDatePlaceholder() {
        NseBanPeriodClient client = client(mock(HttpClient.class));

        assertThat(client.buildUrl(LocalDate.of(2026, 1, 5)))
                .isEqualTo("https://nsearchives.nseindia.com/content/fo/fo_secban.csv");

        client.setUrl("https://nsearchives.nseindia.com/content/fo/ban_list_{date}.csv");
        assertThat(client.buildUrl(LocalDate.of(2026, 1, 5)))
                .isEqualTo("https://nsearchives.nseindia.com/content/fo/ban_list_05012026.csv");
    }
}

package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Data-quality tests for {@link ShoonyaOptionChainService}.
 *
 * <p>Each one pins a failure mode that used to reach the LVR entry gate with a plausible-looking
 * but wrong Put-Call Ratio: an asymmetric strike window, an incomplete quote batch silently ratioed
 * as if complete, and quote rows that carried neither a {@code stat} nor an {@code oi}.
 */
class ShoonyaOptionChainQualityTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    @Test
    @DisplayName("The window is re-centred on ATM: cnt is honoured low but short by one high")
    void windowIsReCentredOnAtm() throws Exception {
        // GetOptionChain returns `cnt` strikes below ATM but only `cnt - 1` above it. Asked for
        // 5 (count 4 + 1) it hands back ATM-5 .. ATM+4 — ten rows, low-heavy.
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), 1_000L, 2_000L));
        }

        OptionChainResponse chain =
                service(rows, noOverrides())
                        .getOptionChain(
                                "NIFTY",
                                "NIFTY27OCT26F",
                                "48704",
                                new BigDecimal("22850"),
                                4,
                                true);

        assertThat(chain.isSynthetic()).as("a fully quoted window must not be synthetic").isFalse();
        assertThat(chain.strikes()).hasSize(9).as("ATM ± 4 is nine strikes");
        assertThat(chain.strikes().stream().map(s -> s.strikePrice()).min(BigDecimal::compareTo))
                .get()
                .isEqualTo(new BigDecimal("22650"));
        assertThat(chain.strikes().stream().map(s -> s.strikePrice()).max(BigDecimal::compareTo))
                .get()
                .isEqualTo(new BigDecimal("23050"));
        assertThat(
                        chain.strikes().stream()
                                .filter(
                                        s ->
                                                s.strikePrice().compareTo(new BigDecimal("22600"))
                                                        == 0))
                .as("the extra low strike the API added must be trimmed away")
                .isEmpty();

        long below =
                chain.strikes().stream()
                        .filter(s -> s.strikePrice().compareTo(new BigDecimal("22850")) < 0)
                        .count();
        long above =
                chain.strikes().stream()
                        .filter(s -> s.strikePrice().compareTo(new BigDecimal("22850")) > 0)
                        .count();
        assertThat(below).as("strikes below ATM").isEqualTo(above);
    }

    @Test
    @DisplayName("A window that loses even one quote fails closed as synthetic")
    void incompleteQuoteCoverageFailsClosed() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), 1_000L, 2_000L));
        }

        // Every contract quotes cleanly except one, which reports a non-Ok stat.
        Map<String, String> quotes = noOverrides();
        quotes.put("PE_22850", "{\"stat\":\"Not_Ok\",\"emsg\":\"throttled\"}");

        OptionChainResponse chain =
                service(rows, quotes)
                        .getOptionChain(
                                "NIFTY",
                                "NIFTY27OCT26F",
                                "48704",
                                new BigDecimal("22850"),
                                4,
                                true);

        assertThat(chain.isSynthetic())
                .as("a partially quoted window must fail closed, not be ratioed")
                .isTrue();
        assertThat(chain.pcr()).as("fabricated PCR is exactly 1.00").isEqualTo(1.00);
    }

    @Test
    @DisplayName("A quote with no oi field is a failed quote, not a zero")
    void quoteWithoutOiFailsClosed() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), 1_000L, 2_000L));
        }

        // Parses as valid JSON and reports stat=Ok, but carries no oi at all.
        Map<String, String> quotes = noOverrides();
        quotes.put("CE_22900", "{\"stat\":\"Ok\",\"lp\":\"12.5\",\"v\":0}");

        OptionChainResponse chain =
                service(rows, quotes)
                        .getOptionChain(
                                "NIFTY",
                                "NIFTY27OCT26F",
                                "48704",
                                new BigDecimal("22850"),
                                4,
                                true);

        assertThat(chain.isSynthetic())
                .as("an oi-less quote must not be mistaken for zero open interest")
                .isTrue();
    }

    @Test
    @DisplayName("A quote with no stat field is a failed quote, not a success")
    void quoteWithoutStatFailsClosed() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), 1_000L, 2_000L));
        }

        Map<String, String> quotes = noOverrides();
        // No "stat" key: this used to default to "Ok" and land in the aggregate with oi=0.
        quotes.put("CE_22750", "{\"lp\":\"31.0\",\"oi\":0,\"v\":0}");

        OptionChainResponse chain =
                service(rows, quotes)
                        .getOptionChain(
                                "NIFTY",
                                "NIFTY27OCT26F",
                                "48704",
                                new BigDecimal("22850"),
                                4,
                                true);

        assertThat(chain.isSynthetic())
                .as("a stat-less quote must not be read as a successful fetch")
                .isTrue();
    }

    @Test
    @DisplayName("PCR is delivered unrounded so the entry gate sees the true ratio")
    void pcrIsNotRoundedBeforeTheGate() throws Exception {
        // 2250 put OI against 2000 call OI is 1.125 — which rounds to 1.13 but must not be
        // pre-rounded, since the gate compares the raw value against the neutral band.
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), i == 0 ? 2_000L : 0L, i == 0 ? 2_250L : 0L));
        }

        OptionChainResponse chain =
                service(rows, noOverrides())
                        .getOptionChain(
                                "NIFTY",
                                "NIFTY27OCT26F",
                                "48704",
                                new BigDecimal("22850"),
                                4,
                                true);

        assertThat(chain.pcr()).isEqualTo(1.125);
    }

    @Test
    @DisplayName("A side carrying no open interest yields no S&R strike, not the first one")
    void zeroOiDoesNotWinSupportAndResistance() {
        List<OptionStrike> allZero = new ArrayList<>();
        for (String sp : List.of("22700", "22850", "23000")) {
            allZero.add(
                    new OptionStrike(
                            new BigDecimal(sp),
                            sp.equals("22850"),
                            contract("CE", 0),
                            contract("PE", 0)));
        }
        OptionChainResponse emptySide =
                new OptionChainResponse(
                        "NIFTY",
                        new BigDecimal("22860"),
                        new BigDecimal("22850"),
                        "NIFTY27OCT26F",
                        3,
                        0,
                        0,
                        0.0,
                        allZero);

        assertThat(emptySide.maxCallOiStrike()).as("no call OI means no resistance").isNull();
        assertThat(emptySide.maxPutOiStrike()).as("no put OI means no support").isNull();

        // Only the put side carries interest. The call side must stay empty rather than default
        // to the lowest strike — which is exactly what maxOi = -1 used to produce, and that
        // fabricated level then drove the OPTION_SR gate.
        List<OptionStrike> putOnly = new ArrayList<>();
        putOnly.add(
                new OptionStrike(
                        new BigDecimal("22700"), false, contract("CE", 0), contract("PE", 500)));
        putOnly.add(
                new OptionStrike(
                        new BigDecimal("22850"), true, contract("CE", 0), contract("PE", 900)));
        putOnly.add(
                new OptionStrike(
                        new BigDecimal("23000"), false, contract("CE", 0), contract("PE", 100)));
        OptionChainResponse putsOnly =
                new OptionChainResponse(
                        "NIFTY",
                        new BigDecimal("22860"),
                        new BigDecimal("22850"),
                        "NIFTY27OCT26F",
                        3,
                        0,
                        1500,
                        0.0,
                        putOnly);

        assertThat(putsOnly.maxCallOiStrike()).as("the call side is empty").isNull();
        assertThat(putsOnly.maxPutOiStrike())
                .as("support is the heaviest put strike")
                .isEqualTo(new BigDecimal("22850"));
    }

    @Test
    @DisplayName("No ATM anchor fails closed rather than guessing a strike")
    void missingUnderlyingPriceFailsClosed() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), 1_000L, 2_000L));
        }

        // The underlying quote answers but carries no usable price, and no explicit strike is
        // pinned. The service used to fall back to a hardcoded defaultSpotPrice() — 24000 for
        // any unknown symbol — and build a fully-quoted, non-synthetic chain around that guess.
        Map<String, String> quotes = noOverrides();
        quotes.put("48704", "{\"stat\":\"Ok\",\"oi\":0,\"v\":1}");

        OptionChainResponse chain =
                service(rows, quotes)
                        .getOptionChain("NIFTY", "NIFTY27OCT26F", "48704", null, 4, true);

        assertThat(chain.isSynthetic())
                .as("without an underlying price there is no ATM anchor, so there is no chain")
                .isTrue();
    }

    @Test
    @DisplayName("An unpinned strike still resolves ATM from the underlying quote")
    void underlyingQuoteResolvesAtmWhenNoStrikePinned() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = -5; i <= 4; i++) {
            rows.add(strike(22850 + (i * 50), 1_000L, 2_000L));
        }

        // Control for the test above: with a real underlying price of 22860 and no explicit
        // strike, ATM must be derived from the quote (22860 / 50 rounded -> 22850) and the
        // window must come back real and centred on it.
        OptionChainResponse chain =
                service(rows, noOverrides())
                        .getOptionChain("NIFTY", "NIFTY27OCT26F", "48704", null, 4, true);

        assertThat(chain.isSynthetic())
                .as("a usable underlying price must yield a real chain")
                .isFalse();
        assertThat(chain.atmStrike())
                .as("ATM is derived from the underlying quote, not a hardcoded default")
                .isEqualByComparingTo(new BigDecimal("22850"));
        assertThat(chain.strikes()).hasSize(9);
    }

    // ------------------------------------------------------------------ fixtures

    /** A quote for one side of a strike, carrying {@code oi} open interest. */
    private static OptionContract contract(String optionType, long openInterest) {
        return new OptionContract(
                "NIFTY" + optionType,
                "1",
                optionType,
                new BigDecimal("22850"),
                new BigDecimal("10.5"),
                openInterest,
                100L,
                null,
                null,
                null);
    }

    /** One CE/PE pair at a strike, with token keys the quote map is keyed by. */
    private static Map<String, String> strike(long sp, long callOi, long putOi) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("strprc", String.valueOf(sp));
        row.put("ceToken", "CE_" + sp);
        row.put("peToken", "PE_" + sp);
        row.put("callOi", String.valueOf(callOi));
        row.put("putOi", String.valueOf(putOi));
        return row;
    }

    /**
     * An empty override map, i.e. every contract answers with the well-formed quote the fake
     * transport synthesises. Tests add an entry to break one specific contract.
     */
    private static Map<String, String> noOverrides() {
        return new LinkedHashMap<>();
    }

    private static ShoonyaOptionChainService service(
            List<Map<String, String>> rows, Map<String, String> quoteOverrides) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("TESTUSER");
        config.setBaseUrl("https://example.invalid");

        ShoonyaAuthenticator auth = mock(ShoonyaAuthenticator.class);
        when(auth.getOrAuthenticateToken()).thenReturn("SESSION");

        return new ShoonyaOptionChainService(
                config,
                auth,
                mock(ShoonyaMarketDataService.class),
                mapper,
                fakeHttpClient(rows, quoteOverrides));
    }

    /**
     * Stands in for Shoonya: GetOptionChain returns the CE/PE rows, and GetQuotes answers per token
     * — honouring {@code quoteOverrides} so a test can break one contract.
     */
    private static HttpClient fakeHttpClient(
            List<Map<String, String>> rows, Map<String, String> quoteOverrides) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any()))
                .thenAnswer(
                        invocation -> {
                            HttpRequest req = invocation.getArgument(0);
                            String uri = req.uri().toString();
                            String body =
                                    req.bodyPublisher()
                                            .map(ShoonyaOptionChainQualityTest::drain)
                                            .orElse("");
                            if (uri.endsWith("GetOptionChain")) {
                                return response(chainJson(rows));
                            }
                            String token = tokenOf(body);
                            String payload;
                            if (quoteOverrides.containsKey(token)) {
                                payload = quoteOverrides.get(token);
                            } else if (token.startsWith("CE_") || token.startsWith("PE_")) {
                                payload =
                                        String.format(
                                                "{\"stat\":\"Ok\",\"lp\":\"10.5\",\"oi\":%s,\"v\":100,"
                                                        + " \"bp1\":\"10.0\",\"sp1\":\"11.0\",\"c\":\"9.0\"}",
                                                oiFor(token, rows));
                            } else {
                                // The underlying futures quote, used only for the ATM anchor.
                                payload = "{\"stat\":\"Ok\",\"lp\":\"22860\",\"oi\":0,\"v\":1}";
                            }
                            return response(payload);
                        });
        return client;
    }

    private static long oiFor(String token, List<Map<String, String>> rows) {
        for (Map<String, String> row : rows) {
            if (token.equals(row.get("ceToken"))) {
                return Long.parseLong(row.get("callOi"));
            }
            if (token.equals(row.get("peToken"))) {
                return Long.parseLong(row.get("putOi"));
            }
        }
        return 0L;
    }

    private static String chainJson(List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder("{\"stat\":\"Ok\",\"values\":[");
        boolean first = true;
        for (Map<String, String> row : rows) {
            String sp = row.get("strprc");
            first = appendRow(sb, first, sp, "CE", row.get("ceToken"));
            first = appendRow(sb, first, sp, "PE", row.get("peToken"));
        }
        return sb.append("]}").toString();
    }

    private static boolean appendRow(
            StringBuilder sb, boolean first, String sp, String optType, String token) {
        if (!first) {
            sb.append(',');
        }
        sb.append("{\"strprc\":\"")
                .append(sp)
                .append("\",\"optt\":\"")
                .append(optType)
                .append("\",\"token\":\"")
                .append(token)
                .append("\",\"tsym\":\"")
                .append(token)
                .append("\"}");
        return false;
    }

    /**
     * Reads a one-shot {@link HttpRequest.BodyPublisher} into a string.
     *
     * <p>The publisher can only be consumed once, so it is subscribed to directly and drained into
     * a byte array before the fake transport routes the request.
     */
    private static String drain(HttpRequest.BodyPublisher publisher) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        publisher.subscribe(
                new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer item) {
                        byte[] chunk = new byte[item.remaining()];
                        item.get(chunk);
                        out.writeBytes(chunk);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });
        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out draining the request body");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted draining the request body", e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String tokenOf(String body) {
        Matcher m = TOKEN.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(String payload) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(payload);
        return resp;
    }
}

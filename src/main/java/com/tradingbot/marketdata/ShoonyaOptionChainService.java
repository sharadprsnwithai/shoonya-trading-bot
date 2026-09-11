package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Service to fetch and construct Option Chain data from Shoonya (NorenAPI). */
@Service
public class ShoonyaOptionChainService {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaOptionChainService.class);
    public static final String DEFAULT_NIFTY_FUT_SYMBOL = "NIFTY29SEP26F";
    public static final String DEFAULT_NIFTY_FUT_TOKEN = "68407";

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @Autowired
    public ShoonyaOptionChainService(ShoonyaConfig config, ShoonyaAuthenticator authenticator) {
        this(
                config,
                authenticator,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    public ShoonyaOptionChainService(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Retrieves option chain for NIFTY 50 centered around ATM ± count strikes. */
    public OptionChainResponse getNifty50OptionChain(
            BigDecimal explicitStrike, int count, boolean fetchQuotes) {
        return getOptionChain(
                "NIFTY",
                DEFAULT_NIFTY_FUT_SYMBOL,
                DEFAULT_NIFTY_FUT_TOKEN,
                explicitStrike,
                count,
                fetchQuotes);
    }

    /** Calculates Put-Call Ratio (PCR) for NIFTY 50 across ATM ± count strikes. */
    public com.tradingbot.model.PcrResponse getNifty50Pcr(int count) {
        OptionChainResponse chain =
                getNifty50OptionChain(null, Math.max(1, Math.min(count, 25)), true);
        long totalCallVolume = 0;
        long totalPutVolume = 0;

        for (OptionStrike strike : chain.strikes()) {
            if (strike.call() != null) totalCallVolume += strike.call().volume();
            if (strike.put() != null) totalPutVolume += strike.put().volume();
        }

        return com.tradingbot.model.PcrResponse.calculate(
                chain.underlying(),
                chain.underlyingPrice(),
                chain.atmStrike(),
                chain.strikeCount(),
                chain.totalCallOi(),
                chain.totalPutOi(),
                totalCallVolume,
                totalPutVolume);
    }

    /** Retrieves option chain for any underlying symbol centered around ATM ± count strikes. */
    public OptionChainResponse getOptionChain(
            String underlying,
            String futSymbol,
            String futToken,
            BigDecimal explicitStrike,
            int count,
            boolean fetchQuotes) {
        if (!config.isEnabled()) {
            return mockOptionChain(
                    underlying,
                    explicitStrike != null ? explicitStrike : new BigDecimal("24000"),
                    count);
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String sessionToken = authenticator.getOrAuthenticateToken();

                // 1. Determine Underlying Price & ATM Strike
                BigDecimal underlyingPrice = BigDecimal.ZERO;
                if (futToken != null && !futToken.isBlank()) {
                    JsonNode quote = fetchQuote(sessionToken, "NFO", futToken);
                    if (quote != null) {
                        underlyingPrice =
                                new BigDecimal(
                                        quote.path("lp")
                                                .asText(quote.path("sptprc").asText("24000")));
                    }
                }

                BigDecimal atmStrike;
                if (explicitStrike != null && explicitStrike.compareTo(BigDecimal.ZERO) > 0) {
                    atmStrike = explicitStrike;
                } else if (underlyingPrice.compareTo(BigDecimal.ZERO) > 0) {
                    // Round to nearest 50 strike
                    atmStrike =
                            underlyingPrice
                                    .divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(50));
                } else {
                    atmStrike = new BigDecimal("24000");
                }

                // 2. Call GetOptionChain API
                Map<String, Object> ocPayload = new LinkedHashMap<>();
                ocPayload.put("uid", config.getUserId());
                ocPayload.put("exch", "NFO");
                ocPayload.put("tsym", futSymbol);
                ocPayload.put("strprc", atmStrike.stripTrailingZeros().toPlainString());
                ocPayload.put("cnt", String.valueOf(count));

                String formBody =
                        "jData="
                                + objectMapper.writeValueAsString(ocPayload)
                                + "&jKey="
                                + sessionToken;

                HttpRequest req =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                config.getBaseUrl()
                                                        + "/NorenWClientAPI/GetOptionChain"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Forwarded-For", config.resolvePublicIp())
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                formBody, StandardCharsets.UTF_8))
                                .build();

                HttpResponse<String> resp =
                        httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                String respBody = resp.body();

                if (isSessionExpired(resp.statusCode(), respBody)) {
                    log.warn(
                            "Shoonya session expired (HTTP {}) during GetOptionChain fetch for {}. Invalidating session and retrying (attempt {})...",
                            resp.statusCode(),
                            futSymbol,
                            attempt);
                    authenticator.invalidateSession();
                    continue;
                }

                if (resp.statusCode() != 200) {
                    log.error(
                            "HTTP error {} fetching OptionChain for {}: {}",
                            resp.statusCode(),
                            futSymbol,
                            respBody);
                    return mockOptionChain(underlying, atmStrike, count);
                }

                JsonNode root = objectMapper.readTree(respBody);

                if (!"Ok".equalsIgnoreCase(root.path("stat").asText())) {
                    log.warn(
                            "GetOptionChain API returned non-Ok for {}: {}",
                            futSymbol,
                            root.path("emsg").asText(resp.body()));
                    return mockOptionChain(underlying, atmStrike, count);
                }

                JsonNode values = root.path("values");
                if (!values.isArray() || values.isEmpty()) {
                    return new OptionChainResponse(
                            underlying,
                            underlyingPrice,
                            atmStrike,
                            futSymbol,
                            0,
                            0,
                            0,
                            0.0,
                            List.of());
                }

                // 3. Group by strike
                Map<BigDecimal, OptionContractDraft> strikeMap = new HashMap<>();
                List<OptionContractDraft> allContracts = new ArrayList<>();

                for (JsonNode item : values) {
                    BigDecimal strikePrice = new BigDecimal(item.path("strprc").asText("0"));
                    String optType = item.path("optt").asText("");
                    String token = item.path("token").asText("");
                    String tsym = item.path("tsym").asText("");

                    OptionContractDraft draft =
                            new OptionContractDraft(tsym, token, optType, strikePrice);
                    allContracts.add(draft);

                    OptionContractDraft existing = strikeMap.get(strikePrice);
                    if (existing == null) {
                        existing = new OptionContractDraft(null, null, null, strikePrice);
                        strikeMap.put(strikePrice, existing);
                    }
                    if ("CE".equalsIgnoreCase(optType)) {
                        existing.callDraft = draft;
                    } else if ("PE".equalsIgnoreCase(optType)) {
                        existing.putDraft = draft;
                    }
                }

                // 4. Optionally fetch quotes for all option contracts concurrently
                if (fetchQuotes) {
                    List<CompletableFuture<Void>> futures =
                            allContracts.stream()
                                    .map(
                                            draft ->
                                                    CompletableFuture.runAsync(
                                                            () -> {
                                                                try {
                                                                    JsonNode q =
                                                                            fetchQuote(
                                                                                    sessionToken,
                                                                                    "NFO",
                                                                                    draft.token);
                                                                    if (q != null
                                                                            && "Ok"
                                                                                    .equalsIgnoreCase(
                                                                                            q.path(
                                                                                                            "stat")
                                                                                                    .asText(
                                                                                                            "Ok"))) {
                                                                        draft.ltp =
                                                                                new BigDecimal(
                                                                                        q.path("lp")
                                                                                                .asText(
                                                                                                        "0"));
                                                                        draft.openInterest =
                                                                                q.path("oi")
                                                                                        .asLong(0);
                                                                        draft.volume =
                                                                                q.path("v")
                                                                                        .asLong(0);
                                                                        draft.bidPrice =
                                                                                new BigDecimal(
                                                                                        q.path(
                                                                                                        "bp1")
                                                                                                .asText(
                                                                                                        "0"));
                                                                        draft.askPrice =
                                                                                new BigDecimal(
                                                                                        q.path(
                                                                                                        "sp1")
                                                                                                .asText(
                                                                                                        "0"));
                                                                        draft.previousClose =
                                                                                new BigDecimal(
                                                                                        q.path("c")
                                                                                                .asText(
                                                                                                        "0"));
                                                                    }
                                                                } catch (Exception e) {
                                                                    log.debug(
                                                                            "Quote fetch failed for token {}: {}",
                                                                            draft.token,
                                                                            e.getMessage());
                                                                }
                                                            },
                                                            executor))
                                    .toList();

                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .get(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn(
                                "Option chain quote batch fetch timed out or interrupted: {}",
                                e.getMessage());
                    }
                }

                // 5. Construct sorted OptionStrike list and calculate aggregate OI
                List<BigDecimal> sortedStrikes = new ArrayList<>(strikeMap.keySet());
                Collections.sort(sortedStrikes);

                long totalCallOi = 0;
                long totalPutOi = 0;
                List<OptionStrike> strikeList = new ArrayList<>();

                for (BigDecimal sp : sortedStrikes) {
                    OptionContractDraft draft = strikeMap.get(sp);
                    OptionContract call =
                            draft.callDraft != null ? draft.callDraft.toContract() : null;
                    OptionContract put =
                            draft.putDraft != null ? draft.putDraft.toContract() : null;

                    if (call != null) totalCallOi += call.openInterest();
                    if (put != null) totalPutOi += put.openInterest();

                    boolean isAtm = sp.compareTo(atmStrike) == 0;
                    strikeList.add(new OptionStrike(sp, isAtm, call, put));
                }

                double pcr = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0.0;

                return new OptionChainResponse(
                        underlying,
                        underlyingPrice,
                        atmStrike,
                        futSymbol,
                        strikeList.size(),
                        totalCallOi,
                        totalPutOi,
                        Math.round(pcr * 100.0) / 100.0,
                        strikeList);

            } catch (Exception e) {
                log.warn(
                        "Attempt {} failed to fetch option chain for {}: {}",
                        attempt,
                        underlying,
                        e.getMessage());
                if (attempt == 2) {
                    log.error(
                            "Failed to fetch option chain for {} after 2 attempts",
                            underlying,
                            e);
                    throw new RuntimeException("Option chain fetch failure: " + e.getMessage(), e);
                }
            }
        }
        return mockOptionChain(underlying, explicitStrike != null ? explicitStrike : new BigDecimal("24000"), count);
    }

    private JsonNode fetchQuote(String sessionToken, String exchange, String token) {
        try {
            Map<String, Object> payload =
                    Map.of(
                            "uid", config.getUserId(),
                            "exch", exchange,
                            "token", token);
            String body =
                    "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + sessionToken;

            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/GetQuotes"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("GetQuotes returned status {} for token {}", resp.statusCode(), token);
                return null;
            }
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSessionExpired(int statusCode, String body) {
        if (statusCode == 401 || statusCode == 403) {
            return true;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("Session Expired")
                || body.contains("Invalid Session Key")
                || body.contains("NOT_LOGGED_IN")
                || body.contains("Invalid Token")
                || body.contains("INVALID_SESSION");
    }

    private OptionChainResponse mockOptionChain(
            String underlying, BigDecimal atmStrike, int count) {
        List<OptionStrike> list = new ArrayList<>();
        BigDecimal strikeStep = BigDecimal.valueOf(50);
        long totalCallOi = 0;
        long totalPutOi = 0;

        for (int i = -count; i <= count; i++) {
            BigDecimal sp = atmStrike.add(strikeStep.multiply(BigDecimal.valueOf(i)));
            boolean isAtm = (i == 0);

            BigDecimal callLtp = BigDecimal.valueOf(Math.max(1.0, 150.0 - (i * 25.0)));
            BigDecimal putLtp = BigDecimal.valueOf(Math.max(1.0, 150.0 + (i * 25.0)));
            long callOi = 50000L + (Math.abs(i) * 12000L);
            long putOi = 48000L + (Math.abs(i) * 11000L);

            totalCallOi += callOi;
            totalPutOi += putOi;

            OptionContract call =
                    new OptionContract(
                            underlying + "29SEP26C" + sp.intValue(),
                            "mock_c_" + sp,
                            "CE",
                            sp,
                            callLtp,
                            callOi,
                            10000L,
                            callLtp.subtract(BigDecimal.ONE),
                            callLtp.add(BigDecimal.ONE),
                            callLtp);
            OptionContract put =
                    new OptionContract(
                            underlying + "29SEP26P" + sp.intValue(),
                            "mock_p_" + sp,
                            "PE",
                            sp,
                            putLtp,
                            putOi,
                            10000L,
                            putLtp.subtract(BigDecimal.ONE),
                            putLtp.add(BigDecimal.ONE),
                            putLtp);

            list.add(new OptionStrike(sp, isAtm, call, put));
        }

        double pcr = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0.0;
        return new OptionChainResponse(
                underlying,
                atmStrike,
                atmStrike,
                DEFAULT_NIFTY_FUT_SYMBOL,
                list.size(),
                totalCallOi,
                totalPutOi,
                Math.round(pcr * 100.0) / 100.0,
                list);
    }

    private static class OptionContractDraft {
        String symbol;
        String token;
        String optionType;
        BigDecimal strikePrice;
        BigDecimal ltp = BigDecimal.ZERO;
        long openInterest = 0;
        long volume = 0;
        BigDecimal bidPrice = BigDecimal.ZERO;
        BigDecimal askPrice = BigDecimal.ZERO;
        BigDecimal previousClose = BigDecimal.ZERO;

        OptionContractDraft callDraft;
        OptionContractDraft putDraft;

        OptionContractDraft(
                String symbol, String token, String optionType, BigDecimal strikePrice) {
            this.symbol = symbol;
            this.token = token;
            this.optionType = optionType;
            this.strikePrice = strikePrice;
        }

        OptionContract toContract() {
            return new OptionContract(
                    symbol,
                    token,
                    optionType,
                    strikePrice,
                    ltp,
                    openInterest,
                    volume,
                    bidPrice,
                    askPrice,
                    previousClose);
        }
    }
}

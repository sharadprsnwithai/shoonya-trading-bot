package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.util.StockFnoRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
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
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
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

    /**
     * Retrieves option chain for any index or stock underlying centered around ATM ± count strikes.
     */
    public OptionChainResponse getIndexOptionChain(
            String underlying, BigDecimal explicitStrike, int count, boolean fetchQuotes) {
        String cleanUnderlying = underlying != null ? underlying.trim().toUpperCase() : "NIFTY";
        if ("SENSEX".equalsIgnoreCase(cleanUnderlying)
                || "BSESN".equalsIgnoreCase(cleanUnderlying)) {
            return getOptionChain("SENSEX", "BSXOPT", "", explicitStrike, count, fetchQuotes);
        } else if ("BANKNIFTY".equalsIgnoreCase(cleanUnderlying)
                || "BANK NIFTY".equalsIgnoreCase(cleanUnderlying)) {
            return getOptionChain("BANKNIFTY", "BANKNIFTY", "", explicitStrike, count, fetchQuotes);
        } else if ("NIFTY".equalsIgnoreCase(cleanUnderlying)
                || "NIFTY50".equalsIgnoreCase(cleanUnderlying)
                || "NIFTY 50".equalsIgnoreCase(cleanUnderlying)) {
            return getNifty50OptionChain(explicitStrike, count, fetchQuotes);
        } else {
            // Stock F&O Underlying (e.g. BSE, LAURUSLABS, SAIL, POLYCAB, etc.)
            return getOptionChain(
                    cleanUnderlying, cleanUnderlying, "", explicitStrike, count, fetchQuotes);
        }
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
        String cleanUnderlying = underlying != null ? underlying.trim().toUpperCase() : "NIFTY";
        String segment = StockFnoRegistry.getSegment(cleanUnderlying);

        if (!config.isEnabled()) {
            return mockOptionChain(
                    cleanUnderlying,
                    explicitStrike != null ? explicitStrike : defaultSpotPrice(cleanUnderlying),
                    count);
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String sessionToken = authenticator.getOrAuthenticateToken();

                // 1. Determine Underlying Price & ATM Strike
                BigDecimal underlyingPrice = BigDecimal.ZERO;
                if (futToken != null && !futToken.isBlank()) {
                    JsonNode quote = fetchQuote(sessionToken, segment, futToken);
                    if (quote != null) {
                        underlyingPrice =
                                new BigDecimal(
                                        quote.path("lp")
                                                .asText(
                                                        quote.path("sptprc")
                                                                .asText(
                                                                        defaultSpotPrice(
                                                                                        cleanUnderlying)
                                                                                .toPlainString())));
                    }
                }

                BigDecimal atmStrike;
                if (explicitStrike != null && explicitStrike.compareTo(BigDecimal.ZERO) > 0) {
                    atmStrike = explicitStrike;
                } else if (underlyingPrice.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal step =
                            StockFnoRegistry.getStrikeStep(cleanUnderlying, underlyingPrice);
                    atmStrike =
                            underlyingPrice.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
                } else {
                    atmStrike = defaultSpotPrice(cleanUnderlying);
                }

                // 2. Call GetOptionChain API
                Map<String, Object> ocPayload = new LinkedHashMap<>();
                ocPayload.put("uid", config.getUserId());
                ocPayload.put("exch", segment);
                ocPayload.put(
                        "tsym",
                        futSymbol != null && !futSymbol.isBlank() ? futSymbol : cleanUnderlying);
                ocPayload.put("strprc", atmStrike.stripTrailingZeros().toPlainString());
                ocPayload.put("cnt", String.valueOf(count));

                String formBody =
                        ShoonyaMarketDataService.buildFormBody(
                                objectMapper.writeValueAsString(ocPayload), sessionToken);

                HttpRequest req =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                config.getBaseUrl()
                                                        + "/NorenWClientAPI/GetOptionChain"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Forwarded-For", config.resolvePublicIp())
                                .timeout(Duration.ofSeconds(10))
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
                    return mockOptionChain(cleanUnderlying, atmStrike, count);
                }

                JsonNode root = objectMapper.readTree(respBody);

                if (!"Ok".equalsIgnoreCase(root.path("stat").asText())) {
                    log.warn(
                            "GetOptionChain API returned non-Ok for {}: {}",
                            futSymbol,
                            root.path("emsg").asText(resp.body()));
                    return mockOptionChain(cleanUnderlying, atmStrike, count);
                }

                JsonNode values = root.path("values");
                if (!values.isArray() || values.isEmpty()) {
                    return new OptionChainResponse(
                            cleanUnderlying,
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
                                                                                    segment,
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
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
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
                    OptionContract call = null;
                    OptionContract put = null;

                    if (draft.callDraft != null) {
                        OptionContractDraft c = draft.callDraft;
                        call =
                                new OptionContract(
                                        c.symbol,
                                        c.token,
                                        "CE",
                                        c.strikePrice,
                                        c.ltp,
                                        c.openInterest,
                                        c.volume,
                                        c.bidPrice,
                                        c.askPrice,
                                        c.previousClose);
                        totalCallOi += c.openInterest;
                    }

                    if (draft.putDraft != null) {
                        OptionContractDraft p = draft.putDraft;
                        put =
                                new OptionContract(
                                        p.symbol,
                                        p.token,
                                        "PE",
                                        p.strikePrice,
                                        p.ltp,
                                        p.openInterest,
                                        p.volume,
                                        p.bidPrice,
                                        p.askPrice,
                                        p.previousClose);
                        totalPutOi += p.openInterest;
                    }

                    boolean isAtm =
                            underlyingPrice.compareTo(BigDecimal.ZERO) > 0
                                    && sp.compareTo(atmStrike) == 0;
                    strikeList.add(new OptionStrike(sp, isAtm, call, put));
                }

                double pcr = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0.0;

                return new OptionChainResponse(
                        cleanUnderlying,
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
                        "Option chain fetch attempt {} failed for {}: {}",
                        attempt,
                        futSymbol,
                        e.getMessage());
                if (attempt == 2) {
                    log.error(
                            "Failed to fetch option chain for {} after 2 attempts",
                            cleanUnderlying,
                            e);
                    return mockOptionChain(
                            cleanUnderlying,
                            explicitStrike != null
                                    ? explicitStrike
                                    : defaultSpotPrice(cleanUnderlying),
                            count);
                }
            }
        }
        return mockOptionChain(
                cleanUnderlying,
                explicitStrike != null ? explicitStrike : defaultSpotPrice(cleanUnderlying),
                count);
    }

    private JsonNode fetchQuote(String sessionToken, String exchange, String token) {
        try {
            Map<String, Object> payload =
                    Map.of(
                            "uid", config.getUserId(),
                            "exch", exchange,
                            "token", token);
            String body =
                    ShoonyaMarketDataService.buildFormBody(
                            objectMapper.writeValueAsString(payload), sessionToken);

            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/GetQuotes"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("GetQuotes returned status {} for token {}", resp.statusCode(), token);
                return null;
            }
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            log.debug("GetQuotes API threw exception for token {}: {}", token, e.getMessage());
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
        BigDecimal strikeStep = StockFnoRegistry.getStrikeStep(underlying, atmStrike);
        long totalCallOi = 0;
        long totalPutOi = 0;

        LocalDate today = LocalDate.now();
        boolean isWeekly = StockFnoRegistry.isIndex(underlying);
        LocalDate expiry = StockFnoRegistry.calculateTargetExpiry(underlying, today, isWeekly, 1);
        double dteDays = Math.max(1.0, java.time.temporal.ChronoUnit.DAYS.between(today, expiry));

        for (int i = -count; i <= count; i++) {
            BigDecimal sp = atmStrike.add(strikeStep.multiply(BigDecimal.valueOf(i)));
            boolean isAtm = (i == 0);

            BigDecimal callLtp =
                    StockFnoRegistry.estimateTheoreticalPremium(
                            underlying, atmStrike, sp, "CE", dteDays);
            BigDecimal putLtp =
                    StockFnoRegistry.estimateTheoreticalPremium(
                            underlying, atmStrike, sp, "PE", dteDays);

            long callOi = 50000L + (Math.abs(i) * 12000L);
            long putOi = 48000L + (Math.abs(i) * 11000L);

            totalCallOi += callOi;
            totalPutOi += putOi;

            String callSymbol =
                    StockFnoRegistry.formatTradingSymbol(underlying, expiry, sp, "CE", isWeekly);
            String putSymbol =
                    StockFnoRegistry.formatTradingSymbol(underlying, expiry, sp, "PE", isWeekly);

            OptionContract call =
                    new OptionContract(
                            callSymbol,
                            "mock_c_" + sp,
                            "CE",
                            sp,
                            callLtp,
                            callOi,
                            10000L,
                            callLtp.subtract(BigDecimal.valueOf(0.50)),
                            callLtp.add(BigDecimal.valueOf(0.50)),
                            callLtp);
            OptionContract put =
                    new OptionContract(
                            putSymbol,
                            "mock_p_" + sp,
                            "PE",
                            sp,
                            putLtp,
                            putOi,
                            10000L,
                            putLtp.subtract(BigDecimal.valueOf(0.50)),
                            putLtp.add(BigDecimal.valueOf(0.50)),
                            putLtp);

            list.add(new OptionStrike(sp, isAtm, call, put));
        }

        double pcr = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0.0;
        return new OptionChainResponse(
                underlying,
                atmStrike,
                atmStrike,
                underlying,
                list.size(),
                totalCallOi,
                totalPutOi,
                Math.round(pcr * 100.0) / 100.0,
                list);
    }

    private BigDecimal defaultSpotPrice(String underlying) {
        if ("SENSEX".equalsIgnoreCase(underlying) || "BSESN".equalsIgnoreCase(underlying)) {
            return new BigDecimal("80000");
        }
        if ("BANKNIFTY".equalsIgnoreCase(underlying) || "BANK NIFTY".equalsIgnoreCase(underlying)) {
            return new BigDecimal("52000");
        }
        if ("BSE".equalsIgnoreCase(underlying)) return new BigDecimal("2400");
        if ("LAURUSLABS".equalsIgnoreCase(underlying)) return new BigDecimal("480");
        if ("SAIL".equalsIgnoreCase(underlying)) return new BigDecimal("140");
        if ("POLYCAB".equalsIgnoreCase(underlying)) return new BigDecimal("7000");
        if ("ADANIENSOL".equalsIgnoreCase(underlying)) return new BigDecimal("980");
        if ("ADANIGREEN".equalsIgnoreCase(underlying)) return new BigDecimal("1250");
        if ("MCX".equalsIgnoreCase(underlying)) return new BigDecimal("6200");
        if ("TORNTPHARM".equalsIgnoreCase(underlying)) return new BigDecimal("3400");
        if ("BHEL".equalsIgnoreCase(underlying)) return new BigDecimal("270");
        if ("HINDALCO".equalsIgnoreCase(underlying)) return new BigDecimal("680");
        return new BigDecimal("24000");
    }

    @PreDestroy
    public void cleanup() {
        try {
            executor.shutdownNow();
            log.info("[OPTION-CHAIN] Executor shutdown complete.");
        } catch (Exception e) {
            log.debug("[OPTION-CHAIN] Error shutting down executor: {}", e.getMessage());
        }
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
    }
}

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Service to fetch and construct Option Chain data from Shoonya (NorenAPI). */
@Service
public class ShoonyaOptionChainService {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaOptionChainService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Fallback only (previous-month contract, stale after expiry) — see getNifty50OptionChain. */
    public static final String DEFAULT_NIFTY_FUT_SYMBOL = "NIFTY29SEP26F";

    public static final String DEFAULT_NIFTY_FUT_TOKEN = "68407";

    /**
     * Number of strikes either side of ATM aggregated into every Put-Call Ratio calculation — a
     * symmetric 9-strike window (ATM±4).
     */
    public static final int PCR_STRIKE_WINDOW = 4;

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ShoonyaMarketDataService marketDataService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    // GetQuotes degrades silently under burst: responses keep stat=Ok but drop fields (oi above
    // all), which the window-completeness guard then rejects — measured at 11-14 of 18 covered
    // under a concurrent burst versus 18 of 18 when paced near 8 req/s. Every quote request this
    // service makes therefore queues on a single shared slot machine capped below the broker's
    // ~10 req/s data limit, so concurrent chain reads throttle themselves instead of tripping
    // the broker's field-drop.
    private static final long QUOTE_MIN_INTERVAL_NANOS = 125_000_000L; // 8 req/s aggregate
    private final Object quoteSlotLock = new Object();
    private long nextQuoteSlotNanos = 0L;

    @Autowired
    public ShoonyaOptionChainService(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService) {
        this(
                config,
                authenticator,
                marketDataService,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public ShoonyaOptionChainService(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.authenticator = authenticator;
        this.marketDataService = marketDataService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Retrieves option chain for NIFTY 50 centered around ATM ± count strikes. */
    public OptionChainResponse getNifty50OptionChain(
            BigDecimal explicitStrike, int count, boolean fetchQuotes) {
        // Resolve the live near-month NIFTY futures contract (real tsym for the chain anchor, live
        // token for the underlying-price/ATM path) instead of the hardcoded previous-month
        // constants, which went stale at every expiry. Falls back to the constants if resolution
        // fails (previous behavior).
        ShoonyaMarketDataService.FuturesContract contract =
                marketDataService != null
                        ? marketDataService.resolveFuturesContract("NIFTY")
                        : null;
        if (contract != null) {
            return getOptionChain(
                    "NIFTY", contract.tsym(), contract.token(), explicitStrike, count, fetchQuotes);
        }
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
            // Stock F&O Underlying (e.g. RELIANCE, TCS, HDFCBANK, INFY, etc.)
            ShoonyaMarketDataService.FuturesContract stockContract =
                    marketDataService != null
                            ? marketDataService.resolveFuturesContract(cleanUnderlying)
                            : null;
            String futTsym =
                    stockContract != null && stockContract.tsym() != null
                            ? stockContract.tsym()
                            : cleanUnderlying;
            String futTok = stockContract != null ? stockContract.token() : "";
            return getOptionChain(
                    cleanUnderlying, futTsym, futTok, explicitStrike, count, fetchQuotes);
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
                    count,
                    "Shoonya disabled (SHOONYA_ENABLED=false)");
        }

        String lastFailure = "no data returned by GetOptionChain";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String sessionToken = authenticator.getOrAuthenticateToken();

                // 1. Determine Underlying Price & ATM Strike
                //
                // When the caller does not pin the strike, this quote is the ONLY source of the
                // underlying price. It used to fall back to defaultSpotPrice() when the quote
                // failed or carried no usable price, so the chain was requested centred on a
                // hardcoded guess — 24000 for any unknown symbol. Every quote in the resulting
                // window could then answer cleanly and the chain came back as legitimate,
                // non-synthetic data whose PCR described the wrong part of the strike ladder.
                // A missing or malformed price is now recorded as missing rather than guessed.
                BigDecimal underlyingPrice = BigDecimal.ZERO;
                if (futToken != null && !futToken.isBlank()) {
                    JsonNode quote = fetchQuote(sessionToken, segment, futToken);
                    if (quote != null && "Ok".equalsIgnoreCase(quote.path("stat").asText(""))) {
                        String lp = quote.path("lp").asText("");
                        String spot = quote.path("sptprc").asText("");
                        String raw = !lp.isBlank() ? lp : spot;
                        if (!raw.isBlank()) {
                            try {
                                underlyingPrice = new BigDecimal(raw);
                            } catch (NumberFormatException e) {
                                log.debug(
                                        "Underlying quote for token {} carried a non-numeric price"
                                                + " '{}'",
                                        futToken,
                                        raw);
                            }
                        }
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
                    // No pinned strike and no usable underlying price — there is nothing to centre
                    // the window on. Retry, then fail closed via the synthetic chain below rather
                    // than invent an ATM.
                    lastFailure =
                            String.format(
                                    "attempt %d: no ATM anchor for tsym=%s — the underlying quote"
                                            + " did not yield a usable price and no explicit strike"
                                            + " was supplied",
                                    attempt, futSymbol);
                    log.warn("[OPTION-CHAIN] {}", lastFailure);
                    continue;
                }

                // 2. Call GetOptionChain API
                Map<String, Object> ocPayload = new LinkedHashMap<>();
                ocPayload.put("uid", config.getUserId());
                ocPayload.put("exch", segment);
                ocPayload.put(
                        "tsym",
                        futSymbol != null && !futSymbol.isBlank() ? futSymbol : cleanUnderlying);
                ocPayload.put("strprc", atmStrike.stripTrailingZeros().toPlainString());
                // GetOptionChain centres its window one strike low: it honours `cnt` rows below
                // ATM but only `cnt - 1` above it, so cnt=4 yields ATM-4..ATM+3 rather than the
                // symmetric ATM±4 the parameter implies. Ask for one extra strike and re-centre
                // the window in code (see below) so both sides carry the same count.
                ocPayload.put("cnt", String.valueOf(count + 1));

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
                    if (respBody != null && respBody.contains("Invalid Trading Symbol")) {
                        log.warn(
                                "Option chain not directly available for underlying {} (HTTP {}): {}",
                                futSymbol,
                                resp.statusCode(),
                                respBody);
                    } else {
                        log.warn(
                                "HTTP {} fetching OptionChain for {}: {}",
                                resp.statusCode(),
                                futSymbol,
                                respBody);
                    }
                    return mockOptionChain(
                            cleanUnderlying,
                            atmStrike,
                            count,
                            String.format(
                                    "GetOptionChain HTTP %d for tsym=%s",
                                    resp.statusCode(), futSymbol));
                }

                JsonNode root = objectMapper.readTree(respBody);

                if (!"Ok".equalsIgnoreCase(root.path("stat").asText())) {
                    String emsg = root.path("emsg").asText(respBody);
                    log.warn("GetOptionChain API returned non-Ok for {}: {}", futSymbol, emsg);
                    return mockOptionChain(
                            cleanUnderlying,
                            atmStrike,
                            count,
                            String.format(
                                    "GetOptionChain non-Ok for tsym=%s: %s", futSymbol, emsg));
                }

                JsonNode values = root.path("values");
                if (!values.isArray() || values.isEmpty()) {
                    // The request succeeded but returned nothing usable, so this is just as
                    // unavailable as a failed fetch. Returning it via the live constructor left
                    // synthetic=false with PCR 0.0 and an empty strike list, which served a
                    // fabricated reading to the REST callers as if it were market data.
                    return mockOptionChain(
                            cleanUnderlying,
                            atmStrike,
                            count,
                            String.format(
                                    Locale.US,
                                    "GetOptionChain returned no strikes for tsym=%s",
                                    futSymbol));
                }

                // 3. Group by strike.
                //
                // A TreeMap orders BigDecimal by compareTo (numeric value). HashMap would use
                // BigDecimal's equals/hashCode, which is scale-sensitive — "22850" and "22850.00"
                // are different keys — so one strike's CE and PE could be split into two rows,
                // leaving a call with no put beside a put with no call.
                Map<BigDecimal, OptionContractDraft> strikeMap = new TreeMap<>();

                for (JsonNode item : values) {
                    BigDecimal strikePrice = new BigDecimal(item.path("strprc").asText("0"));
                    String optType = item.path("optt").asText("");
                    String token = item.path("token").asText("");
                    String tsym = item.path("tsym").asText("");

                    OptionContractDraft draft =
                            new OptionContractDraft(tsym, token, optType, strikePrice);

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

                // 3b. Re-centre onto a symmetric ATM ± count window.
                //
                // GetOptionChain honours `cnt` strikes below ATM but only `cnt - 1` above it, so
                // the raw chain runs ATM-4..ATM+3 rather than ATM±4. The window is cut here rather
                // than trusted from the API: without this the read carries an extra ITM call
                // strike and drops an OTM one, and since ITM calls hold the heaviest OI the bias
                // pushes call OI — and therefore PCR — systematically low, toward bearish.
                List<BigDecimal> allStrikes = new ArrayList<>(strikeMap.keySet());
                int atmIndex = 0;
                for (int i = 0; i < allStrikes.size(); i++) {
                    if (allStrikes.get(i).compareTo(atmStrike) <= 0) {
                        atmIndex = i;
                    } else {
                        break;
                    }
                }
                int windowFrom = Math.max(0, atmIndex - count);
                int windowTo = Math.min(allStrikes.size() - 1, atmIndex + count);
                List<BigDecimal> sortedStrikes =
                        new ArrayList<>(allStrikes.subList(windowFrom, windowTo + 1));

                // Only the window's contracts are quoted, so a wide chain costs no more than a
                // narrow one.
                List<OptionContractDraft> allContracts = new ArrayList<>();
                for (BigDecimal sp : sortedStrikes) {
                    OptionContractDraft draft = strikeMap.get(sp);
                    if (draft.callDraft != null) {
                        allContracts.add(draft.callDraft);
                    }
                    if (draft.putDraft != null) {
                        allContracts.add(draft.putDraft);
                    }
                }

                // 4. Fetch quotes for the window's option contracts. The batch is drained through
                //    the shared quote slot machine (see fetchQuote), and whatever still comes back
                //    without usable data gets one paced retry before the window is declared
                //    unusable — a single throttled row must not silently narrow the sample the
                //    ratio is computed from, but a transient hiccup also must not fabricate one.
                AtomicInteger quotesSucceeded = new AtomicInteger();
                if (fetchQuotes) {
                    awaitQuoteBatch(allContracts, sessionToken, segment, quotesSucceeded);

                    List<OptionContractDraft> stragglers =
                            allContracts.stream().filter(d -> !d.quoteReceived).toList();
                    if (!stragglers.isEmpty()) {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        awaitQuoteBatch(stragglers, sessionToken, segment, quotesSucceeded);
                    }

                    // Fail closed. A window missing quotes is not a narrower window, it is a
                    // wrong ratio: the synthetic chain returned here is flagged, so every
                    // downstream gate clears PCR and the S&R strikes rather than trading on a
                    // Put-Call Ratio computed from a partial sample of open interest.
                    int expectedQuotes = allContracts.size();
                    if (quotesSucceeded.get() < expectedQuotes) {
                        return mockOptionChain(
                                cleanUnderlying,
                                atmStrike,
                                count,
                                String.format(
                                        Locale.US,
                                        "only %d of %d window quotes returned open interest for tsym=%s",
                                        quotesSucceeded.get(),
                                        expectedQuotes,
                                        futSymbol));
                    }
                }

                // 5. Construct sorted OptionStrike list and calculate aggregate OI

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

                    // Flag the ATM by comparing against the anchor strike directly. Gating this on
                    // underlyingPrice>0 meant that when futures-contract resolution failed (and
                    // so no quote was fetched), no strike was flagged ATM at all — even though
                    // atmStrike is always resolved, via explicitStrike or the default fallback.
                    boolean isAtm = sp.compareTo(atmStrike) == 0;
                    strikeList.add(new OptionStrike(sp, isAtm, call, put));
                }

                // Kept unrounded on purpose: the same field drives the entry gate, and rounding
                // here made a true 0.845 read as 0.85 — inside the neutral band — purely for
                // display reasons. Consumers that show the number round it themselves.
                double pcr = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0.0;

                return new OptionChainResponse(
                        cleanUnderlying,
                        underlyingPrice,
                        atmStrike,
                        futSymbol,
                        strikeList.size(),
                        totalCallOi,
                        totalPutOi,
                        pcr,
                        strikeList);

            } catch (Exception e) {
                lastFailure =
                        String.format(
                                "attempt %d failed for tsym=%s: %s",
                                attempt, futSymbol, e.getMessage());
                log.warn(
                        "Option chain fetch attempt {} failed for {}: {}",
                        attempt,
                        futSymbol,
                        e.getMessage(),
                        e);
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
                            count,
                            lastFailure);
                }
            }
        }
        return mockOptionChain(
                cleanUnderlying,
                explicitStrike != null ? explicitStrike : defaultSpotPrice(cleanUnderlying),
                count,
                lastFailure);
    }

    /**
     * Fetches {@code GetQuotes} for every contract in {@code contracts} through the shared quote
     * slot machine and waits for the batch to settle (or until the timeout elapses — the coverage
     * guard in the caller still decides usability either way).
     *
     * @param contracts the window drafts to quote
     * @param sessionToken the authenticated session key
     * @param segment the exchange segment, e.g. {@code NFO}
     * @param quotesSucceeded counter of contracts filled with a usable quote, incremented in place
     */
    private void awaitQuoteBatch(
            List<OptionContractDraft> contracts,
            String sessionToken,
            String segment,
            AtomicInteger quotesSucceeded) {
        List<CompletableFuture<Void>> futures =
                contracts.stream()
                        .map(
                                draft ->
                                        CompletableFuture.runAsync(
                                                () ->
                                                        fetchWindowQuote(
                                                                draft,
                                                                sessionToken,
                                                                segment,
                                                                quotesSucceeded),
                                                executor))
                        .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn(
                    "Option chain quote batch fetch timed out or interrupted: {}",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Quotes one window contract and folds the response into its draft. A row is only accepted when
     * {@code stat} is {@code Ok} and it carries {@code oi}: a missing {@code stat} must fail rather
     * than default to Ok (an empty or throttled payload used to read as success and land in the
     * aggregate with {@code oi=0}), and a row that parses but carries no {@code oi} cannot be told
     * apart from a genuine zero, so it is rejected and the coverage guard decides whether the
     * window can still be trusted.
     *
     * @param draft the contract draft to fill in place
     * @param sessionToken the authenticated session key
     * @param segment the exchange segment, e.g. {@code NFO}
     * @param quotesSucceeded counter incremented when the draft is filled
     */
    private void fetchWindowQuote(
            OptionContractDraft draft,
            String sessionToken,
            String segment,
            AtomicInteger quotesSucceeded) {
        try {
            JsonNode q = fetchQuote(sessionToken, segment, draft.token);
            String stat = q == null ? "" : q.path("stat").asText("");
            if (!"Ok".equalsIgnoreCase(stat)) {
                return;
            }
            if (!q.has("oi")) {
                return;
            }
            draft.ltp = new BigDecimal(q.path("lp").asText("0"));
            draft.openInterest = q.path("oi").asLong(0);
            draft.volume = q.path("v").asLong(0);
            draft.bidPrice = new BigDecimal(q.path("bp1").asText("0"));
            draft.askPrice = new BigDecimal(q.path("sp1").asText("0"));
            draft.previousClose = new BigDecimal(q.path("c").asText("0"));
            draft.quoteReceived = true;
            quotesSucceeded.incrementAndGet();
        } catch (Exception e) {
            log.debug("Quote fetch failed for token {}: {}", draft.token, e.getMessage(), e);
        }
    }

    /**
     * Reserves the next global slot for a GetQuotes request and parks the caller until its turn.
     *
     * <p>The slot machine is shared by every quote this service issues, so N concurrent chain reads
     * present their requests at the capped aggregate rate instead of N times it — which is what
     * keeps the broker from answering {@code stat=Ok} while silently dropping {@code oi}. The first
     * caller proceeds immediately; an interrupted caller skips the wait and lets the coverage guard
     * decide the outcome.
     */
    private void awaitQuoteSlot() {
        long deadline;
        synchronized (quoteSlotLock) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextQuoteSlotNanos);
            nextQuoteSlotNanos = slot + QUOTE_MIN_INTERVAL_NANOS;
            deadline = slot;
        }
        while (!Thread.currentThread().isInterrupted()) {
            long waitNanos = deadline - System.nanoTime();
            if (waitNanos <= 0) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(waitNanos);
        }
    }

    private JsonNode fetchQuote(String sessionToken, String exchange, String token) {
        try {
            awaitQuoteSlot();
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
            log.debug("GetQuotes API threw exception for token {}: {}", token, e.getMessage(), e);
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

    /**
     * Fabricated fallback chain used when the live fetch cannot be satisfied.
     *
     * <p>The generated call/put OI ladders are exact mirror images of each other, so the resulting
     * Put-Call Ratio is always exactly {@code 1.00}. The payload is therefore flagged {@code
     * synthetic} and logged at WARN so callers can fail closed instead of reading a fabricated 1.00
     * as a genuine neutral signal.
     *
     * @param underlying the underlying symbol
     * @param atmStrike the strike the synthetic ladder is centred on
     * @param count number of strikes either side of ATM
     * @param reason why the live fetch was abandoned
     * @return a synthetic chain response
     */
    private OptionChainResponse mockOptionChain(
            String underlying, BigDecimal atmStrike, int count, String reason) {
        log.warn(
                "[OPTION-CHAIN] SYNTHETIC chain returned for {} (ATM={}, ±{}): {}. "
                        + "PCR is fabricated and always 1.00 — downstream gates must fail closed.",
                underlying,
                atmStrike,
                count,
                reason);
        List<OptionStrike> list = new ArrayList<>();
        BigDecimal strikeStep = StockFnoRegistry.getStrikeStep(underlying, atmStrike);
        long totalCallOi = 0;
        long totalPutOi = 0;

        LocalDate today = LocalDate.now(IST);
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

            long callOi =
                    (i >= 0) ? (50000L + (i * 20000L)) : Math.max(5000L, 50000L + (i * 10000L));
            long putOi =
                    (i <= 0)
                            ? (50000L + (Math.abs(i) * 20000L))
                            : Math.max(5000L, 50000L - (i * 10000L));

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
                list,
                true);
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
            log.debug("[OPTION-CHAIN] Error shutting down executor: {}", e.getMessage(), e);
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

        /** Set once this contract's quote came back with a usable (stat-Ok, oi-carrying) row. */
        volatile boolean quoteReceived = false;

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

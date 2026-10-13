package com.tradingbot.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LIVE integration probe against the real Shoonya (Finvasia NorenAPI) endpoint.
 *
 * <p>Reads an option chain across ATM ± {@link ShoonyaOptionChainService#PCR_STRIKE_WINDOW} strikes
 * and derives the Put-Call Ratio from aggregate open interest, printing the raw window, the derived
 * metrics, data-quality diagnostics and the LVR entry-gate verdict the PCR would produce.
 *
 * <p>Needs a {@code .env} with working Shoonya credentials and a whitelisted public IP. When they
 * are missing the test <em>aborts</em> (JUnit assumption) instead of failing, so a build without
 * live access still stays green.
 */
@Tag("live")
@DisplayName("LIVE: option-chain PCR across ATM ± 4 strikes (real Shoonya API)")
class OptionChainPcrLiveTest {

    /** The production PCR window — 4 strikes either side of ATM, i.e. a 9-strike chain. */
    private static final int WINDOW = ShoonyaOptionChainService.PCR_STRIKE_WINDOW;

    /** LVR neutral no-trade band; PCR inside it denies both directions. */
    private static final double NEUTRAL_MIN = 0.85;

    private static final double NEUTRAL_MAX = 1.15;

    private static final String RULE = "=".repeat(118);

    private ShoonyaMarketDataService marketDataService;
    private ShoonyaOptionChainService optionChainService;

    /** Wires up the real (non-mocked) Shoonya stack from {@code .env}. */
    @BeforeEach
    void connectToShoonya() {
        ShoonyaConfig config = ShoonyaConfig.load();
        assumeTrue(config.isEnabled(), "SHOONYA_ENABLED=false — skipping live option-chain call");
        assumeTrue(
                !config.getUserId().isBlank() && !config.getPassword().isBlank(),
                "No Shoonya credentials in .env — skipping live option-chain call");
        assumeTrue(!config.getBaseUrl().isBlank(), "No Shoonya base URL — skipping live call");

        ShoonyaAuthenticator authenticator = new ShoonyaAuthenticator(config);
        marketDataService = new ShoonyaMarketDataService(config, authenticator);
        optionChainService =
                new ShoonyaOptionChainService(config, authenticator, marketDataService);
    }

    @Test
    @DisplayName("NIFTY 50 PCR (ATM ± 4) returns live, non-synthetic exchange data")
    void nifty50PcrAtmPlusMinusFour() {
        runProbe("NIFTY");
    }

    @Test
    @DisplayName("TCS PCR (ATM ± 4) returns live, non-synthetic exchange data")
    void tcsPcrAtmPlusMinusFour() {
        runProbe("TCS");
    }

    @Test
    @DisplayName("Window sizing: what GetOptionChain's 'cnt' actually returns")
    void windowSizingProbe() {
        System.out.println(RULE);
        System.out.println(" LIVE: GetOptionChain 'cnt' semantics (NIFTY, quotes skipped)");
        System.out.println(RULE);
        System.out.printf(
                Locale.US,
                "  %4s  %6s  %11s  %11s  %11s  %11s%n",
                "cnt",
                "rows",
                "min strike",
                "max strike",
                "below ATM",
                "above ATM");
        System.out.println("  " + "-".repeat(70));

        for (int cnt : new int[] {2, 3, 4, 5, 6}) {
            OptionChainResponse chain = optionChainService.getNifty50OptionChain(null, cnt, false);
            List<OptionStrike> strikes = chain.strikes();
            BigDecimal min =
                    strikes.stream()
                            .map(OptionStrike::strikePrice)
                            .min(BigDecimal::compareTo)
                            .orElse(null);
            BigDecimal max =
                    strikes.stream()
                            .map(OptionStrike::strikePrice)
                            .max(BigDecimal::compareTo)
                            .orElse(null);
            BigDecimal atm = chain.atmStrike();
            long below = strikes.stream().filter(s -> s.strikePrice().compareTo(atm) < 0).count();
            long above = strikes.stream().filter(s -> s.strikePrice().compareTo(atm) > 0).count();

            System.out.printf(
                    Locale.US,
                    "  %4d  %6d  %11s  %11s  %11d  %11d%n",
                    cnt,
                    strikes.size(),
                    fmt(min),
                    fmt(max),
                    below,
                    above);
        }
        System.out.println(RULE);
    }

    /**
     * Fetches and reports the ATM ± {@link #WINDOW} option chain for one underlying, then asserts
     * the result is real exchange data rather than the fabricated fallback.
     *
     * @param underlying the symbol to probe, e.g. {@code NIFTY} or {@code TCS}
     */
    private void runProbe(String underlying) {
        System.out.println(RULE);
        System.out.println(
                " LIVE: "
                        + underlying
                        + " PUT-CALL RATIO — ATM ± "
                        + WINDOW
                        + "  (expected "
                        + (2 * WINDOW + 1)
                        + " strikes)");
        System.out.println(RULE);

        // --- 1. Which contract anchors the chain? --------------------------------
        ShoonyaMarketDataService.FuturesContract contract =
                marketDataService.resolveFuturesContract(underlying);
        System.out.printf(
                Locale.US,
                "%-26s: %s%n",
                "Resolved " + underlying + " futures",
                contract != null
                        ? contract.tsym() + " (token " + contract.token() + ")"
                        : "<none — falling back to the cash symbol>");

        // --- 2. Fetch the chain ---------------------------------------------------
        OptionChainResponse chain =
                optionChainService.getIndexOptionChain(underlying, null, WINDOW, true);

        System.out.printf(Locale.US, "%-26s: %s%n", "Trading symbol", chain.tradingSymbol());
        System.out.printf(Locale.US, "%-26s: %s%n", "Underlying price", chain.underlyingPrice());
        System.out.printf(Locale.US, "%-26s: %s%n", "ATM strike", chain.atmStrike());
        System.out.printf(Locale.US, "%-26s: %d%n", "Strikes returned", chain.strikes().size());
        System.out.printf(
                Locale.US,
                "%-26s: %s   <- false means live exchange data%n",
                "Synthetic (fabricated)",
                chain.isSynthetic());
        assumeTrue(
                !chain.isSynthetic(),
                "Shoonya returned a fabricated chain — live access unavailable "
                        + "(is this IP whitelisted / the session valid?). Aborting probe.");

        // --- 3. Strike-by-strike window -------------------------------------------
        System.out.println();
        System.out.printf(
                Locale.US,
                "  %2s  %9s  %2s  %13s  %13s  %9s  %13s  %13s  %9s%n",
                "#",
                "Strike",
                "",
                "CE OI",
                "CE Volume",
                "CE LTP",
                "PE OI",
                "PE Volume",
                "PE LTP");
        System.out.println("  " + "-".repeat(110));

        long callOi = 0;
        long putOi = 0;
        long callVol = 0;
        long putVol = 0;
        int index = 0;
        for (OptionStrike strike : chain.strikes()) {
            index++;
            OptionContract call = strike.call();
            OptionContract put = strike.put();

            long thisCallOi = call != null ? call.openInterest() : 0L;
            long thisPutOi = put != null ? put.openInterest() : 0L;
            long thisCallVol = call != null ? call.volume() : 0L;
            long thisPutVol = put != null ? put.volume() : 0L;
            callOi += thisCallOi;
            putOi += thisPutOi;
            callVol += thisCallVol;
            putVol += thisPutVol;

            System.out.printf(
                    Locale.US,
                    "  %2d  %9s  %-2s  %,13d  %,13d  %9s  %,13d  %,13d  %9s%n",
                    index,
                    fmt(strike.strikePrice()),
                    strike.isAtm() ? "AT" : "",
                    thisCallOi,
                    thisCallVol,
                    call != null ? fmt(call.ltp()) : "-",
                    thisPutOi,
                    thisPutVol,
                    put != null ? fmt(put.ltp()) : "-");
        }
        System.out.println("  " + "-".repeat(110));

        // --- 4. Derived metrics ----------------------------------------------------
        double recomputedPcrOi = ratio(putOi, callOi);

        System.out.printf(Locale.US, "%-26s: %,d%n", "Total Call OI", callOi);
        System.out.printf(Locale.US, "%-26s: %,d%n", "Total Put OI", putOi);
        System.out.printf(Locale.US, "%-26s: %,d%n", "Total Call Volume", callVol);
        System.out.printf(Locale.US, "%-26s: %,d%n", "Total Put Volume", putVol);
        System.out.printf(
                Locale.US,
                "%-26s: %.2f   (recomputed independently: %.2f)%n",
                "PCR (OI)",
                chain.pcr(),
                recomputedPcrOi);
        System.out.printf(Locale.US, "%-26s: %.2f%n", "PCR (Volume)", ratio(putVol, callVol));
        System.out.printf(
                Locale.US,
                "%-26s: %s  (resistance)%n",
                "Max Call OI strike",
                fmt(chain.maxCallOiStrike()));
        System.out.printf(
                Locale.US,
                "%-26s: %s  (support)%n",
                "Max Put OI strike",
                fmt(chain.maxPutOiStrike()));

        boolean looksFabricated = chain.pcr() == 1.00 && callOi == putOi;
        System.out.printf(
                Locale.US,
                "%-26s: %s%n",
                "Fabricated signature",
                looksFabricated
                        ? "MATCHES the old mock (PCR 1.00 + equal OI) — NOT live data"
                        : "clear");

        // --- 5. Data-quality diagnostics --------------------------------------------
        List<String> anomalies = diagnose(chain, callOi, putOi);
        System.out.println();
        System.out.println("  Data-quality diagnostics:");
        if (anomalies.isEmpty()) {
            System.out.println("    none — window looks clean");
        } else {
            anomalies.forEach(a -> System.out.println("    ! " + a));
        }

        // --- 6. Entry-gate verdict under the 0.85–1.15 neutral band -------------------
        System.out.println();
        System.out.println(
                "  LVR entry gate (neutral no-trade band "
                        + NEUTRAL_MIN
                        + " – "
                        + NEUTRAL_MAX
                        + "):");
        System.out.printf(Locale.US, "    LONG : %s%n", longVerdict(chain.pcr()));
        System.out.printf(Locale.US, "    SHORT: %s%n", shortVerdict(chain.pcr()));
        System.out.println(RULE);

        // --- 7. Assertions ------------------------------------------------------------
        assertThat(chain.strikes())
                .as("strike window for %s must not be empty", underlying)
                .isNotEmpty();
        assertThat(chain.strikes().size())
                .as("ATM ± %d should return at most %d strikes", WINDOW, 2 * WINDOW + 1)
                .isLessThanOrEqualTo(2 * WINDOW + 1);
        assertThat(callOi).as("%s must carry call OI", underlying).isPositive();
        assertThat(putOi).as("%s must carry put OI", underlying).isPositive();
        assertThat(callOi)
                .as("recomputed call OI must match the reported total")
                .isEqualTo(chain.totalCallOi());
        assertThat(putOi)
                .as("recomputed put OI must match the reported total")
                .isEqualTo(chain.totalPutOi());
        assertThat(chain.pcr())
                .as("reported PCR must equal Put/Call OI over the ATM ± %d window", WINDOW)
                .isEqualTo(recomputedPcrOi);
    }

    /**
     * Collects human-readable data-quality findings: a short window, contracts with no open
     * interest, quotes that cannot possibly be option premiums, and a degenerate S&amp;R pair.
     *
     * @param chain the chain under inspection
     * @param callOi aggregate call open interest across the window
     * @param putOi aggregate put open interest across the window
     * @return one message per detected anomaly
     */
    private static List<String> diagnose(OptionChainResponse chain, long callOi, long putOi) {
        List<String> out = new ArrayList<>();

        int expected = 2 * WINDOW + 1;
        if (chain.strikes().size() != expected) {
            out.add(
                    String.format(
                            Locale.US,
                            "window has %d strikes, expected %d (ATM ± %d) — one side is short",
                            chain.strikes().size(),
                            expected,
                            WINDOW));
        }
        if (chain.strikes().stream().noneMatch(OptionStrike::isAtm)) {
            out.add("no strike flagged as ATM — underlying price/ATM anchoring failed");
        }

        BigDecimal underlying = chain.underlyingPrice();
        for (OptionStrike strike : chain.strikes()) {
            checkContract(out, "CE", strike, strike.call(), underlying);
            checkContract(out, "PE", strike, strike.put(), underlying);
        }

        if (callOi > 0
                && putOi > 0
                && chain.maxCallOiStrike() != null
                && chain.maxCallOiStrike().compareTo(chain.maxPutOiStrike()) == 0) {
            out.add(
                    String.format(
                            Locale.US,
                            "Max Call OI and Max Put OI land on the same strike (%s) — "
                                    + "the OPTION_SR gate gets no separation between "
                                    + "resistance and support",
                            fmt(chain.maxCallOiStrike())));
        }
        return out;
    }

    /**
     * Flags a single contract whose quote looks impossible for an option.
     *
     * @param out accumulator of anomaly messages
     * @param side {@code CE} or {@code PE}, for the message text
     * @param strike the enclosing strike
     * @param contract the contract to inspect, may be {@code null}
     * @param underlying the underlying price the premium is compared against
     */
    private static void checkContract(
            List<String> out,
            String side,
            OptionStrike strike,
            OptionContract contract,
            BigDecimal underlying) {
        if (contract == null) {
            return;
        }
        String label = fmt(strike.strikePrice()) + " " + side + " (" + contract.symbol() + ")";
        if (contract.openInterest() <= 0) {
            out.add(label + ": open interest is " + contract.openInterest());
        }
        if (underlying != null
                && contract.ltp() != null
                && contract.ltp().compareTo(underlying) > 0) {
            out.add(
                    String.format(
                            Locale.US,
                            "%s: LTP %s exceeds the underlying price %s — quote/token mismatch",
                            label,
                            fmt(contract.ltp()),
                            fmt(underlying)));
        }
    }

    /** LONG side of the neutral-band gate, mirroring {@code evaluateEntryGate}. */
    private static String longVerdict(double pcr) {
        if (pcr > NEUTRAL_MAX) {
            return String.format(
                    Locale.US, "ALLOWED — PCR %.2f > %.2f (bullish)", pcr, NEUTRAL_MAX);
        }
        if (pcr >= NEUTRAL_MIN) {
            return String.format(
                    Locale.US,
                    "BLOCKED — PCR %.2f is inside the neutral no-trade band [%.2f, %.2f]",
                    pcr,
                    NEUTRAL_MIN,
                    NEUTRAL_MAX);
        }
        return String.format(Locale.US, "BLOCKED — PCR %.2f is bearish, not confirmed", pcr);
    }

    /** SHORT side of the neutral-band gate, mirroring {@code evaluateEntryGate}. */
    private static String shortVerdict(double pcr) {
        if (pcr < NEUTRAL_MIN) {
            return String.format(
                    Locale.US, "ALLOWED — PCR %.2f < %.2f (bearish)", pcr, NEUTRAL_MIN);
        }
        if (pcr <= NEUTRAL_MAX) {
            return String.format(
                    Locale.US,
                    "BLOCKED — PCR %.2f is inside the neutral no-trade band [%.2f, %.2f]",
                    pcr,
                    NEUTRAL_MIN,
                    NEUTRAL_MAX);
        }
        return String.format(Locale.US, "BLOCKED — PCR %.2f is bullish, not confirmed", pcr);
    }

    /** Rounds a ratio the same way {@code OptionChainResponse} does — 2 decimal places. */
    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(((double) numerator / denominator) * 100.0) / 100.0;
    }

    /** Null-safe decimal formatter for strikes and premiums. */
    private static String fmt(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}

package com.tradingbot.strategy.commodity.service;

import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.feed.CommodityQuoteFeed;
import com.tradingbot.strategy.commodity.model.CommodityBias;
import com.tradingbot.strategy.commodity.model.CommodityCostModel;
import com.tradingbot.strategy.commodity.model.CommodityNewsCalendar;
import com.tradingbot.strategy.commodity.model.CommoditySetup;
import com.tradingbot.strategy.commodity.model.CommoditySetupState;
import com.tradingbot.strategy.commodity.model.CommodityStatusReport;
import com.tradingbot.strategy.commodity.model.CommodityTradePosition;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CommodityRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Service orchestrating the 1:30 PM MCX Commodity Directional PCR & 15m VWAP Breakout Strategy. */
@Service
public class CommodityVwapStrategyService {

    /** Strategy identifier used on the shared trade-signal bus. */
    public static final String STRATEGY_ID = "COMMODITY_VWAP";

    private static final Logger log = LoggerFactory.getLogger(CommodityVwapStrategyService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CommodityVwapProperties properties;
    private final CommodityQuoteFeed quoteFeed;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final Clock clock;
    private final SignalPublisher signalPublisher;

    private final Map<String, CommoditySetup> setups = new ConcurrentHashMap<>();
    private final List<CommodityTradePosition> closedTrades =
            Collections.synchronizedList(new ArrayList<>());

    /** Rolling per-symbol window of daily PCR samples feeding the z-score confirmation veto. */
    private final Map<String, Deque<Double>> pcrHistory = new ConcurrentHashMap<>();

    /** Last calendar date a PCR sample was recorded per symbol (one sample per symbol per day). */
    private final Map<String, LocalDate> lastPcrSampleDate = new ConcurrentHashMap<>();

    /** Most recent computed z-score per symbol, surfaced in the daily Telegram bias digest. */
    private final Map<String, Double> lastPcrZ = new ConcurrentHashMap<>();

    /** Daily max-loss circuit breaker latch; once tripped, stays tripped until session reset. */
    private final java.util.concurrent.atomic.AtomicBoolean dailyCircuitBreakerTripped =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Ensures the circuit-breaker Telegram alert fires only once per trip. */
    private final java.util.concurrent.atomic.AtomicBoolean dailyCircuitBreakerAlertSent =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Ensures the loss-count halt Telegram alert fires only once per day. */
    private final java.util.concurrent.atomic.AtomicBoolean lossCountHaltAlertSent =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Autowired
    public CommodityVwapStrategyService(
            CommodityVwapProperties properties,
            CommodityQuoteFeed quoteFeed,
            TechnicalAnalysisService taService,
            @Autowired(required = false) TelegramService telegramService,
            @Autowired(required = false) SignalPublisher signalPublisher) {
        this(properties, quoteFeed, taService, telegramService, Clock.system(IST), signalPublisher);
    }

    /** Test/replay constructor with an injectable clock (production uses the system IST clock). */
    public CommodityVwapStrategyService(
            CommodityVwapProperties properties,
            CommodityQuoteFeed quoteFeed,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            Clock clock,
            SignalPublisher signalPublisher) {
        this.properties = properties;
        this.quoteFeed = quoteFeed;
        this.taService = taService;
        this.telegramService = telegramService;
        this.clock = clock != null ? clock : Clock.system(IST);
        this.signalPublisher = signalPublisher;
        initializeSetups();
    }

    private void initializeSetups() {
        if (properties.getSymbols() != null) {
            for (String sym : properties.getSymbols()) {
                String clean = sym.trim().toUpperCase();
                setups.put(clean, new CommoditySetup(clean));
            }
        }
    }

    public CommoditySetup getSetup(String symbol) {
        if (symbol == null) return null;
        return setups.computeIfAbsent(symbol.trim().toUpperCase(), CommoditySetup::new);
    }

    /** Triggered at 1:30 PM IST to determine macro directional bias from Put-Call Ratio (PCR). */
    public void evaluateDailyBias() {
        if (!properties.isEnabled()) {
            log.info("[COMMODITY-VWAP] Strategy disabled. Skipping 1:30 PM bias evaluation.");
            return;
        }

        log.info(
                "[COMMODITY-VWAP] Evaluating 1:30 PM daily directional bias across configured commodities...");
        StringBuilder telegramMsg =
                new StringBuilder("📊 *[MCX Commodity Directional Bias - 1:30 PM IST]*\n");
        telegramMsg.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (String symbol : properties.getSymbols()) {
            String clean = symbol.trim().toUpperCase();
            CommoditySetup setup = getSetup(clean);

            try {
                // DTE safety gate: skip contracts inside the MCX physical-tender window.
                if (!isContractDteSafe(clean)) {
                    setup.updateBias(CommodityBias.NEUTRAL, 0.0);
                    telegramMsg.append(
                            String.format(
                                    "⚪ *%s*: Contract near expiry (DTE < %d) ➔ *NEUTRAL*\n",
                                    clean, properties.getMinDteDays()));
                    log.info(
                            "[COMMODITY-VWAP] {} skipped: contract inside min-DTE tender window (minDteDays={}).",
                            clean,
                            properties.getMinDteDays());
                    continue;
                }

                OptionChainResponse chain = quoteFeed != null ? quoteFeed.optionChain(clean) : null;

                if (chain != null && (chain.totalCallOi() > 0 || chain.totalPutOi() > 0)) {
                    double pcr = chain.pcr();
                    CommodityBias bias =
                            CommodityBias.fromPcr(
                                    pcr,
                                    properties.getPcrBullishMin(),
                                    properties.getPcrBearishMax());

                    // Adaptive z-score veto: once enough daily samples exist, a static-threshold
                    // signal must also be unusual relative to the recent PCR regime.
                    String zNote = "";
                    CommodityBias zVetoed = applyPcrZScoreVeto(clean, bias, pcr);
                    if (zVetoed != bias) {
                        zNote =
                                String.format(
                                        " (z-score %.2f vs regime — signal too common)",
                                        lastComputedZ(clean));
                        bias = zVetoed;
                    }
                    recordPcrSample(clean, pcr);
                    setup.updateBias(bias, pcr);

                    String icon =
                            bias == CommodityBias.BULLISH
                                    ? "🟢"
                                    : (bias == CommodityBias.BEARISH ? "🔴" : "⚪");
                    telegramMsg.append(
                            String.format(
                                    "%s *%s*: PCR `%.2f` ➔ *%s*%s\n",
                                    icon, clean, pcr, bias, zNote));
                    log.info("[COMMODITY-VWAP] {} PCR: {} -> Bias: {}{}", clean, pcr, bias, zNote);
                } else {
                    setup.updateBias(CommodityBias.NEUTRAL, 0.0);
                    telegramMsg.append(
                            String.format("⚪ *%s*: Option chain unavailable ➔ *NEUTRAL*\n", clean));
                    log.warn(
                            "[COMMODITY-VWAP] Option chain unavailable for {}. Defaulted to NEUTRAL.",
                            clean);
                }
            } catch (Exception e) {
                setup.updateBias(CommodityBias.NEUTRAL, 0.0);
                telegramMsg.append(
                        String.format("⚠️ *%s*: Error reading PCR ➔ *NEUTRAL*\n", clean));
                log.error(
                        "[COMMODITY-VWAP] Error evaluating bias for {}: {}",
                        clean,
                        e.getMessage(),
                        e);
            }
        }

        telegramMsg.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        telegramMsg
                .append("15-min VWAP crossover scanner active until ")
                .append(properties.getEntryCutoff())
                .append(" IST.");

        if (properties.isTelegramAlertsEnabled() && telegramService != null) {
            telegramService.sendTextMessage(telegramMsg.toString());
        }
    }

    /**
     * True when the near-month contract is outside the min-DTE physical-tender window, or when the
     * expiry cannot be resolved (missing data must not block trading).
     */
    private boolean isContractDteSafe(String symbol) {
        if (properties.getMinDteDays() <= 0) return true;
        LocalDate expiry = quoteFeed != null ? quoteFeed.contractExpiry(symbol) : null;
        if (expiry == null) return true;
        long dte = ChronoUnit.DAYS.between(LocalDate.now(clock), expiry);
        return dte >= properties.getMinDteDays();
    }

    /**
     * Adaptive PCR confirmation: once the rolling window holds at least {@code minSamples} prior
     * daily readings, a static-threshold bias is vetoed to NEUTRAL unless the reading is also
     * unusual relative to the recent PCR regime (z-score beyond {@code zBullish}/{@code zBearish}).
     */
    private CommodityBias applyPcrZScoreVeto(String symbol, CommodityBias bias, double pcr) {
        CommodityVwapProperties.PcrZScore cfg = properties.getPcrZScore();
        if (cfg == null || !cfg.isEnabled() || bias == CommodityBias.NEUTRAL) return bias;
        Double z = computePcrZScore(symbol, pcr);
        if (z == null) return bias; // bootstrap phase: not enough history yet
        lastPcrZ.put(symbol, z);
        if (bias == CommodityBias.BULLISH && z < cfg.getZBullish()) return CommodityBias.NEUTRAL;
        if (bias == CommodityBias.BEARISH && z > cfg.getZBearish()) return CommodityBias.NEUTRAL;
        return bias;
    }

    /**
     * Z-score of {@code pcr} against the symbol's prior daily samples (current sample excluded).
     * Returns null when fewer than {@code minSamples} prior samples exist. A near-zero standard
     * deviation yields z = 0 (no unusual deviation).
     */
    private Double computePcrZScore(String symbol, double pcr) {
        CommodityVwapProperties.PcrZScore cfg = properties.getPcrZScore();
        if (cfg == null) return null;
        Deque<Double> window = pcrHistory.get(symbol);
        if (window == null) return null;
        double[] samples;
        synchronized (window) {
            samples = window.stream().mapToDouble(Double::doubleValue).toArray();
        }
        if (samples.length < cfg.getMinSamples()) return null;
        double mean = 0.0;
        for (double v : samples) mean += v;
        mean /= samples.length;
        double variance = 0.0;
        for (double v : samples) variance += (v - mean) * (v - mean);
        variance /= Math.max(1, samples.length - 1);
        double stddev = Math.sqrt(variance);
        if (stddev < 1e-9) {
            // Flat window: no true z exists. An unchanged reading is neutral (0); any deviation is
            // treated as moderately unusual (+-1.0) so fresh breakouts still confirm.
            if (Math.abs(pcr - mean) < 1e-9) return 0.0;
            return pcr > mean ? 1.0 : -1.0;
        }
        return (pcr - mean) / stddev;
    }

    /** Appends today's PCR sample (at most one per symbol per calendar day) to the window. */
    private void recordPcrSample(String symbol, double pcr) {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(lastPcrSampleDate.put(symbol, today))) {
            return; // already sampled today (e.g. manual re-run of the 13:30 scan)
        }
        Deque<Double> window = pcrHistory.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(pcr);
            int maxSize = Math.max(1, properties.getPcrZScore().getWindowSize());
            while (window.size() > maxSize) window.removeFirst();
        }
    }

    private double lastComputedZ(String symbol) {
        Double z = lastPcrZ.get(symbol);
        return z != null ? z : 0.0;
    }

    /**
     * Executes the strategy cycle for all configured symbols (typically run on 15m candle close).
     */
    public void evaluateStrategyCycle() {
        if (!properties.isEnabled()) return;
        LocalTime nowTime = LocalTime.now(clock);
        for (String symbol : properties.getSymbols()) {
            evaluateSymbolCycle(symbol.trim().toUpperCase(), nowTime);
        }
    }

    /**
     * Manages open positions only (target/SL checks and trailing) without arming or entering new
     * trades. Invoked by the 1-minute scheduler loop so exits are enforced between 15m bars,
     * limiting stop-loss gap-through risk.
     */
    public void manageActiveTrades() {
        if (!properties.isEnabled()) return;
        for (CommoditySetup setup : setups.values()) {
            if (setup.getState() == CommoditySetupState.IN_TRADE) {
                manageActiveTrade(setup);
            }
        }
    }

    /**
     * Evaluates a single symbol for crossover arming, breakout entry, or active position
     * management.
     */
    public void evaluateSymbolCycle(String symbol, LocalTime nowTime) {
        CommoditySetup setup = getSetup(symbol);
        if (setup == null || setup.getState() == CommoditySetupState.SKIPPED) {
            return;
        }

        // 1. Manage active in-trade position
        if (setup.getState() == CommoditySetupState.IN_TRADE) {
            manageActiveTrade(setup);
            return;
        }

        // 2. Check if already completed max trades for the day
        if (setup.getTradesToday() >= properties.getMaxTradesPerSymbol()
                || setup.getState() == CommoditySetupState.COMPLETED) {
            return;
        }

        // 3. Breakout Execution check if ARMED (within liquidity session window)
        if (!nowTime.isBefore(properties.getEntryStartTime())
                && nowTime.isBefore(properties.getEntryCutoff())) {
            if (setup.getState() == CommoditySetupState.ARMED_LONG
                    || setup.getState() == CommoditySetupState.ARMED_SHORT) {
                checkBreakoutExecution(setup, nowTime);
                if (setup.getState() == CommoditySetupState.IN_TRADE) {
                    return;
                }
            }

            // 4. VWAP Crossover Check
            checkVwapCrossover(setup);
        }
    }

    private void checkVwapCrossover(CommoditySetup setup) {
        if (setup.getBias() == CommodityBias.NEUTRAL) return;

        try {
            List<Candle> candles =
                    quoteFeed != null ? quoteFeed.candles15m(setup.getSymbol(), 2) : null;
            if (candles == null || candles.size() < 2) {
                log.debug("[COMMODITY-VWAP] Insufficient 15m candles for {}", setup.getSymbol());
                return;
            }

            double[] vwapSeries = taService != null ? taService.calculateVwapSeries(candles) : null;
            if (vwapSeries == null || vwapSeries.length < 2) return;

            int lastIdx = candles.size() - 1;
            Candle prevCandle = candles.get(lastIdx - 1);
            Candle currCandle = candles.get(lastIdx);

            double prevVwap = vwapSeries[lastIdx - 1];
            double currVwap = vwapSeries[lastIdx];

            if (Double.isNaN(prevVwap) || Double.isNaN(currVwap)) return;

            // Bullish Crossover: Prev close <= Prev VWAP, Curr close > Curr VWAP
            if (setup.getBias() == CommodityBias.BULLISH) {
                if (prevCandle.close().doubleValue() <= prevVwap
                        && currCandle.close().doubleValue() > currVwap
                        && isVolumeConfirmationPassed(setup.getSymbol(), candles, lastIdx)) {
                    setup.armLong(
                            currCandle.high(), BigDecimal.valueOf(currVwap), Instant.now(clock));
                    log.info(
                            "[COMMODITY-VWAP] {} ARMED LONG: Trigger High={}, VWAP={}",
                            setup.getSymbol(),
                            currCandle.high(),
                            currVwap);
                    sendTelegramArmedAlert(
                            setup, currCandle.high(), BigDecimal.valueOf(currVwap), "LONG");
                }
            }
            // Bearish Crossover: Prev close >= Prev VWAP, Curr close < Curr VWAP
            else if (setup.getBias() == CommodityBias.BEARISH) {
                if (prevCandle.close().doubleValue() >= prevVwap
                        && currCandle.close().doubleValue() < currVwap
                        && isVolumeConfirmationPassed(setup.getSymbol(), candles, lastIdx)) {
                    setup.armShort(
                            currCandle.low(), BigDecimal.valueOf(currVwap), Instant.now(clock));
                    log.info(
                            "[COMMODITY-VWAP] {} ARMED SHORT: Trigger Low={}, VWAP={}",
                            setup.getSymbol(),
                            currCandle.low(),
                            currVwap);
                    sendTelegramArmedAlert(
                            setup, currCandle.low(), BigDecimal.valueOf(currVwap), "SHORT");
                }
            }
        } catch (Exception e) {
            log.error(
                    "[COMMODITY-VWAP] Error checking VWAP crossover for {}: {}",
                    setup.getSymbol(),
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Volume confirmation gate: the arming bar's volume must reach {@code volumeMinRatio} times the
     * average volume of the prior {@code volumeAvgPeriod} bars. Insufficient bars (early session or
     * thin replay data) pass the gate rather than blocking arming.
     */
    private boolean isVolumeConfirmationPassed(String symbol, List<Candle> candles, int armingIdx) {
        if (!properties.isVolumeConfirmationEnabled()) return true;
        int period = properties.getVolumeAvgPeriod();
        if (period <= 0 || candles == null) return true;
        if (candles.size() < period + 1) {
            log.debug(
                    "[COMMODITY-VWAP] {} volume gate passed: only {} bars available (need {}).",
                    symbol,
                    candles.size(),
                    period + 1);
            return true;
        }
        long volumeSum = 0;
        for (int i = armingIdx - period; i < armingIdx; i++) {
            volumeSum += candles.get(i).volume();
        }
        double avgVolume = (double) volumeSum / period;
        if (avgVolume <= 0) return true;
        double ratio = candles.get(armingIdx).volume() / avgVolume;
        boolean passed = ratio >= properties.getVolumeMinRatio();
        if (!passed) {
            log.info(
                    "[COMMODITY-VWAP] {} arming vetoed by volume gate: bar volume {} vs {}-bar avg {} (ratio {} < required {}).",
                    symbol,
                    candles.get(armingIdx).volume(),
                    period,
                    avgVolume,
                    ratio,
                    properties.getVolumeMinRatio());
        }
        return passed;
    }

    private void checkBreakoutExecution(CommoditySetup setup, LocalTime nowTime) {
        // 1. High-Impact News & EIA Inventory Blackout Guard (DST-aware US release windows).
        // Uses the caller-supplied cycle time so replay/backtest runs stay deterministic.
        LocalDate today = LocalDate.now(clock);
        java.time.DayOfWeek dayOfWeek = today.getDayOfWeek();
        if (isMacroNewsBlackout(today, nowTime, dayOfWeek, setup.getSymbol())) {
            log.debug(
                    "[COMMODITY-VWAP] {} breakout entry suppressed due to High-Impact Macro/Inventory blackout window ({} IST).",
                    setup.getSymbol(),
                    nowTime);
            return;
        }

        // 1b. Risk kill-switches: daily max-loss circuit breaker + loss-count halt
        if (isKillSwitchActive()) {
            log.debug(
                    "[COMMODITY-VWAP] {} breakout entry suppressed: kill-switch active (breakerTripped={}, lossesToday={}).",
                    setup.getSymbol(),
                    dailyCircuitBreakerTripped.get(),
                    countTodayLosses());
            return;
        }

        // 2. Check trigger age expiration (e.g. 30 minutes = 2 bars)
        if (setup.getArmedTime() != null && properties.getMaxTriggerAgeMinutes() > 0) {
            long ageMinutes =
                    java.time.Duration.between(setup.getArmedTime(), Instant.now(clock))
                            .toMinutes();
            if (ageMinutes > properties.getMaxTriggerAgeMinutes()) {
                log.info(
                        "[COMMODITY-VWAP] {} trigger expired (age: {} min > max: {} min). Returning to BIAS_IDENTIFIED.",
                        setup.getSymbol(),
                        ageMinutes,
                        properties.getMaxTriggerAgeMinutes());
                setup.setState(CommoditySetupState.BIAS_IDENTIFIED);
                return;
            }
        }

        BigDecimal liveLtp = fetchLiveLtp(setup.getSymbol());
        if (liveLtp == null || liveLtp.compareTo(BigDecimal.ZERO) <= 0) return;

        // 3. Optional EMA Trend Filter confirmation (e.g. 20 EMA)
        if (properties.isEmaTrendFilterEnabled()) {
            Double emaValue = fetchLatestEma(setup.getSymbol(), properties.getEmaPeriod());
            if (emaValue != null && !Double.isNaN(emaValue)) {
                if (setup.getState() == CommoditySetupState.ARMED_LONG
                        && liveLtp.doubleValue() < emaValue) {
                    log.debug(
                            "[COMMODITY-VWAP] {} LONG entry skipped: LTP {} < EMA{} {}",
                            setup.getSymbol(),
                            liveLtp,
                            properties.getEmaPeriod(),
                            emaValue);
                    return;
                }
                if (setup.getState() == CommoditySetupState.ARMED_SHORT
                        && liveLtp.doubleValue() > emaValue) {
                    log.debug(
                            "[COMMODITY-VWAP] {} SHORT entry skipped: LTP {} > EMA{} {}",
                            setup.getSymbol(),
                            liveLtp,
                            properties.getEmaPeriod(),
                            emaValue);
                    return;
                }
            }
        }

        String miniSymbol = CommodityRegistry.toMiniSymbol(setup.getSymbol());
        CommodityRegistry.CommodityMetadata meta = CommodityRegistry.getMetadata(miniSymbol);
        int lotSize = meta != null ? meta.lotSize() : 1;
        BigDecimal rr = BigDecimal.valueOf(properties.getRiskRewardRatio());

        if (setup.getState() == CommoditySetupState.ARMED_LONG && setup.getTriggerHigh() != null) {
            if (liveLtp.compareTo(setup.getTriggerHigh()) >= 0) {
                BigDecimal entryPrice = setup.getTriggerHigh();
                BigDecimal rawStopLoss =
                        setup.getVwapAtSetup() != null
                                ? setup.getVwapAtSetup()
                                : liveLtp.subtract(BigDecimal.ONE);
                BigDecimal risk = entryPrice.subtract(rawStopLoss).abs();
                BigDecimal maxRisk =
                        entryPrice.multiply(BigDecimal.valueOf(properties.getMaxRiskPct()));
                if (properties.getMaxRiskPct() > 0 && risk.compareTo(maxRisk) > 0) {
                    rawStopLoss = entryPrice.subtract(maxRisk);
                    risk = entryPrice.subtract(rawStopLoss).abs();
                }

                if (!passesCostFloor(setup.getSymbol(), miniSymbol, entryPrice, risk, lotSize)) {
                    return;
                }

                CommodityTradePosition pos =
                        CommodityTradePosition.createLong(
                                miniSymbol,
                                entryPrice,
                                rawStopLoss,
                                rr,
                                lotSize,
                                Instant.now(clock));
                if (!publishSignal(
                        setup.getSymbol(),
                        miniSymbol,
                        SignalAction.ENTRY_LONG,
                        entryPrice,
                        pos.stopLoss(),
                        pos.targetPrice(),
                        pos.quantity(),
                        "VWAP_BREAKOUT_ENTRY_LONG",
                        java.util.Map.of(
                                "tradeId",
                                "CVW-" + Instant.now(clock).toEpochMilli(),
                                "brokerStopLossPrice",
                                pos.stopLoss()))) {
                    log.warn(
                            "[COMMODITY-VWAP] {} ENTRY_LONG signal rejected by bus. Staying ARMED (no position opened).",
                            miniSymbol);
                    return;
                }
                setup.enterTrade(pos);

                log.info(
                        "[COMMODITY-VWAP] {} ENTERED LONG @ {}: SL={}, Target={}",
                        miniSymbol,
                        entryPrice,
                        pos.stopLoss(),
                        pos.targetPrice());
                sendTelegramEntryAlert(pos);
            }
        } else if (setup.getState() == CommoditySetupState.ARMED_SHORT
                && setup.getTriggerLow() != null) {
            if (liveLtp.compareTo(setup.getTriggerLow()) <= 0) {
                BigDecimal entryPrice = setup.getTriggerLow();
                BigDecimal rawStopLoss =
                        setup.getVwapAtSetup() != null
                                ? setup.getVwapAtSetup()
                                : liveLtp.add(BigDecimal.ONE);
                BigDecimal risk = rawStopLoss.subtract(entryPrice).abs();
                BigDecimal maxRisk =
                        entryPrice.multiply(BigDecimal.valueOf(properties.getMaxRiskPct()));
                if (properties.getMaxRiskPct() > 0 && risk.compareTo(maxRisk) > 0) {
                    rawStopLoss = entryPrice.add(maxRisk);
                    risk = rawStopLoss.subtract(entryPrice).abs();
                }

                if (!passesCostFloor(setup.getSymbol(), miniSymbol, entryPrice, risk, lotSize)) {
                    return;
                }

                CommodityTradePosition pos =
                        CommodityTradePosition.createShort(
                                miniSymbol,
                                entryPrice,
                                rawStopLoss,
                                rr,
                                lotSize,
                                Instant.now(clock));
                if (!publishSignal(
                        setup.getSymbol(),
                        miniSymbol,
                        SignalAction.ENTRY_SHORT,
                        entryPrice,
                        pos.stopLoss(),
                        pos.targetPrice(),
                        pos.quantity(),
                        "VWAP_BREAKOUT_ENTRY_SHORT",
                        java.util.Map.of(
                                "tradeId",
                                "CVW-" + Instant.now(clock).toEpochMilli(),
                                "brokerStopLossPrice",
                                pos.stopLoss()))) {
                    log.warn(
                            "[COMMODITY-VWAP] {} ENTRY_SHORT signal rejected by bus. Staying ARMED (no position opened).",
                            miniSymbol);
                    return;
                }
                setup.enterTrade(pos);

                log.info(
                        "[COMMODITY-VWAP] {} ENTERED SHORT @ {}: SL={}, Target={}",
                        miniSymbol,
                        entryPrice,
                        pos.stopLoss(),
                        pos.targetPrice());
                sendTelegramEntryAlert(pos);
            }
        }
    }

    /**
     * Cost-of-trading floor: an entry is only taken when the full-position risk (per-unit risk x
     * quantity x unit multiplier) covers at least {@code minRiskToCostRatio} of the estimated
     * round-trip cost. Prevents negative-expectancy micro-risk trades (seen in the 1-month baseline
     * where costs dominated PnL).
     */
    private boolean passesCostFloor(
            String underlying,
            String miniSymbol,
            BigDecimal entryPrice,
            BigDecimal risk,
            int lotSize) {
        if (properties.getMinRiskToCostRatio() <= 0) return true;
        double unitMultiplier = CommodityRegistry.getUnitMultiplier(miniSymbol);
        BigDecimal fullRiskInr =
                risk.multiply(BigDecimal.valueOf(lotSize))
                        .multiply(BigDecimal.valueOf(unitMultiplier));
        BigDecimal roundTripCost =
                CommodityCostModel.from(properties).roundTripCost(miniSymbol, entryPrice, lotSize);
        BigDecimal required =
                roundTripCost.multiply(BigDecimal.valueOf(properties.getMinRiskToCostRatio()));
        if (fullRiskInr.compareTo(required) < 0) {
            log.info(
                    "[COMMODITY-VWAP] {} entry skipped by cost floor: full risk Rs {} < {} x round-trip cost Rs {}.",
                    underlying,
                    fullRiskInr,
                    properties.getMinRiskToCostRatio(),
                    roundTripCost);
            return false;
        }
        return true;
    }

    /**
     * Publishes a signal to the shared execution bus (LVR-style dual track: the internal {@link
     * CommodityTradePosition} ledger remains the paper book; consumers route to PAPER log-only or
     * LIVE broker orders per strategy-mode). A null publisher (not wired) always succeeds.
     */
    private boolean publishSignal(
            String underlying,
            String tradingSymbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int quantity,
            String reason,
            Map<String, Object> extraMetadata) {
        if (signalPublisher == null) return true;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exchange", "MCX");
        metadata.put("instrumentType", "FUTURES");
        metadata.put("orderType", "MKT");
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        TradeSignal signal =
                TradeSignal.of(
                        STRATEGY_ID,
                        underlying,
                        tradingSymbol,
                        action,
                        price,
                        stopLoss,
                        targetPrice,
                        quantity,
                        reason,
                        metadata);
        boolean accepted = signalPublisher.publish(signal);
        if (!accepted) {
            log.error(
                    "[COMMODITY-VWAP] Bus rejected {} signal for {} ({}).",
                    action,
                    tradingSymbol,
                    reason);
        }
        return accepted;
    }

    /**
     * True when a new breakout entry must be suppressed because the clock falls inside a US news
     * blackout window. Windows are DST-aware: the 08:30 ET macro print maps to 18:00 IST (EDT) or
     * 19:00 IST (EST), and Wednesday 10:30 ET EIA maps to 20:00/21:00 IST. Each window spans {@code
     * blackoutHalfWidthMinutes} on each side of the release (default 30 = 60 minutes total).
     */
    public boolean isMacroNewsBlackout(
            LocalDate date, LocalTime time, java.time.DayOfWeek dayOfWeek, String symbol) {
        if (date == null || time == null) return false;
        int halfWidth = properties.getBlackoutHalfWidthMinutes();

        // 1. US macro news releases (NFP, CPI, PPI, Retail Sales) at 08:30 ET
        if (properties.isMacroNewsBlackoutEnabled()) {
            java.time.LocalDateTime macroRelease =
                    CommodityNewsCalendar.usReleaseInIst(date, 8, 30);
            if (CommodityNewsCalendar.withinBlackoutWindow(time, macroRelease, halfWidth)) {
                return true;
            }
        }

        // 2. US EIA Crude Oil Inventory Report, Wednesdays at 10:30 ET, crude symbols only
        if (properties.isEiaInventoryBlackoutEnabled()
                && dayOfWeek == java.time.DayOfWeek.WEDNESDAY) {
            String clean = symbol != null ? symbol.trim().toUpperCase() : "";
            if (clean.contains("CRUDE")) {
                java.time.LocalDateTime eiaRelease =
                        CommodityNewsCalendar.usReleaseInIst(date, 10, 30);
                if (CommodityNewsCalendar.withinBlackoutWindow(time, eiaRelease, halfWidth)) {
                    return true;
                }
            }
        }

        return false;
    }

    private Double fetchLatestEma(String symbol, int period) {
        try {
            List<Candle> candles = quoteFeed != null ? quoteFeed.candles15m(symbol, 5) : null;
            if (candles != null && candles.size() >= period && taService != null) {
                double[] closes =
                        candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] emaSeries = taService.calculateEmaSeries(closes, period);
                if (emaSeries.length > 0 && !Double.isNaN(emaSeries[emaSeries.length - 1])) {
                    return emaSeries[emaSeries.length - 1];
                }
            }
        } catch (Exception e) {
            log.debug("[COMMODITY-VWAP] Error fetching EMA for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    private void manageActiveTrade(CommoditySetup setup) {
        CommodityTradePosition pos = setup.getActivePosition();
        if (pos == null || pos.isClosed()) return;

        BigDecimal liveLtp = fetchLiveLtp(pos.symbol());
        if (liveLtp == null || liveLtp.compareTo(BigDecimal.ZERO) <= 0) return;

        double unitMultiplier = CommodityRegistry.getUnitMultiplier(pos.symbol());

        // Phase 1: Not yet partial booked -> Check Target 1 (1:2.5 RR) or Initial Stop Loss
        if (!pos.isPartialBooked()) {
            boolean hitTarget =
                    "LONG".equalsIgnoreCase(pos.side())
                            ? liveLtp.compareTo(pos.targetPrice()) >= 0
                            : liveLtp.compareTo(pos.targetPrice()) <= 0;

            boolean hitSl =
                    "LONG".equalsIgnoreCase(pos.side())
                            ? liveLtp.compareTo(pos.stopLoss()) <= 0
                            : liveLtp.compareTo(pos.stopLoss()) >= 0;

            if (hitTarget) {
                pos.executePartialBook(liveLtp, Instant.now(clock), unitMultiplier);
                log.info(
                        "[COMMODITY-VWAP] {} TARGET HIT @ {}: 50% booked (PnL=₹{}), SL moved to Cost (₹{})",
                        pos.symbol(), liveLtp, pos.partialPnl(), pos.entryPrice());

                int bookedQty = pos.totalQuantity() - pos.remainingQuantity();
                if (pos.isClosed()) {
                    publishSignal(
                            setup.getSymbol(),
                            pos.symbol(),
                            "LONG".equalsIgnoreCase(pos.side())
                                    ? SignalAction.EXIT_LONG
                                    : SignalAction.EXIT_SHORT,
                            liveLtp,
                            null,
                            null,
                            bookedQty,
                            "TARGET_FULL_EXIT",
                            java.util.Map.of("remainingQuantity", pos.remainingQuantity()));
                    setup.completeTrade(pos);
                    closedTrades.add(pos);
                    sendTelegramExitAlert(pos);
                } else {
                    publishSignal(
                            setup.getSymbol(),
                            pos.symbol(),
                            "LONG".equalsIgnoreCase(pos.side())
                                    ? SignalAction.PARTIAL_EXIT_LONG
                                    : SignalAction.PARTIAL_EXIT_SHORT,
                            liveLtp,
                            null,
                            null,
                            bookedQty,
                            "TARGET_PARTIAL_BOOK_50",
                            java.util.Map.of(
                                    "remainingQuantity",
                                    pos.remainingQuantity(),
                                    "brokerStopLossPrice",
                                    pos.currentStopLoss()));
                    sendTelegramPartialBookAlert(pos);
                }
                return;
            } else if (hitSl) {
                closeTrade(setup, pos, liveLtp, "STOP_LOSS_HIT", unitMultiplier);
                return;
            }
        }

        // Phase 2: Partial booked -> Dynamic 10 EMA Trailing for Runner
        if (pos.isPartialBooked() && !pos.isClosed()) {
            updateRunner10EmaTrailing(pos);

            boolean hitDynamicSl =
                    "LONG".equalsIgnoreCase(pos.side())
                            ? liveLtp.compareTo(pos.currentStopLoss()) <= 0
                            : liveLtp.compareTo(pos.currentStopLoss()) >= 0;

            if (hitDynamicSl) {
                String reason =
                        pos.currentStopLoss().compareTo(pos.entryPrice()) == 0
                                ? "COST_BREAKEVEN_EXIT"
                                : "RUNNER_10EMA_TRAIL_EXIT";
                closeTrade(setup, pos, liveLtp, reason, unitMultiplier);
            }
        }
    }

    private void updateRunner10EmaTrailing(CommodityTradePosition pos) {
        try {
            List<Candle> candles = quoteFeed != null ? quoteFeed.candles15m(pos.symbol(), 5) : null;
            if (candles != null && candles.size() >= 10 && taService != null) {
                double[] closes =
                        candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] ema10Series = taService.calculateEmaSeries(closes, 10);
                if (ema10Series.length > 0 && !Double.isNaN(ema10Series[ema10Series.length - 1])) {
                    double latestEma = ema10Series[ema10Series.length - 1];
                    BigDecimal slBefore = pos.currentStopLoss();
                    pos.updateDynamicStopLoss(BigDecimal.valueOf(latestEma));
                    if (pos.currentStopLoss().compareTo(slBefore) != 0) {
                        publishSignal(
                                pos.symbol(),
                                pos.symbol(),
                                SignalAction.UPDATE_STOP_LOSS,
                                pos.currentStopLoss(),
                                pos.currentStopLoss(),
                                null,
                                pos.remainingQuantity(),
                                "RUNNER_10EMA_TRAIL",
                                java.util.Map.of(
                                        "positionSide", pos.side(),
                                        "brokerStopLossPrice", pos.currentStopLoss()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug(
                    "[COMMODITY-VWAP] Could not update 10 EMA trailing SL for {}: {}",
                    pos.symbol(),
                    e.getMessage());
        }
    }

    private void closeTrade(
            CommoditySetup setup,
            CommodityTradePosition pos,
            BigDecimal exitPrice,
            String reason,
            double unitMultiplier) {
        int closingQty = pos.isPartialBooked() ? pos.remainingQuantity() : pos.totalQuantity();
        CommodityTradePosition closed =
                pos.close(exitPrice, Instant.now(clock), reason, unitMultiplier);
        setup.completeTrade(closed);
        closedTrades.add(closed);

        publishSignal(
                setup.getSymbol(),
                closed.symbol(),
                "LONG".equalsIgnoreCase(closed.side())
                        ? SignalAction.EXIT_LONG
                        : SignalAction.EXIT_SHORT,
                exitPrice,
                null,
                null,
                closingQty,
                reason,
                java.util.Map.of("remainingQuantity", 0));

        log.info(
                "[COMMODITY-VWAP] {} TRADE CLOSED: Reason={}, Entry={}, Exit={}, PnL={}",
                closed.symbol(),
                reason,
                closed.entryPrice(),
                closed.exitPrice(),
                closed.pnl());
        sendTelegramExitAlert(closed);
    }

    /**
     * Net-of-cost P&L of all trades closed today: gross realized P&L minus estimated round-trip
     * costs. Uses the same {@link CommodityCostModel} basis as backtest metrics.
     */
    public double calculateTodayRealizedNetPnl() {
        CommodityCostModel costModel = CommodityCostModel.from(properties);
        double total = 0.0;
        for (CommodityTradePosition trade : closedTrades) {
            double cost = costModel.roundTripCost(trade).doubleValue();
            total += trade.pnl().doubleValue() - cost;
        }
        return total;
    }

    /** Estimated floating P&L of currently open positions at live LTP (0 when unavailable). */
    public double calculateOpenPositionsUnrealizedPnl() {
        double total = 0.0;
        for (CommoditySetup setup : setups.values()) {
            CommodityTradePosition pos = setup.getActivePosition();
            if (pos == null || pos.isClosed()) continue;
            try {
                BigDecimal ltp = quoteFeed != null ? quoteFeed.liveLtp(setup.getSymbol()) : null;
                if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) continue;
                double diff = ltp.subtract(pos.entryPrice()).doubleValue();
                double unrealized =
                        "LONG".equalsIgnoreCase(pos.side())
                                ? diff * pos.remainingQuantity()
                                : -diff * pos.remainingQuantity();
                total += unrealized;
            } catch (Exception e) {
                log.debug(
                        "[COMMODITY-VWAP] Unrealized PnL unavailable for {} during kill-switch check: {}",
                        setup.getSymbol(),
                        e.getMessage());
            }
        }
        return total;
    }

    /** Number of losing trades (net of costs) closed today. */
    public int countTodayLosses() {
        CommodityCostModel costModel = CommodityCostModel.from(properties);
        int losses = 0;
        for (CommodityTradePosition trade : closedTrades) {
            double net = trade.pnl().doubleValue() - costModel.roundTripCost(trade).doubleValue();
            if (net < 0) losses++;
        }
        return losses;
    }

    /**
     * Daily max-loss circuit breaker: once today's realized + floating loss reaches the configured
     * limit, the breaker trips and latches until the next session reset. A floating recovery must
     * never silently re-enable new entries.
     */
    public boolean isDailyCircuitBreakerTripped() {
        double maxLoss = properties.getMaxDailyLossInr();
        if (maxLoss <= 0.0) return false;
        if (dailyCircuitBreakerTripped.get()) return true;
        double totalPnl = calculateTodayRealizedNetPnl() + calculateOpenPositionsUnrealizedPnl();
        if (totalPnl <= -maxLoss) {
            dailyCircuitBreakerTripped.set(true);
            if (dailyCircuitBreakerAlertSent.compareAndSet(false, true)) {
                log.warn(
                        "[COMMODITY-VWAP] Daily Max Loss Circuit Breaker tripped: loss {} reached limit {}. Halting new entries.",
                        String.format(java.util.Locale.US, "%.2f", Math.abs(totalPnl)),
                        String.format(java.util.Locale.US, "%.2f", maxLoss));
                if (properties.isTelegramAlertsEnabled() && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    java.util.Locale.US,
                                    "🚨 *COMMODITY-VWAP Daily Circuit Breaker Activated*\n"
                                            + "• Today's Total Loss (Realized + Floating): `₹%.2f`\n"
                                            + "• Daily Loss Limit: `₹%.2f`\n"
                                            + "• Action: *Halting all new trade entries for the day* to protect capital.",
                                    totalPnl,
                                    maxLoss));
                }
            }
            return true;
        }
        return false;
    }

    /** Loss-count kill-switch: halts new entries after the configured number of losing trades. */
    public boolean isDailyLossCountHaltActive() {
        int maxLosses = properties.getMaxDailyLosses();
        if (maxLosses <= 0) return false;
        int losses = countTodayLosses();
        if (losses >= maxLosses) {
            if (lossCountHaltAlertSent.compareAndSet(false, true)) {
                log.warn(
                        "[COMMODITY-VWAP] Daily loss-count halt active: {} losses >= max {}. Halting new entries.",
                        losses,
                        maxLosses);
                if (properties.isTelegramAlertsEnabled() && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    java.util.Locale.US,
                                    "🛑 *COMMODITY-VWAP Loss-Count Halt Activated*\n"
                                            + "• Losing Trades Today: `%d` (max `%d`)\n"
                                            + "• Action: *Halting new trade entries for the rest of the day*.",
                                    losses,
                                    maxLosses));
                }
            }
            return true;
        }
        return false;
    }

    /** True when either kill-switch blocks new entries for the rest of the session. */
    public boolean isKillSwitchActive() {
        return isDailyCircuitBreakerTripped() || isDailyLossCountHaltActive();
    }

    /** Squares off all active open positions at 23:15 IST EOD. */
    public void squareOffAllPositions(String reason) {
        log.info("[COMMODITY-VWAP] Squaring off all active positions with reason: {}", reason);
        for (CommoditySetup setup : setups.values()) {
            if (setup.getState() == CommoditySetupState.IN_TRADE
                    && setup.getActivePosition() != null) {
                CommodityTradePosition pos = setup.getActivePosition();
                BigDecimal ltp = fetchLiveLtp(pos.symbol());
                BigDecimal exitPrice =
                        (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0)
                                ? ltp
                                : pos.entryPrice();
                double unitMultiplier = CommodityRegistry.getUnitMultiplier(pos.symbol());
                closeTrade(
                        setup,
                        pos,
                        exitPrice,
                        reason != null ? reason : "EOD_SQUARE_OFF",
                        unitMultiplier);
            }
        }
    }

    private BigDecimal fetchLiveLtp(String symbol) {
        return quoteFeed != null ? quoteFeed.liveLtp(symbol) : null;
    }

    public CommodityStatusReport getStatusReport() {
        Map<String, CommodityStatusReport.CommoditySetupSummary> summaries = new LinkedHashMap<>();
        int activeTradesCount = 0;

        for (Map.Entry<String, CommoditySetup> entry : setups.entrySet()) {
            CommoditySetup s = entry.getValue();
            if (s.getState() == CommoditySetupState.IN_TRADE) {
                activeTradesCount++;
            }
            summaries.put(
                    entry.getKey(),
                    new CommodityStatusReport.CommoditySetupSummary(
                            s.getSymbol(),
                            s.getBias(),
                            s.getPcr(),
                            s.getState(),
                            s.getTriggerHigh() != null ? s.getTriggerHigh().doubleValue() : null,
                            s.getTriggerLow() != null ? s.getTriggerLow().doubleValue() : null,
                            s.getVwapAtSetup() != null ? s.getVwapAtSetup().doubleValue() : null,
                            s.getTradesToday(),
                            s.getActivePosition()));
        }

        return new CommodityStatusReport(
                Instant.now(clock),
                properties.isEnabled(),
                setups.size(),
                activeTradesCount,
                summaries,
                List.copyOf(closedTrades));
    }

    public void resetSession(boolean force) {
        log.info("[COMMODITY-VWAP] Resetting strategy session (force={})", force);
        for (CommoditySetup setup : setups.values()) {
            setup.reset();
        }
        closedTrades.clear();
        dailyCircuitBreakerTripped.set(false);
        dailyCircuitBreakerAlertSent.set(false);
        lossCountHaltAlertSent.set(false);
    }

    public String formatTelegramReport() {
        CommodityStatusReport report = getStatusReport();
        StringBuilder sb = new StringBuilder("⚡ *[MCX Commodity VWAP Strategy Status]* ⚡\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        for (CommodityStatusReport.CommoditySetupSummary summary : report.setups().values()) {
            String stateIcon =
                    switch (summary.state()) {
                        case IN_TRADE -> "🚀";
                        case ARMED_LONG -> "🎯 🟢";
                        case ARMED_SHORT -> "🎯 🔴";
                        case BIAS_IDENTIFIED -> "👀";
                        case COMPLETED -> "✅";
                        case SKIPPED -> "⚪";
                        default -> "⏳";
                    };
            sb.append(
                    String.format(
                            "%s *%s*: State=`%s` | Bias=`%s` | PCR=`%.2f`\n",
                            stateIcon,
                            summary.symbol(),
                            summary.state(),
                            summary.bias(),
                            summary.pcr() != null ? summary.pcr() : 0.0));
            if (summary.activePosition() != null) {
                CommodityTradePosition p = summary.activePosition();
                sb.append(
                        String.format(
                                "   └ %s @ ₹%.2f | SL: ₹%.2f | Tgt: ₹%.2f\n",
                                p.side(), p.entryPrice(), p.stopLoss(), p.targetPrice()));
            }
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Active Trades: *")
                .append(report.activeTradesCount())
                .append("* | Closed Today: *")
                .append(report.closedTrades().size())
                .append("*\n");
        return sb.toString();
    }

    private void sendTelegramArmedAlert(
            CommoditySetup setup, BigDecimal trigger, BigDecimal vwap, String side) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String emoji = "LONG".equals(side) ? "🟢" : "🔴";
        String modeTag =
                "PAPER".equalsIgnoreCase(properties.getExecutionMode()) ? " [PAPER]" : " [LIVE]";
        String msg =
                String.format(
                        "🔔 *[COMMODITY VWAP SETUP ARMED%s]* 🔔\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Instrument: *%s*\n"
                                + "Direction: *%s %s*\n"
                                + "Breakout Trigger: *₹%.2f*\n"
                                + "Intraday VWAP (SL Anchor): *₹%.2f*\n"
                                + "Target RR: *1:%.1f*\n"
                                + "Mode: `%s`\n"
                                + "Awaiting breakout to execute trade.",
                        modeTag,
                        setup.getSymbol(),
                        side,
                        emoji,
                        trigger,
                        vwap,
                        properties.getRiskRewardRatio(),
                        properties.getExecutionMode());
        telegramService.sendTextMessage(msg);
    }

    private void sendTelegramEntryAlert(CommodityTradePosition pos) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String emoji = "LONG".equalsIgnoreCase(pos.side()) ? "🟢 🚀" : "🔴 🚀";
        String modeTag =
                "PAPER".equalsIgnoreCase(properties.getExecutionMode()) ? " [PAPER]" : " [LIVE]";
        String msg =
                String.format(
                        "⚡ *[COMMODITY TRADE ENTERED%s]* ⚡\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Contract: *%s*\n"
                                + "Side: *%s %s*\n"
                                + "Entry Price: *₹%.2f*\n"
                                + "Stop Loss: *₹%.2f*\n"
                                + "Target (1:%.1f RR): *₹%.2f*\n"
                                + "Risk: *₹%.2f* | Qty: *%d*\n"
                                + "Mode: `%s`",
                        modeTag,
                        pos.symbol(),
                        pos.side(),
                        emoji,
                        pos.entryPrice(),
                        pos.stopLoss(),
                        properties.getRiskRewardRatio(),
                        pos.targetPrice(),
                        pos.risk(),
                        pos.quantity(),
                        properties.getExecutionMode());
        telegramService.sendTextMessage(msg);
    }

    private void sendTelegramPartialBookAlert(CommodityTradePosition pos) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String modeTag =
                "PAPER".equalsIgnoreCase(properties.getExecutionMode()) ? " [PAPER]" : " [LIVE]";
        String msg =
                String.format(
                        "💰 *[COMMODITY TARGET HIT — 50%% BOOKED%s]* 💰\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Contract: *%s* (%s)\n"
                                + "Target 1 (1:%.1f RR) Reached @ *₹%.2f*\n"
                                + "Booked P&L: *₹%.2f*\n"
                                + "SL Moved to Cost: *₹%.2f* (Risk-free)\n"
                                + "Remaining Runner: *%d lots* trailing with 10 EMA.",
                        modeTag,
                        pos.symbol(),
                        pos.side(),
                        properties.getRiskRewardRatio(),
                        pos.partialExitPrice(),
                        pos.partialPnl(),
                        pos.entryPrice(),
                        pos.remainingQuantity());
        telegramService.sendTextMessage(msg);
    }

    private void sendTelegramExitAlert(CommodityTradePosition pos) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String emoji = pos.pnl().compareTo(BigDecimal.ZERO) >= 0 ? "🎯 💰" : "🛑";
        String modeTag =
                "PAPER".equalsIgnoreCase(properties.getExecutionMode()) ? " [PAPER]" : " [LIVE]";
        String msg =
                String.format(
                        "🏁 *[COMMODITY TRADE EXIT%s]* 🏁\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Contract: *%s* (%s)\n"
                                + "Reason: *%s %s*\n"
                                + "Entry: *₹%.2f* ➔ Exit: *₹%.2f*\n"
                                + "Realized P&L: *₹%.2f*",
                        modeTag,
                        pos.symbol(),
                        pos.side(),
                        pos.exitReason(),
                        emoji,
                        pos.entryPrice(),
                        pos.exitPrice(),
                        pos.pnl());
        telegramService.sendTextMessage(msg);
    }
}

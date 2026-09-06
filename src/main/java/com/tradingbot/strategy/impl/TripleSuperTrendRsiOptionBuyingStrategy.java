package com.tradingbot.strategy.impl;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.TripleSuperTrendPosition;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.strategy.IntradayStrategy;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Directional Options Buying Strategy using Triple SuperTrend (Fast, Medium, Slow) and RSI momentum
 * filter on NIFTY 50 and 29 F&O Equities.
 *
 * <p>Specification: 1. Indicators: - Fast SuperTrend: (7, 2.0) -> Trailing exit trigger and
 * confirmation. - Medium SuperTrend: (10, 3.0) -> Trend confirmation. - Slow SuperTrend: (14, 4.0)
 * -> Dominant trend confirmation. - RSI: 14 period momentum filter. 2. Bullish Entry (Buy ATM Call
 * / CE): - Close > Fast ST (7, 2) AND Close > Medium ST (10, 3) AND Close > Slow ST (14, 4) - All 3
 * SuperTrends are GREEN. - RSI filter: Recent RSI did not break below 40-50 zone (rsiBullishMin =
 * 40.0) and latest RSI >= 45.0. - Strike: Nearest ATM strike. - Expiry: Target ~30-40 DTE monthly
 * contract. 3. Bearish Entry (Buy ATM Put / PE): - Close < Fast ST (7, 2) AND Close < Medium ST
 * (10, 3) AND Close < Slow ST (14, 4) - All 3 SuperTrends are RED. - RSI filter: Recent RSI did not
 * break above 50-60 zone (rsiBearishMax = 60.0) and latest RSI <= 55.0. - Strike: Nearest ATM
 * strike. - Expiry: Target ~30-40 DTE monthly contract. 4. Exit Rules: - Primary Trailing Exit:
 * Fast SuperTrend (7, 2) flips color (Bullish->Bearish closes CE; Bearish->Bullish closes PE). -
 * Safety Stop Loss: Optional hard % stop loss on option premium (default: 30%). - Safety Target
 * Profit: Optional % target profit on option premium (default: 100%). - Optional Intraday
 * Square-Off: 15:15 IST (disabled by default for multi-day swing holding).
 */
@Component
public class TripleSuperTrendRsiOptionBuyingStrategy implements IntradayStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(TripleSuperTrendRsiOptionBuyingStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    public static final String STRATEGY_ID = "TRIPLE_SUPERTREND_RSI_OPTION_BUYING";
    public static final String STRATEGY_NAME =
            "Triple SuperTrend + RSI Directional Options Buying Strategy";

    // Indicator Parameter Defaults
    public static final int DEFAULT_FAST_ATR = 7;
    public static final double DEFAULT_FAST_MULT = 2.0;
    public static final int DEFAULT_MEDIUM_ATR = 10;
    public static final double DEFAULT_MEDIUM_MULT = 3.0;
    public static final int DEFAULT_SLOW_ATR = 14;
    public static final double DEFAULT_SLOW_MULT = 4.0;
    public static final int DEFAULT_RSI_PERIOD = 14;

    private final TechnicalAnalysisService taService;
    private final ShoonyaOptionChainService optionChainService;
    private final TelegramService telegramService;

    private boolean enabled = true;
    private final Map<String, TripleSuperTrendPosition> activePositions = new ConcurrentHashMap<>();
    private final Map<String, List<TripleSuperTrendPosition>> tradeHistory =
            new ConcurrentHashMap<>();

    // Configurations
    @Value("${trading-bot.strategy.triple-supertrend.timeframe:60m}")
    private String timeframe = "60m";

    @Value("${trading-bot.strategy.triple-supertrend.fast-atr-period:7}")
    private int fastAtrPeriod = DEFAULT_FAST_ATR;

    @Value("${trading-bot.strategy.triple-supertrend.fast-multiplier:2.0}")
    private double fastMultiplier = DEFAULT_FAST_MULT;

    @Value("${trading-bot.strategy.triple-supertrend.medium-atr-period:10}")
    private int mediumAtrPeriod = DEFAULT_MEDIUM_ATR;

    @Value("${trading-bot.strategy.triple-supertrend.medium-multiplier:3.0}")
    private double mediumMultiplier = DEFAULT_MEDIUM_MULT;

    @Value("${trading-bot.strategy.triple-supertrend.slow-atr-period:14}")
    private int slowAtrPeriod = DEFAULT_SLOW_ATR;

    @Value("${trading-bot.strategy.triple-supertrend.slow-multiplier:4.0}")
    private double slowMultiplier = DEFAULT_SLOW_MULT;

    @Value("${trading-bot.strategy.triple-supertrend.rsi-period:14}")
    private int rsiPeriod = DEFAULT_RSI_PERIOD;

    @Value("${trading-bot.strategy.triple-supertrend.rsi-filter-enabled:true}")
    private boolean rsiFilterEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.rsi-lookback:5}")
    private int rsiLookback = 5;

    @Value("${trading-bot.strategy.triple-supertrend.rsi-bullish-min:40.0}")
    private double rsiBullishMin = 40.0;

    @Value("${trading-bot.strategy.triple-supertrend.rsi-bearish-max:60.0}")
    private double rsiBearishMax = 60.0;

    @Value("${trading-bot.strategy.triple-supertrend.stop-loss-enabled:true}")
    private boolean stopLossEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.stop-loss-percent:30.0}")
    private double stopLossPercent = 30.0;

    @Value("${trading-bot.strategy.triple-supertrend.target-profit-enabled:true}")
    private boolean targetProfitEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.mode:OPTION_SELLING}")
    private String mode = "OPTION_SELLING";

    @Value("${trading-bot.strategy.triple-supertrend.target-profit-percent:50.0}")
    private double targetProfitPercent = 50.0;

    @Value("${trading-bot.strategy.triple-supertrend.dte-roll-threshold:10}")
    private int dteRollThreshold = 10;

    @Value("${trading-bot.strategy.triple-supertrend.intraday-mode:false}")
    private boolean intradayMode = false;

    @Value("${trading-bot.strategy.triple-supertrend.telegram-alerts:true}")
    private boolean telegramAlertsEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.lots:1}")
    private int lots = 1;

    // Enhancement 1: ADX Regime Filter (Blocks entries during choppy consolidation)
    @Value("${trading-bot.strategy.triple-supertrend.adx-filter-enabled:true}")
    private boolean adxFilterEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.adx-period:14}")
    private int adxPeriod = 14;

    @Value("${trading-bot.strategy.triple-supertrend.adx-threshold:22.0}")
    private double adxThreshold = 22.0;

    // Enhancement 2: RSI Exhaustion Caps (Avoids entering at trend exhaustion tops/bottoms)
    @Value("${trading-bot.strategy.triple-supertrend.rsi-bullish-max:68.0}")
    private double rsiBullishMax = 68.0;

    @Value("${trading-bot.strategy.triple-supertrend.rsi-bearish-min:32.0}")
    private double rsiBearishMin = 32.0;

    // Enhancement 3: Post-Loss Whipsaw Cooldown (Freezes re-entry after a loss)
    @Value("${trading-bot.strategy.triple-supertrend.cooldown-bars:3}")
    private int cooldownBars = 3;

    private final Map<String, Integer> symbolCooldowns = new ConcurrentHashMap<>();

    // Enhancement 4: Credit Spread Mode (Hedging leg to cap max risk and reduce margin)
    @Value("${trading-bot.strategy.triple-supertrend.credit-spread-enabled:true}")
    private boolean creditSpreadEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.hedge-strike-offset:2}")
    private int hedgeStrikeOffset = 2;

    // Enhancement 5: Curated Symbol Basket Selection (CURATED vs ALL)
    @Value("${trading-bot.strategy.triple-supertrend.symbol-basket:CURATED}")
    private String symbolBasket = "CURATED";

    // False Breakout Filter 1: Rejection Wick Filter
    @Value("${trading-bot.strategy.triple-supertrend.rejection-wick-filter-enabled:true}")
    private boolean rejectionWickFilterEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.rejection-wick-threshold:0.60}")
    private double rejectionWickThreshold = 0.60;

    // False Breakout Filter 2: ATR Range Sanity Filter
    @Value("${trading-bot.strategy.triple-supertrend.atr-filter-enabled:true}")
    private boolean atrFilterEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.min-atr-ratio:0.60}")
    private double minAtrRatio = 0.60;

    @Value("${trading-bot.strategy.triple-supertrend.max-atr-ratio:2.50}")
    private double maxAtrRatio = 2.50;

    // False Breakout Filter 3: Macro Trend Alignment (50 EMA)
    @Value("${trading-bot.strategy.triple-supertrend.ema-filter-enabled:true}")
    private boolean emaFilterEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.ema-period:50}")
    private int emaPeriod = 50;

    // False Breakout Filter 4: Volume Expansion Filter
    @Value("${trading-bot.strategy.triple-supertrend.volume-filter-enabled:false}")
    private boolean volumeFilterEnabled = false;

    @Value("${trading-bot.strategy.triple-supertrend.volume-multiplier:1.10}")
    private double volumeMultiplier = 1.10;

    // Expiry Preference for Indices (Weekly vs Monthly)
    @Value("${trading-bot.strategy.triple-supertrend.prefer-weekly-for-index:true}")
    private boolean preferWeeklyForIndex = true;

    // Time-Decay Stagnation Stop (exits stagnant trades to protect premium from theta decay)
    @Value("${trading-bot.strategy.triple-supertrend.stagnation-exit-enabled:true}")
    private boolean stagnationExitEnabled = true;

    @Value("${trading-bot.strategy.triple-supertrend.stagnation-bars-threshold:4}")
    private int stagnationBarsThreshold = 4;

    @Autowired
    public TripleSuperTrendRsiOptionBuyingStrategy(
            TechnicalAnalysisService taService,
            ShoonyaOptionChainService optionChainService,
            TelegramService telegramService) {
        this.taService = taService;
        this.optionChainService = optionChainService;
        this.telegramService = telegramService;
    }

    @Override
    public String getId() {
        return STRATEGY_ID;
    }

    @Override
    public String getName() {
        return isOptionSelling()
                ? (creditSpreadEnabled
                        ? "Triple SuperTrend + RSI Directional Credit Spread Strategy"
                        : "Triple SuperTrend + RSI Directional Options Selling Strategy")
                : STRATEGY_NAME;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isOptionSelling() {
        return "OPTION_SELLING".equalsIgnoreCase(mode);
    }

    public boolean isCreditSpreadEnabled() {
        return creditSpreadEnabled;
    }

    public void setCreditSpreadEnabled(boolean creditSpreadEnabled) {
        this.creditSpreadEnabled = creditSpreadEnabled;
    }

    public String getSymbolBasket() {
        return symbolBasket;
    }

    public void setSymbolBasket(String symbolBasket) {
        this.symbolBasket = symbolBasket;
    }

    public boolean isAdxFilterEnabled() {
        return adxFilterEnabled;
    }

    public void setAdxFilterEnabled(boolean adxFilterEnabled) {
        this.adxFilterEnabled = adxFilterEnabled;
    }

    public int getAdxPeriod() {
        return adxPeriod;
    }

    public void setAdxPeriod(int adxPeriod) {
        this.adxPeriod = adxPeriod;
    }

    public double getAdxThreshold() {
        return adxThreshold;
    }

    public void setAdxThreshold(double adxThreshold) {
        this.adxThreshold = adxThreshold;
    }

    public double getRsiBullishMax() {
        return rsiBullishMax;
    }

    public void setRsiBullishMax(double rsiBullishMax) {
        this.rsiBullishMax = rsiBullishMax;
    }

    public double getRsiBearishMin() {
        return rsiBearishMin;
    }

    public void setRsiBearishMin(double rsiBearishMin) {
        this.rsiBearishMin = rsiBearishMin;
    }

    public int getCooldownBars() {
        return cooldownBars;
    }

    public void setCooldownBars(int cooldownBars) {
        this.cooldownBars = cooldownBars;
    }

    public int getHedgeStrikeOffset() {
        return hedgeStrikeOffset;
    }

    public void setHedgeStrikeOffset(int hedgeStrikeOffset) {
        this.hedgeStrikeOffset = hedgeStrikeOffset;
    }

    public boolean isRejectionWickFilterEnabled() {
        return rejectionWickFilterEnabled;
    }

    public void setRejectionWickFilterEnabled(boolean rejectionWickFilterEnabled) {
        this.rejectionWickFilterEnabled = rejectionWickFilterEnabled;
    }

    public double getRejectionWickThreshold() {
        return rejectionWickThreshold;
    }

    public void setRejectionWickThreshold(double rejectionWickThreshold) {
        this.rejectionWickThreshold = rejectionWickThreshold;
    }

    public boolean isAtrFilterEnabled() {
        return atrFilterEnabled;
    }

    public void setAtrFilterEnabled(boolean atrFilterEnabled) {
        this.atrFilterEnabled = atrFilterEnabled;
    }

    public double getMinAtrRatio() {
        return minAtrRatio;
    }

    public void setMinAtrRatio(double minAtrRatio) {
        this.minAtrRatio = minAtrRatio;
    }

    public double getMaxAtrRatio() {
        return maxAtrRatio;
    }

    public void setMaxAtrRatio(double maxAtrRatio) {
        this.maxAtrRatio = maxAtrRatio;
    }

    public boolean isEmaFilterEnabled() {
        return emaFilterEnabled;
    }

    public void setEmaFilterEnabled(boolean emaFilterEnabled) {
        this.emaFilterEnabled = emaFilterEnabled;
    }

    public int getEmaPeriod() {
        return emaPeriod;
    }

    public void setEmaPeriod(int emaPeriod) {
        this.emaPeriod = emaPeriod;
    }

    public boolean isVolumeFilterEnabled() {
        return volumeFilterEnabled;
    }

    public void setVolumeFilterEnabled(boolean volumeFilterEnabled) {
        this.volumeFilterEnabled = volumeFilterEnabled;
    }

    public boolean isPreferWeeklyForIndex() {
        return preferWeeklyForIndex;
    }

    public void setPreferWeeklyForIndex(boolean preferWeeklyForIndex) {
        this.preferWeeklyForIndex = preferWeeklyForIndex;
    }

    public boolean isStagnationExitEnabled() {
        return stagnationExitEnabled;
    }

    public void setStagnationExitEnabled(boolean stagnationExitEnabled) {
        this.stagnationExitEnabled = stagnationExitEnabled;
    }

    public int getStagnationBarsThreshold() {
        return stagnationBarsThreshold;
    }

    public void setStagnationBarsThreshold(int stagnationBarsThreshold) {
        this.stagnationBarsThreshold = stagnationBarsThreshold;
    }

    public double getVolumeMultiplier() {
        return volumeMultiplier;
    }

    public void setVolumeMultiplier(double volumeMultiplier) {
        this.volumeMultiplier = volumeMultiplier;
    }

    @Override
    public List<String> getSubscribedSymbols() {
        return "ALL".equalsIgnoreCase(symbolBasket)
                ? StockFnoRegistry.getAllSubscribedSymbols()
                : StockFnoRegistry.getCuratedSymbols();
    }

    @Override
    public String getTimeframe() {
        return timeframe;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[TRIPLE-ST-RSI] Strategy enabled state set to: {}", enabled);
    }

    @Override
    public void onResetDaily() {
        if (intradayMode) {
            squareOffAll("Daily Intraday Reset");
        }
    }

    @Override
    public TradeSignal onCandle(Candle latestCandle, List<Candle> history) {
        if (!enabled || latestCandle == null) {
            return TradeSignal.hold(
                    getId(),
                    latestCandle != null ? latestCandle.symbol() : "UNKNOWN",
                    "Strategy disabled or null candle");
        }

        String rawSymbol = latestCandle.symbol();
        String symbol = normalizeSymbol(rawSymbol);

        List<Candle> allCandles = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            allCandles.addAll(history);
        }
        allCandles.add(latestCandle);

        int minCandlesNeeded = Math.max(slowAtrPeriod + 2, rsiPeriod + 2);
        if (allCandles.size() < minCandlesNeeded) {
            return TradeSignal.hold(
                    getId(),
                    symbol,
                    String.format(
                            "Insufficient candles: %d < %d", allCandles.size(), minCandlesNeeded));
        }

        int size = allCandles.size();
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];
        long[] volume = new long[size];
        for (int i = 0; i < size; i++) {
            Candle c = allCandles.get(i);
            high[i] = c.high().doubleValue();
            low[i] = c.low().doubleValue();
            close[i] = c.close().doubleValue();
            volume[i] = c.volume();
        }

        // 1. Calculate Triple SuperTrend series
        SuperTrendResult[] fastSeries =
                taService.calculateSuperTrendSeries(
                        high, low, close, fastAtrPeriod, fastMultiplier);
        SuperTrendResult[] mediumSeries =
                taService.calculateSuperTrendSeries(
                        high, low, close, mediumAtrPeriod, mediumMultiplier);
        SuperTrendResult[] slowSeries =
                taService.calculateSuperTrendSeries(
                        high, low, close, slowAtrPeriod, slowMultiplier);

        // 2. Calculate RSI series
        double[] rsiSeries = taService.calculateRsiSeries(close, rsiPeriod);

        // 3. Calculate ADX series (Enhancement 1: Trend Strength Filter)
        double[] adxSeries =
                adxFilterEnabled ? taService.calculateAdxSeries(high, low, close, adxPeriod) : null;

        // 4. Calculate EMA series (False Breakout Filter 3: 50 EMA Macro Alignment)
        double[] emaSeries =
                emaFilterEnabled ? taService.calculateEmaSeries(close, emaPeriod) : null;

        // 5. Calculate ATR series (False Breakout Filter 2: Candle Range Sanity Check)
        double[] atrSeries =
                atrFilterEnabled ? taService.calculateAtrSeries(high, low, close, 14) : null;

        int lastIdx = size - 1;
        SuperTrendResult fastSt = fastSeries[lastIdx];
        SuperTrendResult mediumSt = mediumSeries[lastIdx];
        SuperTrendResult slowSt = slowSeries[lastIdx];
        double latestRsi = rsiSeries[lastIdx];
        double latestAdx =
                (adxSeries != null && adxSeries.length > lastIdx) ? adxSeries[lastIdx] : Double.NaN;
        double latestEma =
                (emaSeries != null && emaSeries.length > lastIdx) ? emaSeries[lastIdx] : Double.NaN;
        double latestAtr =
                (atrSeries != null && atrSeries.length > lastIdx) ? atrSeries[lastIdx] : Double.NaN;
        double currentClose = close[lastIdx];
        double currentHigh = high[lastIdx];
        double currentLow = low[lastIdx];
        double candleRange = currentHigh - currentLow;

        double avgVolume =
                volumeFilterEnabled ? taService.calculateLatestVolumeSma(volume, 20) : 0.0;

        if (Double.isNaN(fastSt.value())
                || Double.isNaN(mediumSt.value())
                || Double.isNaN(slowSt.value())) {
            return TradeSignal.hold(getId(), symbol, "SuperTrend indicators warming up");
        }

        LocalTime candleTime = latestCandle.timestamp().atZone(IST).toLocalTime();
        LocalDate candleDate = latestCandle.timestamp().atZone(IST).toLocalDate();

        TripleSuperTrendPosition activePos = activePositions.get(symbol);

        // Enhancement 3: Post-Loss Whipsaw Cooldown (when flat)
        int cooldown = symbolCooldowns.getOrDefault(symbol, 0);
        if (cooldown > 0 && (activePos == null || activePos.isClosed())) {
            symbolCooldowns.put(symbol, cooldown - 1);
            return TradeSignal.hold(
                    getId(),
                    symbol,
                    String.format(
                            "Post-loss cooldown active for %s: %d bars remaining",
                            symbol, cooldown));
        }

        // =========================================================================
        // EXIT LOGIC (When active position is open)
        // =========================================================================
        if (activePos != null && !activePos.isClosed()) {
            boolean isCall = "CE".equalsIgnoreCase(activePos.optionType());
            boolean selling = isOptionSelling();
            BigDecimal currentEstimatedPremium =
                    estimateCurrentOptionPremium(symbol, latestCandle.close(), activePos);

            // Exit Signal 1: Fast SuperTrend (7, 2) Flip (Primary Dynamic Trailing Exit)
            // Buying CE: exits if Fast ST flips Bearish (Red)
            // Buying PE: exits if Fast ST flips Bullish (Green)
            // Selling PE: exits if Fast ST flips Bearish (Red)
            // Selling CE: exits if Fast ST flips Bullish (Green)
            boolean fastStFlipped;
            if (selling) {
                fastStFlipped =
                        isCall
                                ? (fastSt.isBullish() || currentClose > fastSt.value())
                                : (!fastSt.isBullish() || currentClose < fastSt.value());
            } else {
                fastStFlipped =
                        isCall
                                ? (!fastSt.isBullish() || currentClose < fastSt.value())
                                : (fastSt.isBullish() || currentClose > fastSt.value());
            }

            // Exit Signal 2: Stop-Loss on Option Premium (e.g. 30%)
            // Buying: Premium drops by 30% -> currPremium <= entryPremium * 0.70
            // Selling: Premium rises by 30% -> currPremium >= entryPremium * 1.30
            boolean stopLossHit = false;
            if (stopLossEnabled && activePos.entryPremium().compareTo(BigDecimal.ZERO) > 0) {
                if (selling) {
                    BigDecimal slThreshold =
                            activePos
                                    .entryPremium()
                                    .multiply(BigDecimal.valueOf(1.0 + (stopLossPercent / 100.0)))
                                    .setScale(2, RoundingMode.HALF_UP);
                    if (currentEstimatedPremium.compareTo(slThreshold) >= 0) {
                        stopLossHit = true;
                    }
                } else {
                    BigDecimal slThreshold =
                            activePos
                                    .entryPremium()
                                    .multiply(BigDecimal.valueOf(1.0 - (stopLossPercent / 100.0)))
                                    .setScale(2, RoundingMode.HALF_UP);
                    if (currentEstimatedPremium.compareTo(slThreshold) <= 0) {
                        stopLossHit = true;
                    }
                }
            }

            // Exit Signal 3: Target Profit on Option Premium
            // Buying: Premium rises by targetProfitPercent% -> currPremium >= entryPremium * (1 +
            // TP%)
            // Selling: Premium decays by targetProfitPercent% -> currPremium <= entryPremium * (1 -
            // TP%)
            boolean targetProfitHit = false;
            if (targetProfitEnabled && activePos.entryPremium().compareTo(BigDecimal.ZERO) > 0) {
                if (selling) {
                    BigDecimal tpThreshold =
                            activePos
                                    .entryPremium()
                                    .multiply(
                                            BigDecimal.valueOf(1.0 - (targetProfitPercent / 100.0)))
                                    .setScale(2, RoundingMode.HALF_UP);
                    if (currentEstimatedPremium.compareTo(tpThreshold) <= 0) {
                        targetProfitHit = true;
                    }
                } else {
                    BigDecimal tpThreshold =
                            activePos
                                    .entryPremium()
                                    .multiply(
                                            BigDecimal.valueOf(1.0 + (targetProfitPercent / 100.0)))
                                    .setScale(2, RoundingMode.HALF_UP);
                    if (currentEstimatedPremium.compareTo(tpThreshold) >= 0) {
                        targetProfitHit = true;
                    }
                }
            }

            // Exit Signal 4: Time-Decay Stagnation Stop (Option Buying only)
            boolean stagnationHit = false;
            if (!selling
                    && stagnationExitEnabled
                    && activePos.entryTime() != null
                    && latestCandle.timestamp() != null) {
                long hoursHeld =
                        java.time.Duration.between(activePos.entryTime(), latestCandle.timestamp())
                                .toHours();
                if (hoursHeld >= stagnationBarsThreshold) {
                    boolean noProgress =
                            isCall
                                    ? latestCandle
                                                    .close()
                                                    .compareTo(activePos.underlyingEntryPrice())
                                            <= 0
                                    : latestCandle
                                                    .close()
                                                    .compareTo(activePos.underlyingEntryPrice())
                                            >= 0;
                    boolean premiumStagnant =
                            currentEstimatedPremium.compareTo(
                                            activePos
                                                    .entryPremium()
                                                    .multiply(BigDecimal.valueOf(1.05)))
                                    <= 0;
                    if (noProgress || premiumStagnant) {
                        stagnationHit = true;
                    }
                }
            }

            // Exit Signal 5: Mandatory EOD Square-Off (if intraday mode enabled)
            boolean intradayEod = intradayMode && shouldAutoSquareOff(candleTime);

            if (fastStFlipped || stopLossHit || targetProfitHit || stagnationHit || intradayEod) {
                String exitReason;
                if (stopLossHit) {
                    exitReason =
                            String.format(
                                    "Hard Stop Loss (%s%.0f%%) Hit",
                                    selling ? "+" : "-", stopLossPercent);
                } else if (targetProfitHit) {
                    exitReason =
                            String.format(
                                    "Target Profit (%s%.0f%%) Hit",
                                    selling ? "-" : "+", targetProfitPercent);
                } else if (stagnationHit) {
                    exitReason =
                            String.format(
                                    "Time-Decay Stagnation Stop (held %d hrs without breakout expansion)",
                                    stagnationBarsThreshold);
                } else if (intradayEod) {
                    exitReason = "Intraday EOD 15:15 IST Square-Off";
                } else {
                    if (selling) {
                        exitReason =
                                isCall
                                        ? "Fast SuperTrend (7, 2) flipped Bullish (Green)"
                                        : "Fast SuperTrend (7, 2) flipped Bearish (Red)";
                    } else {
                        exitReason =
                                isCall
                                        ? "Fast SuperTrend (7, 2) flipped Bearish (Red)"
                                        : "Fast SuperTrend (7, 2) flipped Bullish (Green)";
                    }
                }

                TripleSuperTrendPosition closedPos =
                        activePos.close(currentEstimatedPremium, exitReason, selling);
                activePositions.remove(symbol);
                tradeHistory.computeIfAbsent(symbol, k -> new ArrayList<>()).add(closedPos);

                // Enhancement 3: Trigger cooldown if trade closed for a loss
                if (closedPos.realizedPnl().compareTo(BigDecimal.ZERO) < 0 && cooldownBars > 0) {
                    symbolCooldowns.put(symbol, cooldownBars);
                    log.info(
                            "[TRIPLE-ST-RSI] [{}] Triggered {}-bar cooldown after loss ₹{}",
                            symbol,
                            cooldownBars,
                            closedPos.realizedPnl());
                }

                log.info(
                        "[TRIPLE-ST-RSI] [{}] Exit triggered for {} {}: Reason={}, PnL=₹{}",
                        symbol,
                        selling ? "SHORT" : "LONG",
                        activePos.optionType(),
                        exitReason,
                        closedPos.realizedPnl());

                if (telegramService != null && telegramAlertsEnabled) {
                    if (selling) {
                        telegramService.sendStrategyExitAlert(
                                getName(),
                                symbol,
                                isCall
                                        ? "BUY TO COVER CE (EXIT SHORT CE)"
                                        : "BUY TO COVER PE (EXIT SHORT PE)",
                                latestCandle.close(),
                                activePos.strike(),
                                activePos.optionType(),
                                activePos.entryPremium(),
                                currentEstimatedPremium,
                                exitReason,
                                latestCandle.timestamp());
                    } else {
                        telegramService.sendOptionBuyingExitAlert(
                                getName(),
                                symbol,
                                isCall ? "EXIT LONG CALL (CE)" : "EXIT LONG PUT (PE)",
                                latestCandle.close(),
                                activePos.strike(),
                                activePos.optionType(),
                                activePos.entryPremium(),
                                currentEstimatedPremium,
                                exitReason,
                                latestCandle.timestamp());
                    }
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("strategyName", getName());
                meta.put("symbol", symbol);
                meta.put("mode", mode);
                meta.put("optionType", activePos.optionType());
                meta.put("strike", activePos.strike());
                meta.put("expiry", activePos.expiry());
                meta.put("entryPremium", activePos.entryPremium());
                meta.put("exitPremium", currentEstimatedPremium);
                meta.put("pnl", closedPos.realizedPnl());
                meta.put("exitReason", exitReason);
                meta.put("fastSt", fastSt.value());
                meta.put("rsi", latestRsi);

                SignalAction exitAction;
                if (selling) {
                    exitAction = SignalAction.EXIT_SHORT;
                } else {
                    exitAction = isCall ? SignalAction.EXIT_LONG : SignalAction.EXIT_SHORT;
                }

                return TradeSignal.of(
                        getId(),
                        symbol,
                        exitAction,
                        currentEstimatedPremium,
                        null,
                        null,
                        activePos.quantity(),
                        exitReason,
                        meta);
            }

            return TradeSignal.hold(
                    getId(),
                    symbol,
                    String.format(
                            "Holding active %s position (Fast ST=%.2f, RSI=%.1f)",
                            activePos.optionType(), fastSt.value(), latestRsi));
        }

        // =========================================================================
        // ENTRY LOGIC (When flat)
        // =========================================================================
        boolean selling = isOptionSelling();

        // Enhancement 1: ADX Trend Regime Filter (Blocks entries in choppy consolidation)
        boolean adxOk =
                !adxFilterEnabled || (!Double.isNaN(latestAdx) && latestAdx >= adxThreshold);

        // 1. Check Bullish Confluence
        // When Buying: Buy ATM Call (CE)
        // When Selling: Sell ATM Put (PE) or Bull Put Credit Spread
        boolean allBullish =
                fastSt.isBullish()
                        && mediumSt.isBullish()
                        && slowSt.isBullish()
                        && currentClose > fastSt.value()
                        && currentClose > mediumSt.value()
                        && currentClose > slowSt.value();

        boolean rsiBullishOk = true;
        if (rsiFilterEnabled) {
            int lookbackStart = Math.max(0, lastIdx - rsiLookback);
            for (int k = lookbackStart; k <= lastIdx; k++) {
                if (!Double.isNaN(rsiSeries[k]) && rsiSeries[k] < rsiBullishMin) {
                    rsiBullishOk = false;
                    break;
                }
            }
            if (latestRsi < 45.0) {
                rsiBullishOk = false;
            }
            // Enhancement 2: RSI Exhaustion Cap (Bullish Put Sell / Call Buy shouldn't chase
            // overbought tops)
            if (latestRsi > rsiBullishMax) {
                rsiBullishOk = false;
            }
        }

        // =========================================================================
        // FALSE BREAKOUT FILTERS
        // =========================================================================
        // Filter 1: Rejection Wick Check (Close must be in the leading portion of candle)
        boolean rejectionWickBullOk = true;
        boolean rejectionWickBearOk = true;
        if (rejectionWickFilterEnabled && candleRange > 0) {
            double bullBodyRatio = (currentClose - currentLow) / candleRange;
            if (bullBodyRatio < rejectionWickThreshold) {
                rejectionWickBullOk = false;
            }
            double bearBodyRatio = (currentHigh - currentClose) / candleRange;
            if (bearBodyRatio < rejectionWickThreshold) {
                rejectionWickBearOk = false;
            }
        }

        // Filter 2: ATR Range Sanity Check (Prevents tiny dojis and exhausted blow-offs)
        boolean atrOk = true;
        if (atrFilterEnabled && !Double.isNaN(latestAtr) && latestAtr > 0) {
            if (candleRange < minAtrRatio * latestAtr || candleRange > maxAtrRatio * latestAtr) {
                atrOk = false;
            }
        }

        // Filter 3: Macro Trend Alignment (50 EMA)
        boolean emaBullOk =
                !emaFilterEnabled || Double.isNaN(latestEma) || currentClose > latestEma;
        boolean emaBearOk =
                !emaFilterEnabled || Double.isNaN(latestEma) || currentClose < latestEma;

        // Filter 4: Volume Expansion Check
        boolean volumeOk =
                !volumeFilterEnabled
                        || avgVolume <= 0
                        || latestCandle.volume() >= avgVolume * volumeMultiplier;

        if (adxOk
                && allBullish
                && rsiBullishOk
                && rejectionWickBullOk
                && atrOk
                && emaBullOk
                && volumeOk) {
            String optionType = selling ? "PE" : "CE";
            BigDecimal atmStrike =
                    StockFnoRegistry.calculateAtmStrike(symbol, latestCandle.close());
            LocalDate targetExpiry =
                    StockFnoRegistry.calculateTargetExpiry(
                            symbol, candleDate, preferWeeklyForIndex, dteRollThreshold);
            String expiryStr = targetExpiry.format(EXPIRY_FMT).toUpperCase();
            int contractLotSize = StockFnoRegistry.getLotSize(symbol);
            int totalQty = contractLotSize * this.lots;
            BigDecimal entryPremium =
                    estimateEntryOptionPremium(symbol, latestCandle.close(), atmStrike, optionType);

            // Enhancement 4: Credit Spread (Hedge leg to cap max risk and reduce margin)
            BigDecimal hedgeStrike = null;
            BigDecimal hedgePremium = BigDecimal.ZERO;
            BigDecimal netCredit = entryPremium;
            BigDecimal maxRisk = BigDecimal.ZERO;

            if (selling && creditSpreadEnabled) {
                hedgeStrike =
                        StockFnoRegistry.calculateOtmStrike(
                                symbol, atmStrike, optionType, hedgeStrikeOffset);
                BigDecimal strikeDiff = hedgeStrike.subtract(atmStrike).abs();
                // Vertical credit spread: net credit cannot exceed spread width (typically ~35% of
                // spread width)
                BigDecimal rawCredit =
                        entryPremium
                                .multiply(BigDecimal.valueOf(0.60))
                                .setScale(2, RoundingMode.HALF_UP);
                BigDecimal maxAllowedCredit =
                        strikeDiff
                                .multiply(BigDecimal.valueOf(0.35))
                                .setScale(2, RoundingMode.HALF_UP);
                netCredit = rawCredit.min(maxAllowedCredit);
                hedgePremium = entryPremium.subtract(netCredit).setScale(2, RoundingMode.HALF_UP);
                maxRisk =
                        strikeDiff
                                .subtract(netCredit)
                                .max(BigDecimal.ZERO)
                                .setScale(2, RoundingMode.HALF_UP);
            }

            String tradeId =
                    String.format(
                            "TTRD_%s_%s_%d",
                            symbol, optionType, System.currentTimeMillis() % 1000000);
            TripleSuperTrendPosition pos =
                    TripleSuperTrendPosition.open(
                            tradeId,
                            symbol,
                            optionType,
                            atmStrike,
                            expiryStr,
                            entryPremium,
                            latestCandle.close(),
                            totalQty,
                            latestCandle.timestamp() != null
                                    ? latestCandle.timestamp()
                                    : Instant.now(),
                            fastSt.value(),
                            mediumSt.value(),
                            slowSt.value(),
                            latestRsi,
                            hedgeStrike,
                            hedgePremium,
                            netCredit,
                            maxRisk);

            activePositions.put(symbol, pos);
            String actionDesc;
            if (selling) {
                actionDesc =
                        (creditSpreadEnabled && hedgeStrike != null)
                                ? String.format(
                                        "BULL PUT SPREAD (Sell %s PE, Buy %s PE)",
                                        atmStrike, hedgeStrike)
                                : "SELL ATM PUT (PE)";
            } else {
                actionDesc = "BUY ATM CALL (CE)";
            }
            SignalAction signalAction = selling ? SignalAction.SELL : SignalAction.BUY;
            String reason =
                    selling
                            ? ((creditSpreadEnabled && hedgeStrike != null)
                                    ? "Triple SuperTrend + RSI Bullish Confluence (Bull Put Credit Spread)"
                                    : "Triple SuperTrend + RSI Bullish Confluence (Short ATM PE)")
                            : "Triple SuperTrend + RSI Bullish Confluence (Buy ATM CE)";

            log.info(
                    "[TRIPLE-ST-RSI] [{}] BULLISH Signal Triggered: {} {} Strike {} (Exp: {}), Est. Premium ₹{}, Net Credit ₹{}",
                    symbol,
                    actionDesc,
                    symbol,
                    atmStrike,
                    expiryStr,
                    entryPremium,
                    netCredit);

            if (telegramService != null && telegramAlertsEnabled) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("Fast ST (7, 2)", String.format("%.2f (Bullish)", fastSt.value()));
                extra.put("Medium ST (10, 3)", String.format("%.2f (Bullish)", mediumSt.value()));
                extra.put("Slow ST (14, 4)", String.format("%.2f (Bullish)", slowSt.value()));
                extra.put("RSI (14)", String.format("%.1f (Bullish Momentum)", latestRsi));
                if (!Double.isNaN(latestAdx)) {
                    extra.put("ADX (14)", String.format("%.1f (Trending)", latestAdx));
                }
                if (hedgeStrike != null) {
                    extra.put(
                            "Hedge Leg",
                            String.format("Buy %s PE @ ₹%s", hedgeStrike, hedgePremium));
                    extra.put("Net Credit", String.format("₹%s", netCredit));
                    extra.put("Max Risk", String.format("₹%s / share", maxRisk));
                }
                extra.put("Expiry", expiryStr);
                extra.put("Lot Size", String.format("%d (Qty: %d)", contractLotSize, totalQty));
                extra.put(
                        "Mode",
                        selling
                                ? (creditSpreadEnabled
                                        ? "Bull Put Credit Spread"
                                        : "Directional Option Selling")
                                : "Directional Option Buying");

                telegramService.sendStrategySignalAlert(
                        getName(),
                        symbol,
                        actionDesc,
                        latestCandle.close(),
                        atmStrike,
                        optionType,
                        entryPremium,
                        extra,
                        latestCandle.timestamp());
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("strategyName", getName());
            meta.put("symbol", symbol);
            meta.put("mode", mode);
            meta.put("optionType", optionType);
            meta.put("strike", atmStrike);
            if (hedgeStrike != null) {
                meta.put("hedgeStrike", hedgeStrike);
                meta.put("hedgePremium", hedgePremium);
                meta.put("netCredit", netCredit);
                meta.put("maxRisk", maxRisk);
                meta.put("spreadType", "BULL_PUT_SPREAD");
            }
            meta.put("expiry", expiryStr);
            meta.put("entryPremium", entryPremium);
            meta.put("fastSt", fastSt.value());
            meta.put("mediumSt", mediumSt.value());
            meta.put("slowSt", slowSt.value());
            meta.put("rsi", latestRsi);
            meta.put("adx", latestAdx);
            meta.put("quantity", totalQty);

            return TradeSignal.of(
                    getId(),
                    symbol,
                    signalAction,
                    entryPremium,
                    null,
                    null,
                    totalQty,
                    reason,
                    meta);
        }

        // 2. Check Bearish Confluence
        // When Buying: Buy ATM Put (PE)
        // When Selling: Sell ATM Call (CE) or Bear Call Credit Spread
        boolean allBearish =
                !fastSt.isBullish()
                        && !mediumSt.isBullish()
                        && !slowSt.isBullish()
                        && currentClose < fastSt.value()
                        && currentClose < mediumSt.value()
                        && currentClose < slowSt.value();

        boolean rsiBearishOk = true;
        if (rsiFilterEnabled) {
            int lookbackStart = Math.max(0, lastIdx - rsiLookback);
            for (int k = lookbackStart; k <= lastIdx; k++) {
                if (!Double.isNaN(rsiSeries[k]) && rsiSeries[k] > rsiBearishMax) {
                    rsiBearishOk = false;
                    break;
                }
            }
            if (latestRsi > 55.0) {
                rsiBearishOk = false;
            }
            // Enhancement 2: RSI Exhaustion Cap (Bearish Call Sell / Put Buy shouldn't chase
            // oversold bottoms)
            if (latestRsi < rsiBearishMin) {
                rsiBearishOk = false;
            }
        }

        if (adxOk
                && allBearish
                && rsiBearishOk
                && rejectionWickBearOk
                && atrOk
                && emaBearOk
                && volumeOk) {
            String optionType = selling ? "CE" : "PE";
            BigDecimal atmStrike =
                    StockFnoRegistry.calculateAtmStrike(symbol, latestCandle.close());
            LocalDate targetExpiry =
                    StockFnoRegistry.calculateTargetExpiry(
                            symbol, candleDate, preferWeeklyForIndex, dteRollThreshold);
            String expiryStr = targetExpiry.format(EXPIRY_FMT).toUpperCase();
            int contractLotSize = StockFnoRegistry.getLotSize(symbol);
            int totalQty = contractLotSize * this.lots;
            BigDecimal entryPremium =
                    estimateEntryOptionPremium(symbol, latestCandle.close(), atmStrike, optionType);

            // Enhancement 4: Credit Spread (Hedge leg to cap max risk and reduce margin)
            BigDecimal hedgeStrike = null;
            BigDecimal hedgePremium = BigDecimal.ZERO;
            BigDecimal netCredit = entryPremium;
            BigDecimal maxRisk = BigDecimal.ZERO;

            if (selling && creditSpreadEnabled) {
                hedgeStrike =
                        StockFnoRegistry.calculateOtmStrike(
                                symbol, atmStrike, optionType, hedgeStrikeOffset);
                BigDecimal strikeDiff = hedgeStrike.subtract(atmStrike).abs();
                // Vertical credit spread: net credit cannot exceed spread width (typically ~35% of
                // spread width)
                BigDecimal rawCredit =
                        entryPremium
                                .multiply(BigDecimal.valueOf(0.60))
                                .setScale(2, RoundingMode.HALF_UP);
                BigDecimal maxAllowedCredit =
                        strikeDiff
                                .multiply(BigDecimal.valueOf(0.35))
                                .setScale(2, RoundingMode.HALF_UP);
                netCredit = rawCredit.min(maxAllowedCredit);
                hedgePremium = entryPremium.subtract(netCredit).setScale(2, RoundingMode.HALF_UP);
                maxRisk =
                        strikeDiff
                                .subtract(netCredit)
                                .max(BigDecimal.ZERO)
                                .setScale(2, RoundingMode.HALF_UP);
            }

            String tradeId =
                    String.format(
                            "TTRD_%s_%s_%d",
                            symbol, optionType, System.currentTimeMillis() % 1000000);
            TripleSuperTrendPosition pos =
                    TripleSuperTrendPosition.open(
                            tradeId,
                            symbol,
                            optionType,
                            atmStrike,
                            expiryStr,
                            entryPremium,
                            latestCandle.close(),
                            totalQty,
                            latestCandle.timestamp() != null
                                    ? latestCandle.timestamp()
                                    : Instant.now(),
                            fastSt.value(),
                            mediumSt.value(),
                            slowSt.value(),
                            latestRsi,
                            hedgeStrike,
                            hedgePremium,
                            netCredit,
                            maxRisk);

            activePositions.put(symbol, pos);
            String actionDesc;
            if (selling) {
                actionDesc =
                        (creditSpreadEnabled && hedgeStrike != null)
                                ? String.format(
                                        "BEAR CALL SPREAD (Sell %s CE, Buy %s CE)",
                                        atmStrike, hedgeStrike)
                                : "SELL ATM CALL (CE)";
            } else {
                actionDesc = "BUY ATM PUT (PE)";
            }
            SignalAction signalAction = selling ? SignalAction.SELL : SignalAction.BUY;
            String reason =
                    selling
                            ? ((creditSpreadEnabled && hedgeStrike != null)
                                    ? "Triple SuperTrend + RSI Bearish Confluence (Bear Call Credit Spread)"
                                    : "Triple SuperTrend + RSI Bearish Confluence (Short ATM CE)")
                            : "Triple SuperTrend + RSI Bearish Confluence (Buy ATM PE)";

            log.info(
                    "[TRIPLE-ST-RSI] [{}] BEARISH Signal Triggered: {} {} Strike {} (Exp: {}), Est. Premium ₹{}, Net Credit ₹{}",
                    symbol,
                    actionDesc,
                    symbol,
                    atmStrike,
                    expiryStr,
                    entryPremium,
                    netCredit);

            if (telegramService != null && telegramAlertsEnabled) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("Fast ST (7, 2)", String.format("%.2f (Bearish)", fastSt.value()));
                extra.put("Medium ST (10, 3)", String.format("%.2f (Bearish)", mediumSt.value()));
                extra.put("Slow ST (14, 4)", String.format("%.2f (Bearish)", slowSt.value()));
                extra.put("RSI (14)", String.format("%.1f (Bearish Momentum)", latestRsi));
                if (!Double.isNaN(latestAdx)) {
                    extra.put("ADX (14)", String.format("%.1f (Trending)", latestAdx));
                }
                if (hedgeStrike != null) {
                    extra.put(
                            "Hedge Leg",
                            String.format("Buy %s CE @ ₹%s", hedgeStrike, hedgePremium));
                    extra.put("Net Credit", String.format("₹%s", netCredit));
                    extra.put("Max Risk", String.format("₹%s / share", maxRisk));
                }
                extra.put("Expiry", expiryStr);
                extra.put("Lot Size", String.format("%d (Qty: %d)", contractLotSize, totalQty));
                extra.put(
                        "Mode",
                        selling
                                ? (creditSpreadEnabled
                                        ? "Bear Call Credit Spread"
                                        : "Directional Option Selling")
                                : "Directional Option Buying");

                telegramService.sendStrategySignalAlert(
                        getName(),
                        symbol,
                        actionDesc,
                        latestCandle.close(),
                        atmStrike,
                        optionType,
                        entryPremium,
                        extra,
                        latestCandle.timestamp());
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("strategyName", getName());
            meta.put("symbol", symbol);
            meta.put("mode", mode);
            meta.put("optionType", optionType);
            meta.put("strike", atmStrike);
            if (hedgeStrike != null) {
                meta.put("hedgeStrike", hedgeStrike);
                meta.put("hedgePremium", hedgePremium);
                meta.put("netCredit", netCredit);
                meta.put("maxRisk", maxRisk);
                meta.put("spreadType", "BEAR_CALL_SPREAD");
            }
            meta.put("expiry", expiryStr);
            meta.put("entryPremium", entryPremium);
            meta.put("fastSt", fastSt.value());
            meta.put("mediumSt", mediumSt.value());
            meta.put("slowSt", slowSt.value());
            meta.put("rsi", latestRsi);
            meta.put("adx", latestAdx);
            meta.put("quantity", totalQty);

            return TradeSignal.of(
                    getId(),
                    symbol,
                    signalAction,
                    entryPremium,
                    null,
                    null,
                    totalQty,
                    reason,
                    meta);
        }

        return TradeSignal.hold(
                getId(),
                symbol,
                String.format(
                        "No confluence or filtered (ST Fast=%.2f, Med=%.2f, Slow=%.2f, RSI=%.1f, ADX=%.1f, EMA=%.1f, ATR=%.2f)",
                        fastSt.value(),
                        mediumSt.value(),
                        slowSt.value(),
                        latestRsi,
                        latestAdx,
                        latestEma,
                        latestAtr));
    }

    /**
     * Helper to estimate ATM entry premium (~2.2% of underlying price for 30-40 DTE ATM options).
     */
    public BigDecimal estimateEntryOptionPremium(
            String symbol, BigDecimal spotPrice, BigDecimal strike, String optType) {
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(20.0);
        }
        // Realistic ~30-40 DTE ATM Indian equity/index option pricing model (~2.2% of spot)
        BigDecimal baseEst =
                spotPrice.multiply(BigDecimal.valueOf(0.022)).setScale(2, RoundingMode.HALF_UP);
        return baseEst.max(BigDecimal.valueOf(2.0));
    }

    /**
     * Helper to track option premium evolution dynamically with underlying movement. Naked ATM
     * delta ~0.50. 2-strike Credit Spread net delta ~0.20.
     */
    public BigDecimal estimateCurrentOptionPremium(
            String symbol, BigDecimal currentSpot, TripleSuperTrendPosition pos) {
        if (pos == null || pos.entryPremium() == null) {
            return BigDecimal.ZERO;
        }
        if (currentSpot == null || pos.underlyingEntryPrice() == null) {
            return pos.isCreditSpread() ? pos.netCredit() : pos.entryPremium();
        }
        BigDecimal spotDiff = currentSpot.subtract(pos.underlyingEntryPrice());
        BigDecimal delta =
                pos.isCreditSpread() ? BigDecimal.valueOf(0.20) : BigDecimal.valueOf(0.50);

        BigDecimal premiumDelta;
        if ("CE".equalsIgnoreCase(pos.optionType())) {
            premiumDelta = spotDiff.multiply(delta);
        } else {
            premiumDelta = spotDiff.negate().multiply(delta);
        }

        BigDecimal base = pos.isCreditSpread() ? pos.netCredit() : pos.entryPremium();
        BigDecimal estPremium = base.add(premiumDelta).setScale(2, RoundingMode.HALF_UP);
        return estPremium.max(BigDecimal.valueOf(0.05));
    }

    public Map<String, TripleSuperTrendPosition> getActivePositions() {
        return Collections.unmodifiableMap(activePositions);
    }

    public TripleSuperTrendPosition getActivePosition(String symbol) {
        return activePositions.get(normalizeSymbol(symbol));
    }

    public Map<String, List<TripleSuperTrendPosition>> getTradeHistory() {
        return Collections.unmodifiableMap(tradeHistory);
    }

    public synchronized void squareOffPosition(
            String symbol, BigDecimal exitPremium, String reason) {
        String norm = normalizeSymbol(symbol);
        TripleSuperTrendPosition pos = activePositions.get(norm);
        if (pos != null && !pos.isClosed()) {
            boolean selling = isOptionSelling();
            BigDecimal premium =
                    (exitPremium != null && exitPremium.compareTo(BigDecimal.ZERO) > 0)
                            ? exitPremium
                            : pos.entryPremium();
            TripleSuperTrendPosition closed =
                    pos.close(premium, reason != null ? reason : "Manual Square-Off", selling);
            activePositions.remove(norm);
            tradeHistory.computeIfAbsent(norm, k -> new ArrayList<>()).add(closed);

            if (closed.realizedPnl().compareTo(BigDecimal.ZERO) < 0 && cooldownBars > 0) {
                symbolCooldowns.put(norm, cooldownBars);
            }

            log.info(
                    "[TRIPLE-ST-RSI] Manually squared off {} {} position for {}",
                    selling ? "SHORT" : "LONG",
                    pos.optionType(),
                    norm);

            if (telegramService != null && telegramAlertsEnabled) {
                if (selling) {
                    telegramService.sendStrategyExitAlert(
                            getName(),
                            norm,
                            "MANUAL SQUARE-OFF",
                            pos.underlyingEntryPrice(),
                            pos.strike(),
                            pos.optionType(),
                            pos.entryPremium(),
                            premium,
                            reason != null ? reason : "Manual Square-Off",
                            Instant.now());
                } else {
                    telegramService.sendOptionBuyingExitAlert(
                            getName(),
                            norm,
                            "MANUAL SQUARE-OFF",
                            pos.underlyingEntryPrice(),
                            pos.strike(),
                            pos.optionType(),
                            pos.entryPremium(),
                            premium,
                            reason != null ? reason : "Manual Square-Off",
                            Instant.now());
                }
            }
        }
    }

    public synchronized void squareOffAll(String reason) {
        List<String> symbols = new ArrayList<>(activePositions.keySet());
        for (String sym : symbols) {
            squareOffPosition(sym, null, reason);
        }
    }

    private String normalizeSymbol(String raw) {
        if (raw == null) return "NIFTY50";
        String s = raw.toUpperCase().trim();
        if (s.startsWith("NSE:")) s = s.substring(4);
        if ("NIFTY".equalsIgnoreCase(s)) return "NIFTY50";
        return s;
    }
}

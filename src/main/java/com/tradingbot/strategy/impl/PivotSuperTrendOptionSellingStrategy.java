package com.tradingbot.strategy.impl;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.strategy.IntradayStrategy;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Intraday Strategy: Directional Option Selling on NIFTY 50 / SENSEX using Daily Standard Pivot
 * Points (R1 / S1) + Fast SuperTrend (7, 3.0) + 3-Strike OTM OI Differential.
 *
 * <p>Rules: 1. Daily Level Filter: Compute Pivot (P), Resistance 1 (R1), and Support 1 (S1) from
 * previous session's OHLC: P = (High_prev + Low_prev + Close_prev) / 3 R1 = 2 * P - Low_prev S1 = 2
 * * P - High_prev 2. Bullish Entry (Short ATM PE): Close > SuperTrend(7, 3) AND Close > R1 AND (Sum
 * 3 OTM Put OI - Sum 3 OTM Call OI >= 50 Lakhs) -> Instantly SELL 5 lots ATM Put (PE) option on
 * candle close. 3. Bearish Entry (Short ATM CE): Close < SuperTrend(7, 3) AND Close < S1 AND (Sum 3
 * OTM Call OI - Sum 3 OTM Put OI >= 50 Lakhs) -> Instantly SELL 5 lots ATM Call (CE) option on
 * candle close. 4. Exit Rules: - Trailing Stop / Signal Exit: Exit active position when SuperTrend
 * (7, 3) flips direction. - Time Stop: Mandatory square-off at 15:14 IST. - Max Trades: 1 trade per
 * day.
 */
@Component
public class PivotSuperTrendOptionSellingStrategy implements IntradayStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(PivotSuperTrendOptionSellingStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final String STRATEGY_ID = "PIVOT_SUPERTREND_OPTION_SELLING";
    public static final String STRATEGY_NAME =
            "Intraday Directional Option Selling (Pivot R1/S1 + SuperTrend 7,3)";
    public static final LocalTime SQUARE_OFF_TIME = LocalTime.of(15, 14);

    public static final int ATR_PERIOD = 7;
    public static final double ST_MULTIPLIER = 3.0;
    public static final int MAX_DAILY_TRADES = 1;
    public static final int POSITION_LOTS =
            1; // Scaled down to 1 lot for production risk management
    public static final int NIFTY_LOT_SIZE = 65;
    public static final long DEFAULT_OI_DIFF_THRESHOLD = 5_000_000L; // 50 Lakhs
    public static final int DEFAULT_HEDGE_DISTANCE = 150; // 150 pts OTM protective hedge leg

    public enum PositionType {
        SHORT_PE,
        SHORT_CE
    }

    private final TechnicalAnalysisService taService;
    private final ShoonyaOptionChainService optionChainService;
    private final TelegramService telegramService;
    private boolean enabled = true;
    private boolean telegramAlertsEnabled = true;

    // Sizing Configurations
    @Value("${trading-bot.strategy.pivot-supertrend.lots:1}")
    private int lots = POSITION_LOTS;

    @Value("${trading-bot.strategy.pivot-supertrend.lot-size:65}")
    private int lotSize = NIFTY_LOT_SIZE;

    // Defined-Risk Credit Spread Configurations
    @Value("${trading-bot.strategy.pivot-supertrend.buy-hedge:true}")
    private boolean buyHedge = true;

    @Value("${trading-bot.strategy.pivot-supertrend.hedge-distance:150}")
    private int hedgeDistance = DEFAULT_HEDGE_DISTANCE;

    // Morning Entry Filter (Avoid open whipsaws)
    public static final LocalTime DEFAULT_ENTRY_START_TIME = LocalTime.of(9, 30);

    @Value("${trading-bot.strategy.pivot-supertrend.entry-start-time:09:30:00}")
    private LocalTime entryStartTime = DEFAULT_ENTRY_START_TIME;

    // Hard Stop Loss on Premium Configurations
    public static final double DEFAULT_STOP_LOSS_PERCENT = 30.0;

    @Value("${trading-bot.strategy.pivot-supertrend.stop-loss-enabled:true}")
    private boolean stopLossEnabled = true;

    @Value("${trading-bot.strategy.pivot-supertrend.stop-loss-percent:30.0}")
    private double stopLossPercent = DEFAULT_STOP_LOSS_PERCENT;

    // Target Profit Booking on Premium Decay Configurations
    public static final double DEFAULT_TARGET_PROFIT_PERCENT = 50.0;

    @Value("${trading-bot.strategy.pivot-supertrend.target-profit-enabled:true}")
    private boolean targetProfitEnabled = true;

    @Value("${trading-bot.strategy.pivot-supertrend.target-profit-percent:50.0}")
    private double targetProfitPercent = DEFAULT_TARGET_PROFIT_PERCENT;

    // OI Confirmation Filter Configurations (Disabled by default)
    @Value("${trading-bot.strategy.pivot-supertrend.oi-filter-enabled:false}")
    private boolean oiFilterEnabled = false;

    @Value("${trading-bot.strategy.pivot-supertrend.oi-diff-threshold:5000000}")
    private long oiDiffThreshold = DEFAULT_OI_DIFF_THRESHOLD;

    @Value("${trading-bot.strategy.pivot-supertrend.oi-directional:true}")
    private boolean oiDirectional = true;

    // Last evaluated 3-strike OTM OI metrics (for telemetry / status)
    private long lastOtmCallOi = 0L;
    private long lastOtmPutOi = 0L;
    private long lastOiDifference = 0L;
    private Long manualOtmCallOi = null;
    private Long manualOtmPutOi = null;

    // Daily Intraday State
    private LocalDate activeDay;
    private int dailyTradesCount = 0;

    // Pivot Levels
    private double pivotPoint = 0.0;
    private double r1Level = 0.0;
    private double s1Level = 0.0;
    private boolean pivotCalculated = false;

    // Active Position State
    private boolean inPosition = false;
    private PositionType positionType = null;
    private BigDecimal entryUnderlyingPrice = BigDecimal.ZERO;
    private BigDecimal atmStrike = BigDecimal.ZERO;
    private BigDecimal entryPremium = BigDecimal.ZERO;
    private String positionSymbol = "";
    private BigDecimal hedgeStrike = BigDecimal.ZERO;
    private BigDecimal hedgePremium = BigDecimal.ZERO;
    private String hedgeSymbol = "";

    @Autowired
    public PivotSuperTrendOptionSellingStrategy(
            TechnicalAnalysisService taService,
            @Autowired(required = false) ShoonyaOptionChainService optionChainService,
            @Autowired(required = false) TelegramService telegramService) {
        this.taService = taService;
        this.optionChainService = optionChainService;
        this.telegramService = telegramService;
    }

    public PivotSuperTrendOptionSellingStrategy(TechnicalAnalysisService taService) {
        this(taService, null, null);
    }

    @Override
    public String getId() {
        return STRATEGY_ID;
    }

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public List<String> getSubscribedSymbols() {
        return List.of("NIFTY50", "SENSEX");
    }

    @Override
    public String getTimeframe() {
        return "5m";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized void onResetDaily() {
        activeDay = null;
        dailyTradesCount = 0;
        pivotPoint = 0.0;
        r1Level = 0.0;
        s1Level = 0.0;
        pivotCalculated = false;
        inPosition = false;
        positionType = null;
        entryUnderlyingPrice = BigDecimal.ZERO;
        atmStrike = BigDecimal.ZERO;
        entryPremium = BigDecimal.ZERO;
        positionSymbol = "";
        hedgeStrike = BigDecimal.ZERO;
        hedgePremium = BigDecimal.ZERO;
        hedgeSymbol = "";
        lastOtmCallOi = 0L;
        lastOtmPutOi = 0L;
        lastOiDifference = 0L;
        manualOtmCallOi = null;
        manualOtmPutOi = null;
        log.info("[{}] Daily strategy state reset.", STRATEGY_ID);
    }

    @Override
    public synchronized TradeSignal onCandle(Candle latestCandle, List<Candle> history) {
        if (!enabled || latestCandle == null) {
            return TradeSignal.hold(getId(), "NIFTY50", "Strategy disabled or null candle");
        }

        LocalDate candleDate = latestCandle.timestamp().atZone(IST).toLocalDate();
        LocalTime candleTime = latestCandle.timestamp().atZone(IST).toLocalTime();

        // 1. Day boundary reset
        if (activeDay != null && !activeDay.equals(candleDate)) {
            onResetDaily();
        }
        activeDay = candleDate;

        // 2. Compute Daily Pivot Levels from previous session history if not yet calculated
        if (!pivotCalculated) {
            calculateDailyPivots(history, latestCandle);
        }

        // 3. Prepare full candle series for SuperTrend calculation
        List<Candle> allCandles = new ArrayList<>();
        if (history != null) {
            allCandles.addAll(history);
        }
        allCandles.add(latestCandle);

        int size = allCandles.size();
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];

        for (int i = 0; i < size; i++) {
            Candle c = allCandles.get(i);
            high[i] = c.high().doubleValue();
            low[i] = c.low().doubleValue();
            close[i] = c.close().doubleValue();
        }

        SuperTrendResult[] stSeries =
                taService.calculateSuperTrendSeries(high, low, close, ATR_PERIOD, ST_MULTIPLIER);
        SuperTrendResult currentSt = stSeries[size - 1];

        double currentClose = latestCandle.close().doubleValue();

        // 4. Mandatory 15:14 IST Square-Off
        if (candleTime.isAfter(SQUARE_OFF_TIME) || candleTime.equals(SQUARE_OFF_TIME)) {
            if (inPosition) {
                log.info(
                        "[{}] Mandatory 15:14 IST cutoff. Squaring off active position {}.",
                        STRATEGY_ID,
                        positionSymbol);
                boolean isShortPe = (positionType == PositionType.SHORT_PE);
                String optType = isShortPe ? "PE" : "CE";
                BigDecimal exitUnderlyingPrice = latestCandle.close();
                BigDecimal exitPremium = fetchOptionPremium(atmStrike, optType);
                if (exitPremium == null
                        && entryPremium != null
                        && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                    double underlyingDiff =
                            isShortPe
                                    ? exitUnderlyingPrice
                                            .subtract(entryUnderlyingPrice)
                                            .doubleValue()
                                    : entryUnderlyingPrice
                                            .subtract(exitUnderlyingPrice)
                                            .doubleValue();
                    double estExit =
                            Math.max(1.0, entryPremium.doubleValue() - (underlyingDiff * 0.50));
                    exitPremium = BigDecimal.valueOf(estExit).setScale(2, RoundingMode.HALF_UP);
                } else if (exitPremium == null) {
                    exitPremium = BigDecimal.valueOf(150.00);
                }

                if (telegramService != null && telegramAlertsEnabled) {
                    telegramService.sendStrategyExitAlert(
                            STRATEGY_NAME,
                            "NIFTY 50",
                            "MANDATORY SQUARE-OFF",
                            exitUnderlyingPrice,
                            atmStrike,
                            optType,
                            entryPremium,
                            exitPremium,
                            "Mandatory 15:14 IST Square-Off",
                            latestCandle.timestamp());
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("strategyName", STRATEGY_NAME);
                meta.put("r1", r1Level);
                meta.put("s1", s1Level);
                meta.put("supertrend", currentSt.value());
                meta.put("strike", atmStrike);
                meta.put("optionType", optType);
                meta.put("entryPrice", entryUnderlyingPrice);
                meta.put("entryPremium", entryPremium);
                meta.put("exitPrice", exitUnderlyingPrice);
                meta.put("exitPremium", exitPremium);
                meta.put("lots", this.lots);
                meta.put("quantity", this.lots * this.lotSize);
                meta.put("buyHedge", this.buyHedge);
                meta.put("hedgeStrike", this.hedgeStrike);
                meta.put("hedgeSymbol", this.hedgeSymbol);
                meta.put("exitReason", "MANDATORY_SQUARE_OFF");

                TradeSignal squareOff =
                        TradeSignal.of(
                                getId(),
                                positionSymbol,
                                SignalAction.SQUARE_OFF,
                                latestCandle.close(),
                                null,
                                null,
                                this.lots,
                                "Mandatory 15:14 IST Square-Off",
                                meta);
                inPosition = false;
                positionType = null;
                hedgeStrike = BigDecimal.ZERO;
                hedgePremium = BigDecimal.ZERO;
                hedgeSymbol = "";
                return squareOff;
            }
            return TradeSignal.hold(
                    getId(), "NIFTY50", "Past 15:14 IST cutoff, no new entries permitted");
        }

        // 5. Active Position Management (Hard Stop Loss + SuperTrend Flip)
        if (inPosition) {
            boolean isShortPe = (positionType == PositionType.SHORT_PE);
            String optType = isShortPe ? "PE" : "CE";
            BigDecimal currentPremium = fetchOptionPremium(atmStrike, optType);
            if (currentPremium == null
                    && entryPremium != null
                    && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                double underlyingDiff =
                        isShortPe
                                ? entryUnderlyingPrice.subtract(latestCandle.close()).doubleValue()
                                : latestCandle.close().subtract(entryUnderlyingPrice).doubleValue();
                double estCurrent =
                        Math.max(1.0, entryPremium.doubleValue() + (underlyingDiff * 0.50));
                currentPremium = BigDecimal.valueOf(estCurrent).setScale(2, RoundingMode.HALF_UP);
            } else if (currentPremium == null) {
                currentPremium = BigDecimal.valueOf(150.00);
            }

            // 5a. Hard Stop Loss Check on Option Premium (e.g., 30% expansion)
            if (stopLossEnabled
                    && entryPremium != null
                    && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal slMultiplier = BigDecimal.valueOf(1.0 + (stopLossPercent / 100.0));
                BigDecimal stopLossPremium =
                        entryPremium.multiply(slMultiplier).setScale(2, RoundingMode.HALF_UP);

                if (currentPremium != null && currentPremium.compareTo(stopLossPremium) >= 0) {
                    log.info(
                            "[{}] HARD STOP LOSS TRIGGERED! Current Premium (₹{}) exceeded {}% SL threshold (₹{}). Exiting {} position {}.",
                            STRATEGY_ID,
                            currentPremium,
                            stopLossPercent,
                            stopLossPremium,
                            positionType,
                            positionSymbol);

                    BigDecimal exitUnderlyingPrice = latestCandle.close();
                    if (telegramService != null && telegramAlertsEnabled) {
                        telegramService.sendStrategyExitAlert(
                                STRATEGY_NAME,
                                "NIFTY 50",
                                isShortPe ? "SL HIT (EXIT SHORT PE)" : "SL HIT (EXIT SHORT CE)",
                                exitUnderlyingPrice,
                                atmStrike,
                                optType,
                                entryPremium,
                                currentPremium,
                                String.format(
                                        "Hard Stop Loss Hit: Premium ₹%.2f reached %.0f%% SL threshold ₹%.2f",
                                        currentPremium, stopLossPercent, stopLossPremium),
                                latestCandle.timestamp());
                    }

                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("strategyName", STRATEGY_NAME);
                    meta.put("supertrend", currentSt.value());
                    meta.put("close", currentClose);
                    meta.put("strike", atmStrike);
                    meta.put("optionType", optType);
                    meta.put("entryPrice", entryUnderlyingPrice);
                    meta.put("entryPremium", entryPremium);
                    meta.put("exitPrice", exitUnderlyingPrice);
                    meta.put("exitPremium", currentPremium);
                    meta.put("stopLossPremium", stopLossPremium);
                    meta.put("lots", this.lots);
                    meta.put("quantity", this.lots * this.lotSize);
                    meta.put("buyHedge", this.buyHedge);
                    meta.put("hedgeStrike", this.hedgeStrike);
                    meta.put("hedgeSymbol", this.hedgeSymbol);
                    meta.put("exitReason", "HARD_STOP_LOSS_HIT");

                    inPosition = false;
                    positionType = null;
                    hedgeStrike = BigDecimal.ZERO;
                    hedgePremium = BigDecimal.ZERO;
                    hedgeSymbol = "";
                    SignalAction exitAction =
                            isShortPe ? SignalAction.EXIT_SHORT : SignalAction.EXIT_LONG;
                    String slReason =
                            String.format(
                                    "Hard Stop Loss Hit: Premium ₹%.2f exceeded %.0f%% SL (₹%.2f)",
                                    currentPremium, stopLossPercent, stopLossPremium);
                    return TradeSignal.of(
                            getId(),
                            positionSymbol,
                            exitAction,
                            latestCandle.close(),
                            null,
                            null,
                            this.lots,
                            slReason,
                            meta);
                }
            }

            // 5b. Take Profit Target Booking Check on Option Premium Decay (e.g., 50% decay)
            if (targetProfitEnabled
                    && entryPremium != null
                    && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal targetMultiplier =
                        BigDecimal.valueOf(Math.max(0.0, 1.0 - (targetProfitPercent / 100.0)));
                BigDecimal targetPremium =
                        entryPremium.multiply(targetMultiplier).setScale(2, RoundingMode.HALF_UP);

                if (currentPremium != null && currentPremium.compareTo(targetPremium) <= 0) {
                    log.info(
                            "[{}] TARGET PROFIT HIT! Current Premium (₹{}) decayed by {}% to target (₹{}). Exiting {} position {}.",
                            STRATEGY_ID,
                            currentPremium,
                            targetProfitPercent,
                            targetPremium,
                            positionType,
                            positionSymbol);

                    BigDecimal exitUnderlyingPrice = latestCandle.close();
                    if (telegramService != null && telegramAlertsEnabled) {
                        telegramService.sendStrategyExitAlert(
                                STRATEGY_NAME,
                                "NIFTY 50",
                                isShortPe
                                        ? "TARGET HIT (EXIT SHORT PE)"
                                        : "TARGET HIT (EXIT SHORT CE)",
                                exitUnderlyingPrice,
                                atmStrike,
                                optType,
                                entryPremium,
                                currentPremium,
                                String.format(
                                        "Take Profit Target Hit: Premium decayed by %.0f%% to ₹%.2f (Target: ₹%.2f)",
                                        targetProfitPercent, currentPremium, targetPremium),
                                latestCandle.timestamp());
                    }

                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("strategyName", STRATEGY_NAME);
                    meta.put("supertrend", currentSt.value());
                    meta.put("close", currentClose);
                    meta.put("strike", atmStrike);
                    meta.put("optionType", optType);
                    meta.put("entryPrice", entryUnderlyingPrice);
                    meta.put("entryPremium", entryPremium);
                    meta.put("exitPrice", exitUnderlyingPrice);
                    meta.put("exitPremium", currentPremium);
                    meta.put("targetPremium", targetPremium);
                    meta.put("lots", this.lots);
                    meta.put("quantity", this.lots * this.lotSize);
                    meta.put("buyHedge", this.buyHedge);
                    meta.put("hedgeStrike", this.hedgeStrike);
                    meta.put("hedgeSymbol", this.hedgeSymbol);
                    meta.put("exitReason", "TARGET_PROFIT_BOOKED");

                    inPosition = false;
                    positionType = null;
                    hedgeStrike = BigDecimal.ZERO;
                    hedgePremium = BigDecimal.ZERO;
                    hedgeSymbol = "";
                    SignalAction exitAction =
                            isShortPe ? SignalAction.EXIT_SHORT : SignalAction.EXIT_LONG;
                    String targetReason =
                            String.format(
                                    "Take Profit Target Hit: Premium decayed by %.0f%% to ₹%.2f (Target: ₹%.2f)",
                                    targetProfitPercent, currentPremium, targetPremium);
                    return TradeSignal.of(
                            getId(),
                            positionSymbol,
                            exitAction,
                            latestCandle.close(),
                            null,
                            null,
                            this.lots,
                            targetReason,
                            meta);
                }
            }

            // 5c. SuperTrend Trend Flip Exit
            if (positionType == PositionType.SHORT_PE && !currentSt.isBullish()) {
                log.info(
                        "[{}] SuperTrend flipped Bearish (Red)! Exiting Short PE position {}.",
                        STRATEGY_ID,
                        positionSymbol);
                BigDecimal exitUnderlyingPrice = latestCandle.close();
                BigDecimal exitPremium = fetchOptionPremium(atmStrike, "PE");
                if (exitPremium == null
                        && entryPremium != null
                        && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                    double underlyingDiff =
                            exitUnderlyingPrice.subtract(entryUnderlyingPrice).doubleValue();
                    double estExit =
                            Math.max(1.0, entryPremium.doubleValue() - (underlyingDiff * 0.50));
                    exitPremium = BigDecimal.valueOf(estExit).setScale(2, RoundingMode.HALF_UP);
                } else if (exitPremium == null) {
                    exitPremium = BigDecimal.valueOf(150.00);
                }

                if (telegramService != null && telegramAlertsEnabled) {
                    telegramService.sendStrategyExitAlert(
                            STRATEGY_NAME,
                            "NIFTY 50",
                            "EXIT SHORT PE",
                            exitUnderlyingPrice,
                            atmStrike,
                            "PE",
                            entryPremium,
                            exitPremium,
                            "SuperTrend flipped Bearish (Exit Short PE)",
                            latestCandle.timestamp());
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("strategyName", STRATEGY_NAME);
                meta.put("supertrend", currentSt.value());
                meta.put("close", currentClose);
                meta.put("strike", atmStrike);
                meta.put("optionType", "PE");
                meta.put("entryPrice", entryUnderlyingPrice);
                meta.put("entryPremium", entryPremium);
                meta.put("exitPrice", exitUnderlyingPrice);
                meta.put("exitPremium", exitPremium);
                meta.put("lots", this.lots);
                meta.put("quantity", this.lots * this.lotSize);
                meta.put("buyHedge", this.buyHedge);
                meta.put("hedgeStrike", this.hedgeStrike);
                meta.put("hedgeSymbol", this.hedgeSymbol);
                meta.put("exitReason", "SUPERTREND_FLIP");

                inPosition = false;
                positionType = null;
                hedgeStrike = BigDecimal.ZERO;
                hedgePremium = BigDecimal.ZERO;
                hedgeSymbol = "";
                return TradeSignal.of(
                        getId(),
                        positionSymbol,
                        SignalAction.EXIT_SHORT,
                        latestCandle.close(),
                        null,
                        null,
                        this.lots,
                        "SuperTrend flipped Bearish (Exit Short PE)",
                        meta);
            } else if (positionType == PositionType.SHORT_CE && currentSt.isBullish()) {
                log.info(
                        "[{}] SuperTrend flipped Bullish (Green)! Exiting Short CE position {}.",
                        STRATEGY_ID,
                        positionSymbol);
                BigDecimal exitUnderlyingPrice = latestCandle.close();
                BigDecimal exitPremium = fetchOptionPremium(atmStrike, "CE");
                if (exitPremium == null
                        && entryPremium != null
                        && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                    double underlyingDiff =
                            entryUnderlyingPrice.subtract(exitUnderlyingPrice).doubleValue();
                    double estExit =
                            Math.max(1.0, entryPremium.doubleValue() - (underlyingDiff * 0.50));
                    exitPremium = BigDecimal.valueOf(estExit).setScale(2, RoundingMode.HALF_UP);
                } else if (exitPremium == null) {
                    exitPremium = BigDecimal.valueOf(150.00);
                }

                if (telegramService != null && telegramAlertsEnabled) {
                    telegramService.sendStrategyExitAlert(
                            STRATEGY_NAME,
                            "NIFTY 50",
                            "EXIT SHORT CE",
                            exitUnderlyingPrice,
                            atmStrike,
                            "CE",
                            entryPremium,
                            exitPremium,
                            "SuperTrend flipped Bullish (Exit Short CE)",
                            latestCandle.timestamp());
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("strategyName", STRATEGY_NAME);
                meta.put("supertrend", currentSt.value());
                meta.put("close", currentClose);
                meta.put("strike", atmStrike);
                meta.put("optionType", "CE");
                meta.put("entryPrice", entryUnderlyingPrice);
                meta.put("entryPremium", entryPremium);
                meta.put("exitPrice", exitUnderlyingPrice);
                meta.put("exitPremium", exitPremium);
                meta.put("lots", this.lots);
                meta.put("quantity", this.lots * this.lotSize);
                meta.put("buyHedge", this.buyHedge);
                meta.put("hedgeStrike", this.hedgeStrike);
                meta.put("hedgeSymbol", this.hedgeSymbol);
                meta.put("exitReason", "SUPERTREND_FLIP");

                inPosition = false;
                positionType = null;
                hedgeStrike = BigDecimal.ZERO;
                hedgePremium = BigDecimal.ZERO;
                hedgeSymbol = "";
                return TradeSignal.of(
                        getId(),
                        positionSymbol,
                        SignalAction.EXIT_LONG,
                        latestCandle.close(),
                        null,
                        null,
                        this.lots,
                        "SuperTrend flipped Bullish (Exit Short CE)",
                        meta);
            }

            return TradeSignal.hold(
                    getId(),
                    positionSymbol,
                    "Holding "
                            + positionType
                            + " - tracking SuperTrend ("
                            + currentSt.trend()
                            + ")");
        }

        // 6. Entry Condition Checks (Flat, Daily trades < MAX_DAILY_TRADES, SuperTrend valid)
        if (dailyTradesCount >= MAX_DAILY_TRADES) {
            return TradeSignal.hold(
                    getId(), "NIFTY50", "Max daily trades (" + MAX_DAILY_TRADES + ") reached");
        }

        if (candleTime.isBefore(entryStartTime)) {
            return TradeSignal.hold(
                    getId(),
                    "NIFTY50",
                    "Morning open filter: Waiting for "
                            + entryStartTime
                            + " IST before taking new entries");
        }

        if (Double.isNaN(currentSt.value()) || !pivotCalculated) {
            return TradeSignal.hold(getId(), "NIFTY50", "Warming up SuperTrend / Pivot Levels");
        }

        // Rule 1: Bullish Entry (Short ATM PE) -> Close > SuperTrend AND Close > R1 AND (PE OI - CE
        // OI >= 50L)
        if (currentClose > currentSt.value() && currentSt.isBullish() && currentClose > r1Level) {
            BigDecimal atm =
                    latestCandle
                            .close()
                            .divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(50));
            OtmOiAnalysis oi = calculate3OtmOi(atm, "PE");

            if (oiFilterEnabled) {
                if (!oi.valid()) {
                    return TradeSignal.hold(
                            getId(),
                            "NIFTY50",
                            "Bullish confluence met, but Option Chain OI data unavailable");
                }
                long peExcess = oi.sumOtmPutOi() - oi.sumOtmCallOi();
                long effectiveDiff = oiDirectional ? peExcess : Math.abs(peExcess);
                if (effectiveDiff < oiDiffThreshold) {
                    log.info(
                            "[{}] Bullish setup filtered out by OI. 3-OTM Put OI ({}) - Call OI ({}) diff {} < threshold {}",
                            STRATEGY_ID,
                            oi.sumOtmPutOi(),
                            oi.sumOtmCallOi(),
                            effectiveDiff,
                            oiDiffThreshold);
                    return TradeSignal.hold(
                            getId(),
                            "NIFTY50",
                            String.format(
                                    "Bullish confluence met, but 3-OTM Put OI (%d) vs Call OI (%d) diff (%d) < %d threshold",
                                    oi.sumOtmPutOi(),
                                    oi.sumOtmCallOi(),
                                    effectiveDiff,
                                    oiDiffThreshold));
                }
            }

            inPosition = true;
            positionType = PositionType.SHORT_PE;
            entryUnderlyingPrice = latestCandle.close();
            atmStrike = atm;
            entryPremium =
                    (oi.atmPremium() != null && oi.atmPremium().compareTo(BigDecimal.ZERO) > 0)
                            ? oi.atmPremium()
                            : fetchOptionPremium(atmStrike, "PE");
            if (entryPremium == null) {
                entryPremium = BigDecimal.valueOf(150.00);
            }
            positionSymbol = "NIFTY_SHORT_PE_" + atmStrike.intValue();
            dailyTradesCount++;

            if (buyHedge) {
                hedgeStrike = atm.subtract(BigDecimal.valueOf(hedgeDistance));
                hedgePremium = fetchOptionPremium(hedgeStrike, "PE");
                if (hedgePremium == null) {
                    hedgePremium = BigDecimal.valueOf(5.00);
                }
                hedgeSymbol = "NIFTY_BUY_PE_" + hedgeStrike.intValue();
            } else {
                hedgeStrike = BigDecimal.ZERO;
                hedgePremium = BigDecimal.ZERO;
                hedgeSymbol = "";
            }

            long oiDiffReport = oi.sumOtmPutOi() - oi.sumOtmCallOi();
            BigDecimal netCredit = entryPremium.subtract(hedgePremium);
            BigDecimal maxRiskPerShare =
                    buyHedge
                            ? BigDecimal.valueOf(hedgeDistance).subtract(netCredit)
                            : BigDecimal.ZERO;
            int totalQty = this.lots * this.lotSize;

            if (buyHedge) {
                log.info(
                        "[{}] Bull Put Spread Confirmed! Sell ATM {} PE @ ₹{} + Buy Hedge {} PE @ ₹{} (Net Credit: ₹{}, Max Risk: ₹{}). Close ({}) > ST ({}) & R1 ({})",
                        STRATEGY_ID,
                        atmStrike,
                        entryPremium,
                        hedgeStrike,
                        hedgePremium,
                        netCredit,
                        maxRiskPerShare.multiply(BigDecimal.valueOf(totalQty)),
                        currentClose,
                        currentSt.value(),
                        r1Level);
            } else {
                log.info(
                        "[{}] Bullish Trigger Confirmed! Close ({}) > ST ({}) AND Close > R1 ({}). Selling ATM PE ({}). Premium: ₹{}",
                        STRATEGY_ID,
                        currentClose,
                        currentSt.value(),
                        r1Level,
                        atmStrike,
                        entryPremium);
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("strategyName", STRATEGY_NAME);
            meta.put("r1", r1Level);
            meta.put("supertrend", currentSt.value());
            meta.put("atmStrike", atmStrike);
            meta.put("entryPrice", entryUnderlyingPrice);
            meta.put("entryPremium", entryPremium);
            meta.put("optionType", "PE");
            meta.put("lots", this.lots);
            meta.put("quantity", totalQty);
            meta.put("buyHedge", this.buyHedge);
            meta.put("hedgeDistance", this.hedgeDistance);
            meta.put("hedgeStrike", this.hedgeStrike);
            meta.put("hedgePremium", this.hedgePremium);
            meta.put("netCredit", netCredit);
            meta.put("maxRisk", maxRiskPerShare.multiply(BigDecimal.valueOf(totalQty)));
            meta.put("otmCallOi", oi.sumOtmCallOi());
            meta.put("otmPutOi", oi.sumOtmPutOi());
            meta.put("oiDifference", oiDiffReport);
            meta.put("tradeNum", dailyTradesCount);

            if (telegramService != null && telegramAlertsEnabled) {
                Map<String, Object> telegramExtra = new LinkedHashMap<>();
                if (buyHedge) {
                    telegramExtra.put("spreadType", "Bull Put Spread (Credit Spread)");
                    telegramExtra.put(
                            "sellLeg",
                            String.format("ATM %.0f PE @ ₹%.2f", atmStrike, entryPremium));
                    telegramExtra.put(
                            "buyHedgeLeg",
                            String.format(
                                    "%.0f PE @ ₹%.2f (%d pt Hedge)",
                                    hedgeStrike, hedgePremium, hedgeDistance));
                    telegramExtra.put("netCredit", String.format("₹%.2f/sh", netCredit));
                    telegramExtra.put(
                            "maxRisk",
                            String.format(
                                    "₹%.2f (Defined Risk Cap)",
                                    maxRiskPerShare.multiply(BigDecimal.valueOf(totalQty))));
                }
                telegramExtra.put("lots", String.valueOf(this.lots));
                telegramExtra.put("quantity", String.valueOf(totalQty));
                telegramExtra.put("r1", String.format("%.2f", r1Level));
                telegramExtra.put("supertrend", String.format("%.2f", currentSt.value()));
                if (oiFilterEnabled) {
                    telegramExtra.put("oiDiff", String.valueOf(oiDiffReport));
                }
                telegramExtra.put("tradeNum", String.valueOf(dailyTradesCount));

                String actionTitle =
                        buyHedge
                                ? String.format(
                                        "BULL PUT SPREAD (%d Lot: Sell %.0f PE + Buy %.0f PE)",
                                        this.lots, atmStrike, hedgeStrike)
                                : String.format(
                                        "SELL %d LOT ATM PE (Bullish Confluence)", this.lots);

                telegramService.sendStrategySignalAlert(
                        STRATEGY_NAME,
                        "NIFTY 50",
                        actionTitle,
                        entryUnderlyingPrice,
                        atmStrike,
                        "PE",
                        entryPremium,
                        telegramExtra,
                        latestCandle.timestamp());
            }

            String bullishReason =
                    buyHedge
                            ? String.format(
                                    "Bull Put Spread (Sell %d Lot %.0f PE @ ₹%.2f + Buy Hedge %.0f PE @ ₹%.2f, Net Credit: ₹%.2f): Close > ST & R1",
                                    this.lots,
                                    atmStrike,
                                    entryPremium,
                                    hedgeStrike,
                                    hedgePremium,
                                    netCredit)
                            : String.format(
                                    "Bullish Confluence: Close > ST & R1 (Sell %d lot ATM PE %.0f @ ₹%.2f)",
                                    this.lots, atmStrike, entryPremium);

            return TradeSignal.of(
                    getId(),
                    positionSymbol,
                    SignalAction.SELL,
                    latestCandle.close(),
                    BigDecimal.valueOf(currentSt.value()),
                    null,
                    this.lots,
                    bullishReason,
                    meta);
        }

        // Rule 2: Bearish Entry (Short ATM CE) -> Close < SuperTrend AND Close < S1 AND (CE OI - PE
        // OI >= 50L)
        if (currentClose < currentSt.value() && !currentSt.isBullish() && currentClose < s1Level) {
            BigDecimal atm =
                    latestCandle
                            .close()
                            .divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(50));
            OtmOiAnalysis oi = calculate3OtmOi(atm, "CE");

            if (oiFilterEnabled) {
                if (!oi.valid()) {
                    return TradeSignal.hold(
                            getId(),
                            "NIFTY50",
                            "Bearish confluence met, but Option Chain OI data unavailable");
                }
                long ceExcess = oi.sumOtmCallOi() - oi.sumOtmPutOi();
                long effectiveDiff = oiDirectional ? ceExcess : Math.abs(ceExcess);
                if (effectiveDiff < oiDiffThreshold) {
                    log.info(
                            "[{}] Bearish setup filtered out by OI. 3-OTM Call OI ({}) - Put OI ({}) diff {} < threshold {}",
                            STRATEGY_ID,
                            oi.sumOtmCallOi(),
                            oi.sumOtmPutOi(),
                            effectiveDiff,
                            oiDiffThreshold);
                    return TradeSignal.hold(
                            getId(),
                            "NIFTY50",
                            String.format(
                                    "Bearish confluence met, but 3-OTM Call OI (%d) vs Put OI (%d) diff (%d) < %d threshold",
                                    oi.sumOtmCallOi(),
                                    oi.sumOtmPutOi(),
                                    effectiveDiff,
                                    oiDiffThreshold));
                }
            }

            inPosition = true;
            positionType = PositionType.SHORT_CE;
            entryUnderlyingPrice = latestCandle.close();
            atmStrike = atm;
            entryPremium =
                    (oi.atmPremium() != null && oi.atmPremium().compareTo(BigDecimal.ZERO) > 0)
                            ? oi.atmPremium()
                            : fetchOptionPremium(atmStrike, "CE");
            if (entryPremium == null) {
                entryPremium = BigDecimal.valueOf(150.00);
            }
            positionSymbol = "NIFTY_SHORT_CE_" + atmStrike.intValue();
            dailyTradesCount++;

            if (buyHedge) {
                hedgeStrike = atm.add(BigDecimal.valueOf(hedgeDistance));
                hedgePremium = fetchOptionPremium(hedgeStrike, "CE");
                if (hedgePremium == null) {
                    hedgePremium = BigDecimal.valueOf(5.00);
                }
                hedgeSymbol = "NIFTY_BUY_CE_" + hedgeStrike.intValue();
            } else {
                hedgeStrike = BigDecimal.ZERO;
                hedgePremium = BigDecimal.ZERO;
                hedgeSymbol = "";
            }

            long oiDiffReport = oi.sumOtmCallOi() - oi.sumOtmPutOi();
            BigDecimal netCredit = entryPremium.subtract(hedgePremium);
            BigDecimal maxRiskPerShare =
                    buyHedge
                            ? BigDecimal.valueOf(hedgeDistance).subtract(netCredit)
                            : BigDecimal.ZERO;
            int totalQty = this.lots * this.lotSize;

            if (buyHedge) {
                log.info(
                        "[{}] Bear Call Spread Confirmed! Sell ATM {} CE @ ₹{} + Buy Hedge {} CE @ ₹{} (Net Credit: ₹{}, Max Risk: ₹{}). Close ({}) < ST ({}) & S1 ({})",
                        STRATEGY_ID,
                        atmStrike,
                        entryPremium,
                        hedgeStrike,
                        hedgePremium,
                        netCredit,
                        maxRiskPerShare.multiply(BigDecimal.valueOf(totalQty)),
                        currentClose,
                        currentSt.value(),
                        s1Level);
            } else {
                log.info(
                        "[{}] Bearish Trigger Confirmed! Close ({}) < ST ({}) AND Close < S1 ({}). Selling ATM CE ({}). Premium: ₹{}",
                        STRATEGY_ID,
                        currentClose,
                        currentSt.value(),
                        s1Level,
                        atmStrike,
                        entryPremium);
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("strategyName", STRATEGY_NAME);
            meta.put("s1", s1Level);
            meta.put("supertrend", currentSt.value());
            meta.put("atmStrike", atmStrike);
            meta.put("entryPrice", entryUnderlyingPrice);
            meta.put("entryPremium", entryPremium);
            meta.put("optionType", "CE");
            meta.put("lots", this.lots);
            meta.put("quantity", totalQty);
            meta.put("buyHedge", this.buyHedge);
            meta.put("hedgeDistance", this.hedgeDistance);
            meta.put("hedgeStrike", this.hedgeStrike);
            meta.put("hedgePremium", this.hedgePremium);
            meta.put("netCredit", netCredit);
            meta.put("maxRisk", maxRiskPerShare.multiply(BigDecimal.valueOf(totalQty)));
            meta.put("otmCallOi", oi.sumOtmCallOi());
            meta.put("otmPutOi", oi.sumOtmPutOi());
            meta.put("oiDifference", oiDiffReport);
            meta.put("tradeNum", dailyTradesCount);

            if (telegramService != null && telegramAlertsEnabled) {
                Map<String, Object> telegramExtra = new LinkedHashMap<>();
                if (buyHedge) {
                    telegramExtra.put("spreadType", "Bear Call Spread (Credit Spread)");
                    telegramExtra.put(
                            "sellLeg",
                            String.format("ATM %.0f CE @ ₹%.2f", atmStrike, entryPremium));
                    telegramExtra.put(
                            "buyHedgeLeg",
                            String.format(
                                    "%.0f CE @ ₹%.2f (%d pt Hedge)",
                                    hedgeStrike, hedgePremium, hedgeDistance));
                    telegramExtra.put("netCredit", String.format("₹%.2f/sh", netCredit));
                    telegramExtra.put(
                            "maxRisk",
                            String.format(
                                    "₹%.2f (Defined Risk Cap)",
                                    maxRiskPerShare.multiply(BigDecimal.valueOf(totalQty))));
                }
                telegramExtra.put("lots", String.valueOf(this.lots));
                telegramExtra.put("quantity", String.valueOf(totalQty));
                telegramExtra.put("s1", String.format("%.2f", s1Level));
                telegramExtra.put("supertrend", String.format("%.2f", currentSt.value()));
                if (oiFilterEnabled) {
                    telegramExtra.put("oiDiff", String.valueOf(oiDiffReport));
                }
                telegramExtra.put("tradeNum", String.valueOf(dailyTradesCount));

                String actionTitle =
                        buyHedge
                                ? String.format(
                                        "BEAR CALL SPREAD (%d Lot: Sell %.0f CE + Buy %.0f CE)",
                                        this.lots, atmStrike, hedgeStrike)
                                : String.format(
                                        "SELL %d LOT ATM CE (Bearish Confluence)", this.lots);

                telegramService.sendStrategySignalAlert(
                        STRATEGY_NAME,
                        "NIFTY 50",
                        actionTitle,
                        entryUnderlyingPrice,
                        atmStrike,
                        "CE",
                        entryPremium,
                        telegramExtra,
                        latestCandle.timestamp());
            }

            String bearishReason =
                    buyHedge
                            ? String.format(
                                    "Bear Call Spread (Sell %d Lot %.0f CE @ ₹%.2f + Buy Hedge %.0f CE @ ₹%.2f, Net Credit: ₹%.2f): Close < ST & S1",
                                    this.lots,
                                    atmStrike,
                                    entryPremium,
                                    hedgeStrike,
                                    hedgePremium,
                                    netCredit)
                            : String.format(
                                    "Bearish Confluence: Close < ST & S1 (Sell %d lot ATM CE %.0f @ ₹%.2f)",
                                    this.lots, atmStrike, entryPremium);

            return TradeSignal.of(
                    getId(),
                    positionSymbol,
                    SignalAction.SELL,
                    latestCandle.close(),
                    BigDecimal.valueOf(currentSt.value()),
                    null,
                    this.lots,
                    bearishReason,
                    meta);
        }

        return TradeSignal.hold(
                getId(), "NIFTY50", "Scanning for Pivot R1/S1 + SuperTrend + OI breakout");
    }

    /**
     * Immediately forces square-off of any active strategy position, sends Telegram exit alert, and
     * clears position state.
     */
    public synchronized TradeSignal forceSquareOff(String reason) {
        if (!inPosition) {
            return TradeSignal.hold(getId(), "NIFTY50", "No active position to square off");
        }

        log.info("[{}] Force square-off executed. Reason: {}", STRATEGY_ID, reason);
        boolean isShortPe = (positionType == PositionType.SHORT_PE);
        String optType = isShortPe ? "PE" : "CE";
        BigDecimal exitPrice = entryUnderlyingPrice;
        BigDecimal exitPremium = fetchOptionPremium(atmStrike, optType);
        if (exitPremium == null
                && entryPremium != null
                && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
            exitPremium = entryPremium;
        } else if (exitPremium == null) {
            exitPremium = BigDecimal.valueOf(150.00);
        }

        if (telegramService != null && telegramAlertsEnabled) {
            telegramService.sendStrategyExitAlert(
                    STRATEGY_NAME,
                    "NIFTY 50",
                    "MANUAL / SCHEDULED SQUARE-OFF",
                    exitPrice,
                    atmStrike,
                    optType,
                    entryPremium,
                    exitPremium,
                    reason != null ? reason : "Forced Square-Off",
                    Instant.now());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("strategyName", STRATEGY_NAME);
        meta.put("strike", atmStrike);
        meta.put("optionType", optType);
        meta.put("entryPrice", entryUnderlyingPrice);
        meta.put("entryPremium", entryPremium);
        meta.put("exitPrice", exitPrice);
        meta.put("exitPremium", exitPremium);
        meta.put("lots", this.lots);
        meta.put("quantity", this.lots * this.lotSize);
        meta.put("buyHedge", this.buyHedge);
        meta.put("hedgeStrike", this.hedgeStrike);
        meta.put("hedgeSymbol", this.hedgeSymbol);
        meta.put("exitReason", reason != null ? reason : "FORCED_SQUARE_OFF");

        TradeSignal squareOff =
                TradeSignal.of(
                        getId(),
                        positionSymbol,
                        SignalAction.SQUARE_OFF,
                        exitPrice,
                        null,
                        null,
                        this.lots,
                        reason != null ? reason : "Forced Square-Off",
                        meta);

        inPosition = false;
        positionType = null;
        hedgeStrike = BigDecimal.ZERO;
        hedgePremium = BigDecimal.ZERO;
        hedgeSymbol = "";

        return squareOff;
    }

    public BigDecimal fetchOptionPremium(BigDecimal strike, String optionType) {
        if (optionChainService != null) {
            try {
                OptionChainResponse chain =
                        optionChainService.getNifty50OptionChain(strike, 2, true);
                if (chain != null && chain.strikes() != null) {
                    for (OptionStrike s : chain.strikes()) {
                        if (s.strikePrice().compareTo(strike) == 0) {
                            OptionContract contract =
                                    "PE".equalsIgnoreCase(optionType) ? s.put() : s.call();
                            if (contract != null
                                    && contract.ltp() != null
                                    && contract.ltp().compareTo(BigDecimal.ZERO) > 0) {
                                return contract.ltp();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "[{}] Failed to fetch option premium for {} {}: {}",
                        STRATEGY_ID,
                        strike,
                        optionType,
                        e.getMessage());
            }
        }
        return null;
    }

    private void calculateDailyPivots(List<Candle> history, Candle currentCandle) {
        if (history == null || history.isEmpty()) {
            // Fallback estimation around current candle
            double base = currentCandle.open().doubleValue();
            pivotPoint = base;
            r1Level = base + 50.0;
            s1Level = base - 50.0;
            pivotCalculated = true;
            return;
        }

        // Find single immediately preceding trading day's candles in history
        LocalDate today = currentCandle.timestamp().atZone(IST).toLocalDate();
        List<Candle> prevDayBars = new ArrayList<>();
        LocalDate prevTradingDay = null;

        for (int i = history.size() - 1; i >= 0; i--) {
            Candle c = history.get(i);
            LocalDate barDate = c.timestamp().atZone(IST).toLocalDate();
            if (barDate.isBefore(today)) {
                if (prevTradingDay == null) {
                    prevTradingDay = barDate;
                }
                if (barDate.equals(prevTradingDay)) {
                    prevDayBars.add(c);
                } else {
                    break;
                }
            }
        }

        if (prevDayBars.isEmpty()) {
            double base = currentCandle.open().doubleValue();
            pivotPoint = base;
            r1Level = base + 50.0;
            s1Level = base - 50.0;
            pivotCalculated = true;
            return;
        }

        double hPrev = Double.MIN_VALUE;
        double lPrev = Double.MAX_VALUE;
        double cPrev = prevDayBars.get(0).close().doubleValue(); // latest bar of prev day

        for (Candle c : prevDayBars) {
            if (c.high().doubleValue() > hPrev) hPrev = c.high().doubleValue();
            if (c.low().doubleValue() < lPrev) lPrev = c.low().doubleValue();
        }

        pivotPoint = (hPrev + lPrev + cPrev) / 3.0;
        r1Level = 2 * pivotPoint - lPrev;
        s1Level = 2 * pivotPoint - hPrev;
        pivotCalculated = true;

        log.info(
                "[{}] Calculated Daily Pivots for {}: Pivot={:.2f}, R1={:.2f}, S1={:.2f} (from prev day {} H={:.2f}, L={:.2f}, C={:.2f})",
                STRATEGY_ID,
                today,
                pivotPoint,
                r1Level,
                s1Level,
                prevTradingDay,
                hPrev,
                lPrev,
                cPrev);
    }

    // Manual setter for backtesting / tests
    public void setManualPivots(double pivot, double r1, double s1) {
        this.pivotPoint = pivot;
        this.r1Level = r1;
        this.s1Level = s1;
        this.pivotCalculated = true;
    }

    public record OtmOiAnalysis(
            long sumOtmCallOi,
            long sumOtmPutOi,
            long oiDifferential,
            BigDecimal atmPremium,
            boolean valid) {}

    public OtmOiAnalysis calculate3OtmOi(BigDecimal atm, String optionType) {
        if (manualOtmCallOi != null && manualOtmPutOi != null) {
            long callOi = manualOtmCallOi;
            long putOi = manualOtmPutOi;
            long diff =
                    oiDirectional
                            ? ("PE".equalsIgnoreCase(optionType) ? putOi - callOi : callOi - putOi)
                            : Math.abs(putOi - callOi);
            this.lastOtmCallOi = callOi;
            this.lastOtmPutOi = putOi;
            this.lastOiDifference = putOi - callOi;
            return new OtmOiAnalysis(callOi, putOi, diff, null, true);
        }

        if (optionChainService == null) {
            if (!oiFilterEnabled) {
                return new OtmOiAnalysis(0L, 0L, 0L, null, true);
            }
            return new OtmOiAnalysis(0L, 0L, 0L, null, false);
        }

        try {
            OptionChainResponse chain = optionChainService.getNifty50OptionChain(atm, 4, true);
            if (chain == null || chain.strikes() == null || chain.strikes().isEmpty()) {
                return new OtmOiAnalysis(0L, 0L, 0L, null, false);
            }

            BigDecimal call1 = atm.add(BigDecimal.valueOf(50));
            BigDecimal call2 = atm.add(BigDecimal.valueOf(100));
            BigDecimal call3 = atm.add(BigDecimal.valueOf(150));

            BigDecimal put1 = atm.subtract(BigDecimal.valueOf(50));
            BigDecimal put2 = atm.subtract(BigDecimal.valueOf(100));
            BigDecimal put3 = atm.subtract(BigDecimal.valueOf(150));

            long callOi = 0L;
            long putOi = 0L;
            BigDecimal atmLtp = null;

            for (OptionStrike s : chain.strikes()) {
                BigDecimal sp = s.strikePrice();
                if (sp.compareTo(call1) == 0
                        || sp.compareTo(call2) == 0
                        || sp.compareTo(call3) == 0) {
                    if (s.call() != null) callOi += s.call().openInterest();
                }
                if (sp.compareTo(put1) == 0 || sp.compareTo(put2) == 0 || sp.compareTo(put3) == 0) {
                    if (s.put() != null) putOi += s.put().openInterest();
                }
                if (sp.compareTo(atm) == 0) {
                    OptionContract contract =
                            "PE".equalsIgnoreCase(optionType) ? s.put() : s.call();
                    if (contract != null
                            && contract.ltp() != null
                            && contract.ltp().compareTo(BigDecimal.ZERO) > 0) {
                        atmLtp = contract.ltp();
                    }
                }
            }

            this.lastOtmCallOi = callOi;
            this.lastOtmPutOi = putOi;
            this.lastOiDifference = putOi - callOi;

            long diff =
                    oiDirectional
                            ? ("PE".equalsIgnoreCase(optionType) ? putOi - callOi : callOi - putOi)
                            : Math.abs(putOi - callOi);

            return new OtmOiAnalysis(callOi, putOi, diff, atmLtp, true);
        } catch (Exception e) {
            log.warn(
                    "[{}] Error calculating 3-OTM OI for ATM {}: {}",
                    STRATEGY_ID,
                    atm,
                    e.getMessage());
            return new OtmOiAnalysis(0L, 0L, 0L, null, false);
        }
    }

    // Manual OI setter for backtesting / testing
    public void setManualOtmOi(long callOi, long putOi) {
        this.manualOtmCallOi = callOi;
        this.manualOtmPutOi = putOi;
        this.lastOtmCallOi = callOi;
        this.lastOtmPutOi = putOi;
        this.lastOiDifference = putOi - callOi;
    }

    public void clearManualOtmOi() {
        this.manualOtmCallOi = null;
        this.manualOtmPutOi = null;
    }

    // Getters and Setters
    public double getPivotPoint() {
        return pivotPoint;
    }

    public double getR1Level() {
        return r1Level;
    }

    public double getS1Level() {
        return s1Level;
    }

    public boolean isInPosition() {
        return inPosition;
    }

    public PositionType getPositionType() {
        return positionType;
    }

    public String getPositionSymbol() {
        return positionSymbol;
    }

    public int getDailyTradesCount() {
        return dailyTradesCount;
    }

    public BigDecimal getEntryUnderlyingPrice() {
        return entryUnderlyingPrice;
    }

    public BigDecimal getAtmStrike() {
        return atmStrike;
    }

    public BigDecimal getEntryPremium() {
        return entryPremium;
    }

    public boolean isTelegramAlertsEnabled() {
        return telegramAlertsEnabled;
    }

    public void setTelegramAlertsEnabled(boolean telegramAlertsEnabled) {
        this.telegramAlertsEnabled = telegramAlertsEnabled;
    }

    public boolean isOiFilterEnabled() {
        return oiFilterEnabled;
    }

    public void setOiFilterEnabled(boolean oiFilterEnabled) {
        this.oiFilterEnabled = oiFilterEnabled;
    }

    public long getOiDiffThreshold() {
        return oiDiffThreshold;
    }

    public void setOiDiffThreshold(long oiDiffThreshold) {
        this.oiDiffThreshold = oiDiffThreshold;
    }

    public boolean isOiDirectional() {
        return oiDirectional;
    }

    public void setOiDirectional(boolean oiDirectional) {
        this.oiDirectional = oiDirectional;
    }

    public long getLastOtmCallOi() {
        return lastOtmCallOi;
    }

    public long getLastOtmPutOi() {
        return lastOtmPutOi;
    }

    public long getLastOiDifference() {
        return lastOiDifference;
    }

    public LocalTime getEntryStartTime() {
        return entryStartTime;
    }

    public void setEntryStartTime(LocalTime entryStartTime) {
        this.entryStartTime = entryStartTime;
    }

    public boolean isStopLossEnabled() {
        return stopLossEnabled;
    }

    public void setStopLossEnabled(boolean stopLossEnabled) {
        this.stopLossEnabled = stopLossEnabled;
    }

    public double getStopLossPercent() {
        return stopLossPercent;
    }

    public void setStopLossPercent(double stopLossPercent) {
        this.stopLossPercent = stopLossPercent;
    }

    public boolean isTargetProfitEnabled() {
        return targetProfitEnabled;
    }

    public void setTargetProfitEnabled(boolean targetProfitEnabled) {
        this.targetProfitEnabled = targetProfitEnabled;
    }

    public double getTargetProfitPercent() {
        return targetProfitPercent;
    }

    public void setTargetProfitPercent(double targetProfitPercent) {
        this.targetProfitPercent = targetProfitPercent;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
    }

    public boolean isBuyHedge() {
        return buyHedge;
    }

    public void setBuyHedge(boolean buyHedge) {
        this.buyHedge = buyHedge;
    }

    public int getHedgeDistance() {
        return hedgeDistance;
    }

    public void setHedgeDistance(int hedgeDistance) {
        this.hedgeDistance = hedgeDistance;
    }

    public BigDecimal getHedgeStrike() {
        return hedgeStrike;
    }

    public BigDecimal getHedgePremium() {
        return hedgePremium;
    }

    public String getHedgeSymbol() {
        return hedgeSymbol;
    }
}

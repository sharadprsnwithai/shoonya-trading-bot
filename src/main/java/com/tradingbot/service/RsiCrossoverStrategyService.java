package com.tradingbot.service;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.model.strategy.RsiCrossoverPosition;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CandleResamplingUtil;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Automated Intraday NIFTY 5m vs 15m RSI(14) Crossover Strategy.
 *
 * <p>Modes Supported: 1. OPTION_SELLING (Default / Recommended): - 5m RSI(14) crosses above 15m
 * RSI(14) -> SELL ATM PE (Bullish credit). - 5m RSI(14) crosses below 15m RSI(14) -> SELL ATM CE
 * (Bearish credit). 2. OPTION_BUYING: - 5m RSI(14) crosses above 15m RSI(14) -> BUY ATM CE. - 5m
 * RSI(14) crosses below 15m RSI(14) -> BUY ATM PE.
 *
 * <p>Rules: 1. Monitored on NIFTY 50 index (NSE:10576). 2. Active evaluation starts at 09:45:10 IST
 * and runs every 5 minutes until 15:00:10 IST. 3. Trend strength verified using 15m ADX(14) >=
 * threshold (default 20.0). 4. Strict 1 trade per day limit. 5. Hard Stop-Loss and Target Profit
 * protection on option premium. 6. Exit on reverse crossover or mandatory 15:05:10 IST EOD
 * square-off.
 */
@Service
public class RsiCrossoverStrategyService {

    private static final Logger log = LoggerFactory.getLogger(RsiCrossoverStrategyService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String NIFTY_SYMBOL = "NIFTY 50";
    public static final String NIFTY_TOKEN = "10576";
    public static final String NIFTY_EXCHANGE = "NSE";

    public static final LocalTime TIME_SESSION_START = LocalTime.of(9, 15);
    public static final LocalTime TIME_STRATEGY_START = LocalTime.of(9, 45);
    public static final LocalTime TIME_STRATEGY_END = LocalTime.of(15, 0);
    public static final LocalTime TIME_SQUARE_OFF = LocalTime.of(15, 5);

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final ShoonyaOptionChainService optionChainService;
    private final ShoonyaOrderService orderService;
    private final ShoonyaConfig config;

    private Clock clock = Clock.system(IST);

    @Value("${trading-bot.strategy.rsi-crossover.enabled:true}")
    private boolean enabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.mode:OPTION_SELLING}")
    private String mode = "OPTION_SELLING";

    @Value("${trading-bot.strategy.rsi-crossover.auto-execute:false}")
    private boolean autoExecute = false;

    @Value("${trading-bot.strategy.rsi-crossover.lots:1}")
    private int lots = 1;

    @Value("${trading-bot.strategy.rsi-crossover.lot-size:65}")
    private int lotSize = 65;

    @Value("${trading-bot.strategy.rsi-crossover.rsi-period:14}")
    private int rsiPeriod = 14;

    @Value("${trading-bot.strategy.rsi-crossover.adx-filter-enabled:true}")
    private boolean adxFilterEnabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.adx-threshold:22.0}")
    private double adxThreshold = 22.0;

    @Value("${trading-bot.strategy.rsi-crossover.vwap-filter-enabled:true}")
    private boolean vwapFilterEnabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.vwap-max-distance:35.0}")
    private double vwapMaxDistance = 35.0;

    @Value("${trading-bot.strategy.rsi-crossover.supertrend-filter-enabled:true}")
    private boolean supertrendFilterEnabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.supertrend-period:10}")
    private int supertrendPeriod = 10;

    @Value("${trading-bot.strategy.rsi-crossover.supertrend-multiplier:2.0}")
    private double supertrendMultiplier = 2.0;

    @Value("${trading-bot.strategy.rsi-crossover.di-filter-enabled:true}")
    private boolean diFilterEnabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.stop-loss-percent:2.0}")
    private double stopLossPercent = 2.0;

    @Value("${trading-bot.strategy.rsi-crossover.trailing-sl-enabled:true}")
    private boolean trailingSlEnabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.trail-step-1-trigger:12.0}")
    private double trailStep1Trigger = 12.0;

    @Value("${trading-bot.strategy.rsi-crossover.trail-step-1-lock:2.0}")
    private double trailStep1Lock = 2.0;

    @Value("${trading-bot.strategy.rsi-crossover.trail-step-2-trigger:25.0}")
    private double trailStep2Trigger = 25.0;

    @Value("${trading-bot.strategy.rsi-crossover.trail-step-2-lock:15.0}")
    private double trailStep2Lock = 15.0;

    @Value("${trading-bot.strategy.rsi-crossover.target-profit-percent:50.0}")
    private double targetProfitPercent = 50.0;

    @Value("${trading-bot.strategy.rsi-crossover.hedge-enabled:true}")
    private boolean hedgeEnabled = true;

    @Value("${trading-bot.strategy.rsi-crossover.hedge-otm-percent:2.0}")
    private double hedgeOtmPercent = 2.0;

    @Value("${trading-bot.strategy.rsi-crossover.max-trades-per-day:1}")
    private int maxTradesPerDay = 1;

    @Value("${trading-bot.strategy.rsi-crossover.telegram-alerts:true}")
    private boolean telegramAlerts = true;

    private final AtomicInteger tradesExecutedToday = new AtomicInteger(0);
    private final AtomicReference<RsiCrossoverPosition> openPosition = new AtomicReference<>(null);
    private final List<RsiCrossoverPosition> tradeHistory = new CopyOnWriteArrayList<>();
    private final AtomicInteger tradeCounter = new AtomicInteger(1);

    // Latest evaluated indicator values for monitoring
    private volatile double latestRsi5m = Double.NaN;
    private volatile double latestRsi15m = Double.NaN;
    private volatile double prevRsi5m = Double.NaN;
    private volatile double prevRsi15m = Double.NaN;
    private volatile double latestAdx15m = Double.NaN;
    private volatile double latestPlusDi15m = Double.NaN;
    private volatile double latestMinusDi15m = Double.NaN;
    private volatile double latestVwap = Double.NaN;
    private volatile double latestSupertrend = Double.NaN;
    private volatile boolean latestSupertrendBullish = false;
    private volatile double latestNiftyLtp = Double.NaN;

    @Autowired
    public RsiCrossoverStrategyService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaOptionChainService optionChainService,
            ShoonyaOrderService orderService,
            ShoonyaConfig config) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        this.optionChainService = optionChainService;
        this.orderService = orderService;
        this.config = config;
    }

    /** Executes one strategy evaluation cycle. */
    public synchronized void runCycle() {
        if (!enabled) {
            log.debug("[RSI-STRATEGY] Strategy is disabled in configuration.");
            return;
        }

        LocalTime nowTime = LocalTime.now(clock);

        if (nowTime.isBefore(TIME_STRATEGY_START)) {
            log.debug(
                    "[RSI-STRATEGY] Current time {} is before strategy start window (09:45 IST).",
                    nowTime);
            return;
        }

        if (nowTime.isAfter(TIME_SQUARE_OFF)) {
            log.debug(
                    "[RSI-STRATEGY] Current time {} is past square-off time (15:05 IST).", nowTime);
            return;
        }

        log.info(
                "[RSI-STRATEGY] Running 5-min RSI Crossover cycle [{}] at {} IST...",
                mode,
                nowTime);

        // Fetch 5-day 5-min historical candles (guarantees >= 350 bars for warm RSI)
        List<Candle> fiveMinCandles =
                marketDataService.fetchHistoricalCandles(
                        NIFTY_EXCHANGE, NIFTY_TOKEN, NIFTY_SYMBOL, "5", 5);
        if (fiveMinCandles == null || fiveMinCandles.size() < (rsiPeriod + 10)) {
            log.warn(
                    "[RSI-STRATEGY] Insufficient 5m candles retrieved: {}",
                    (fiveMinCandles != null ? fiveMinCandles.size() : 0));
            return;
        }

        List<Candle> fifteenMinCandles = CandleResamplingUtil.resample5MinTo15Min(fiveMinCandles);
        if (fifteenMinCandles == null || fifteenMinCandles.size() < (rsiPeriod + 5)) {
            log.warn(
                    "[RSI-STRATEGY] Insufficient 15m resampled candles: {}",
                    (fifteenMinCandles != null ? fifteenMinCandles.size() : 0));
            return;
        }

        // Calculate RSI(14) series
        double[] close5m =
                fiveMinCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] rsi5mSeries = taService.calculateRsiSeries(close5m, rsiPeriod);

        double[] close15m =
                fifteenMinCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] high15m =
                fifteenMinCandles.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
        double[] low15m =
                fifteenMinCandles.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
        double[] rsi15mSeries = taService.calculateRsiSeries(close15m, rsiPeriod);
        double[] adx15mSeries = taService.calculateAdxSeries(high15m, low15m, close15m, rsiPeriod);
        double[] plusDi15mSeries =
                taService.calculatePlusDiSeries(high15m, low15m, close15m, rsiPeriod);
        double[] minusDi15mSeries =
                taService.calculateMinusDiSeries(high15m, low15m, close15m, rsiPeriod);
        double[] vwapSeries = taService.calculateVwapSeries(fiveMinCandles);
        SuperTrendResult[] st15mSeries =
                taService.calculateSuperTrendSeries(
                        high15m, low15m, close15m, supertrendPeriod, supertrendMultiplier);

        int len5 = rsi5mSeries.length;
        int len15 = rsi15mSeries.length;
        if (len5 < 2 || len15 < 2) {
            log.warn("[RSI-STRATEGY] RSI series length too short.");
            return;
        }

        double rsi5Curr = rsi5mSeries[len5 - 1];
        double rsi5Prev = rsi5mSeries[len5 - 2];
        double rsi15Curr = rsi15mSeries[len15 - 1];
        double rsi15Prev = rsi15mSeries[len15 - 2];
        double adx15mCurr =
                (adx15mSeries != null && adx15mSeries.length > 0)
                        ? adx15mSeries[adx15mSeries.length - 1]
                        : Double.NaN;
        double plusDi15mCurr =
                (plusDi15mSeries != null && plusDi15mSeries.length > 0)
                        ? plusDi15mSeries[plusDi15mSeries.length - 1]
                        : Double.NaN;
        double minusDi15mCurr =
                (minusDi15mSeries != null && minusDi15mSeries.length > 0)
                        ? minusDi15mSeries[minusDi15mSeries.length - 1]
                        : Double.NaN;

        Candle latest5mCandle = fiveMinCandles.get(fiveMinCandles.size() - 1);
        double spotPrice = latest5mCandle.close().doubleValue();

        double vwapCurr =
                (vwapSeries != null && vwapSeries.length > 0)
                        ? vwapSeries[vwapSeries.length - 1]
                        : spotPrice;
        SuperTrendResult st15mCurr =
                (st15mSeries != null && st15mSeries.length > 0)
                        ? st15mSeries[st15mSeries.length - 1]
                        : null;
        boolean isStBullish = st15mCurr != null && st15mCurr.isBullish();
        double stVal = st15mCurr != null ? st15mCurr.value() : Double.NaN;

        if (Double.isNaN(rsi5Curr)
                || Double.isNaN(rsi5Prev)
                || Double.isNaN(rsi15Curr)
                || Double.isNaN(rsi15Prev)) {
            log.warn(
                    "[RSI-STRATEGY] One or more RSI values evaluated to NaN: 5m=[{}, {}], 15m=[{}, {}]",
                    rsi5Prev,
                    rsi5Curr,
                    rsi15Prev,
                    rsi15Curr);
            return;
        }

        this.latestRsi5m = rsi5Curr;
        this.prevRsi5m = rsi5Prev;
        this.latestRsi15m = rsi15Curr;
        this.prevRsi15m = rsi15Prev;
        this.latestAdx15m = adx15mCurr;
        this.latestPlusDi15m = plusDi15mCurr;
        this.latestMinusDi15m = minusDi15mCurr;
        this.latestVwap = vwapCurr;
        this.latestSupertrend = stVal;
        this.latestSupertrendBullish = isStBullish;
        this.latestNiftyLtp = spotPrice;

        log.info(
                "[RSI-STRATEGY] [{}] NIFTY: ₹{} | VWAP: ₹{:.1f} | ST(15m): ₹{:.1f} ({}) | 5m RSI: {:.2f} | 15m RSI: {:.2f} | 15m ADX: {:.2f} (+DI: {:.1f}, -DI: {:.1f})",
                mode,
                spotPrice,
                vwapCurr,
                stVal,
                (isStBullish ? "BULL" : "BEAR"),
                rsi5Curr,
                rsi15Curr,
                adx15mCurr,
                plusDi15mCurr,
                minusDi15mCurr);

        // 1. Manage Active Open Position (Check SL, Target, or Crossover/ST Reversal)
        RsiCrossoverPosition current = openPosition.get();
        if (current != null && !current.isClosed()) {
            evaluatePositionExit(
                    current, rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr, spotPrice, isStBullish);
            return;
        }

        // 2. If no position is open, check Entry (Max trades per day limit)
        if (tradesExecutedToday.get() >= maxTradesPerDay) {
            log.debug(
                    "[RSI-STRATEGY] Max {} trades per day limit reached for today ({}/{}). Skipping new entries.",
                    maxTradesPerDay,
                    tradesExecutedToday.get(),
                    maxTradesPerDay);
            return;
        }

        evaluateEntry(
                rsi5Prev,
                rsi5Curr,
                rsi15Prev,
                rsi15Curr,
                adx15mCurr,
                plusDi15mCurr,
                minusDi15mCurr,
                spotPrice,
                vwapCurr,
                isStBullish);
    }

    /** Compatibility overload for evaluateEntry. */
    public void evaluateEntry(
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            double adx15mCurr,
            double spotPrice) {
        evaluateEntry(
                rsi5Prev,
                rsi5Curr,
                rsi15Prev,
                rsi15Curr,
                adx15mCurr,
                Double.NaN,
                Double.NaN,
                spotPrice,
                Double.NaN,
                rsi5Curr > rsi15Curr);
    }

    /**
     * Evaluates crossover entry condition with comprehensive VWAP, SuperTrend, ADX, and +DI/-DI
     * filters.
     */
    public void evaluateEntry(
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            double adx15mCurr,
            double plusDi15mCurr,
            double minusDi15mCurr,
            double spotPrice,
            double vwap,
            boolean isStBullish) {
        // Bullish Crossover: 5m RSI crosses above 15m RSI
        boolean bullishCrossover = (rsi5Prev <= rsi15Prev) && (rsi5Curr > rsi15Curr);

        // Bearish Crossover: 5m RSI crosses below 15m RSI
        boolean bearishCrossover = (rsi5Prev >= rsi15Prev) && (rsi5Curr < rsi15Curr);

        if (!bullishCrossover && !bearishCrossover) {
            log.debug(
                    "[RSI-STRATEGY] No crossover detected. (5m: {:.2f}, 15m: {:.2f})",
                    rsi5Curr,
                    rsi15Curr);
            return;
        }

        // 1. ADX Trend Strength Filter
        if (adxFilterEnabled && !Double.isNaN(adx15mCurr) && adx15mCurr < adxThreshold) {
            log.info(
                    "[RSI-STRATEGY] ⚠️ Crossover detected but 15m ADX ({:.2f}) < threshold ({:.2f}). Skipping low-momentum entry.",
                    adx15mCurr,
                    adxThreshold);
            return;
        }

        // 2. Intraday VWAP Directional Filter & No-Chasing Distance Gate
        if (vwapFilterEnabled && !Double.isNaN(vwap)) {
            if (vwapMaxDistance > 0.0) {
                double dist = Math.abs(spotPrice - vwap);
                if (dist > vwapMaxDistance) {
                    log.info(
                            "[RSI-STRATEGY] ⚠️ Crossover rejected: Spot (₹{}) is too far from VWAP (₹{:.1f}, dist={:.1f} > max={:.1f}). Skipping chasing price.",
                            spotPrice,
                            vwap,
                            dist,
                            vwapMaxDistance);
                    return;
                }
            }

            if (bullishCrossover && spotPrice < vwap) {
                log.info(
                        "[RSI-STRATEGY] ⚠️ Bullish Crossover rejected: Spot (₹{}) < Intraday VWAP (₹{}). Market is in bearish regime.",
                        spotPrice,
                        vwap);
                return;
            }
            if (bearishCrossover && spotPrice > vwap) {
                log.info(
                        "[RSI-STRATEGY] ⚠️ Bearish Crossover rejected: Spot (₹{}) > Intraday VWAP (₹{}). Market is in bullish regime.",
                        spotPrice,
                        vwap);
                return;
            }
        }

        // 3. 15m Supertrend Directional Filter
        if (supertrendFilterEnabled) {
            if (bullishCrossover && !isStBullish) {
                log.info(
                        "[RSI-STRATEGY] ⚠️ Bullish Crossover rejected: 15m Supertrend is Bearish (Red).");
                return;
            }
            if (bearishCrossover && isStBullish) {
                log.info(
                        "[RSI-STRATEGY] ⚠️ Bearish Crossover rejected: 15m Supertrend is Bullish (Green).");
                return;
            }
        }

        // 4. Directional Movement Indicator Filter (+DI / -DI)
        if (diFilterEnabled && !Double.isNaN(plusDi15mCurr) && !Double.isNaN(minusDi15mCurr)) {
            if (bullishCrossover && plusDi15mCurr < minusDi15mCurr) {
                log.info(
                        "[RSI-STRATEGY] ⚠️ Bullish Crossover rejected: +DI ({:.2f}) < -DI ({:.2f}). Dominant sellers present.",
                        plusDi15mCurr,
                        minusDi15mCurr);
                return;
            }
            if (bearishCrossover && minusDi15mCurr < plusDi15mCurr) {
                log.info(
                        "[RSI-STRATEGY] ⚠️ Bearish Crossover rejected: -DI ({:.2f}) < +DI ({:.2f}). Dominant buyers present.",
                        minusDi15mCurr,
                        plusDi15mCurr);
                return;
            }
        }

        boolean isOptionSelling = "OPTION_SELLING".equalsIgnoreCase(mode);

        if (bullishCrossover) {
            if (isOptionSelling) {
                log.info(
                        "[RSI-STRATEGY] 🟢 BULLISH CONFIRMED: 5m RSI ({:.2f}) > 15m RSI ({:.2f}) | Spot (₹{}) >= VWAP (₹{:.1f}) | ST: BULL | ADX: {:.1f}. Executing ATM PE SELL...",
                        rsi5Curr,
                        rsi15Curr,
                        spotPrice,
                        vwap,
                        adx15mCurr);
                executeTrade("SELL", "PE", spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
            } else {
                log.info(
                        "[RSI-STRATEGY] 🟢 BULLISH CONFIRMED: 5m RSI ({:.2f}) > 15m RSI ({:.2f}) | Spot (₹{}) >= VWAP (₹{:.1f}) | ST: BULL | ADX: {:.1f}. Executing ATM CE BUY...",
                        rsi5Curr,
                        rsi15Curr,
                        spotPrice,
                        vwap,
                        adx15mCurr);
                executeTrade("BUY", "CE", spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
            }
        } else {
            if (isOptionSelling) {
                log.info(
                        "[RSI-STRATEGY] 🔴 BEARISH CONFIRMED: 5m RSI ({:.2f}) < 15m RSI ({:.2f}) | Spot (₹{}) <= VWAP (₹{:.1f}) | ST: BEAR | ADX: {:.1f}. Executing ATM CE SELL...",
                        rsi5Curr,
                        rsi15Curr,
                        spotPrice,
                        vwap,
                        adx15mCurr);
                executeTrade("SELL", "CE", spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
            } else {
                log.info(
                        "[RSI-STRATEGY] 🔴 BEARISH CONFIRMED: 5m RSI ({:.2f}) < 15m RSI ({:.2f}) | Spot (₹{}) <= VWAP (₹{:.1f}) | ST: BEAR | ADX: {:.1f}. Executing ATM PE BUY...",
                        rsi5Curr,
                        rsi15Curr,
                        spotPrice,
                        vwap,
                        adx15mCurr);
                executeTrade("BUY", "PE", spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
            }
        }
    }

    /**
     * Evaluates exit condition for an active open position (Stop Loss, Target, or RSI Reversal).
     */
    public void evaluatePositionExit(
            RsiCrossoverPosition current,
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            double currentSpotPrice) {
        evaluatePositionExit(
                current,
                rsi5Prev,
                rsi5Curr,
                rsi15Prev,
                rsi15Curr,
                currentSpotPrice,
                latestSupertrendBullish);
    }

    /**
     * Evaluates exit condition for an active open position with Supertrend awareness and Stepped
     * Trailing Stop.
     */
    public void evaluatePositionExit(
            RsiCrossoverPosition current,
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            double currentSpotPrice,
            boolean isStBullish) {
        BigDecimal currentPremium =
                fetchOptionPremium(current.getStrike(), current.getOptionType());
        if (currentPremium == null || currentPremium.compareTo(BigDecimal.ZERO) <= 0) {
            // Estimate via delta 0.50 if quote fetch fails
            double spotDiff =
                    "CE".equalsIgnoreCase(current.getOptionType())
                            ? currentSpotPrice - current.getStrike().doubleValue()
                            : current.getStrike().doubleValue() - currentSpotPrice;
            currentPremium = current.getEntryPrice().add(BigDecimal.valueOf(spotDiff * 0.50));
            if (currentPremium.compareTo(BigDecimal.ZERO) < 0) {
                currentPremium = BigDecimal.valueOf(0.05);
            }
        }

        BigDecimal entryPrice = current.getEntryPrice();
        boolean isShortPosition = "SELL".equalsIgnoreCase(current.getAction());

        if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
            if (isShortPosition && current.isHedgeEnabled()) {
                // Hedged Credit Spread Evaluation
                BigDecimal hedgePremium =
                        fetchOptionPremium(current.getHedgeStrike(), current.getOptionType());
                if (hedgePremium == null || hedgePremium.compareTo(BigDecimal.ZERO) <= 0) {
                    hedgePremium = current.getHedgeEntryPrice();
                }

                double mainPoints = entryPrice.doubleValue() - currentPremium.doubleValue();
                double hedgePoints =
                        hedgePremium.doubleValue() - current.getHedgeEntryPrice().doubleValue();
                double netPoints = mainPoints + hedgePoints;
                double netCredit = current.getNetCredit().doubleValue();

                current.updatePeakProfitPerQty(BigDecimal.valueOf(netPoints));
                double peakPts = current.getPeakProfitPerQty().doubleValue();

                double baseSlPoints = -(netCredit * (stopLossPercent / 100.0));
                double effectiveSlPoints = baseSlPoints;

                if (trailingSlEnabled) {
                    if (peakPts >= trailStep2Trigger) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, trailStep2Lock);
                    } else if (peakPts >= trailStep1Trigger) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, trailStep1Lock);
                    }
                }

                if (stopLossPercent > 0.0 || trailingSlEnabled) {
                    if (netPoints <= effectiveSlPoints) {
                        String exitReason =
                                effectiveSlPoints > baseSlPoints ? "TRAIL_SL_LOCK" : "HARD_SL_HIT";
                        log.info(
                                "[RSI-STRATEGY] 🛑 HEDGED SPREAD {} for {}: Net Points {:.2f} <= SL {:.2f} (Peak {:.2f} pts)",
                                exitReason,
                                current.getSymbol(),
                                netPoints,
                                effectiveSlPoints,
                                peakPts);
                        executeExit(exitReason);
                        return;
                    }
                }

                if (targetProfitPercent > 0.0) {
                    double tpThresholdPoints = netCredit * (targetProfitPercent / 100.0);
                    if (netPoints >= tpThresholdPoints) {
                        log.info(
                                "[RSI-STRATEGY] 🎯 HEDGED SPREAD TARGET PROFIT HIT for {}: Net Points {:.2f} >= TP {:.2f} (+{}%)",
                                current.getSymbol(),
                                netPoints,
                                tpThresholdPoints,
                                targetProfitPercent);
                        executeExit("TARGET_PROFIT_HIT");
                        return;
                    }
                }
            } else if (isShortPosition) {
                // For Naked Option Selling
                double profitPoints = entryPrice.doubleValue() - currentPremium.doubleValue();
                current.updatePeakProfitPerQty(BigDecimal.valueOf(profitPoints));
                double peakPts = current.getPeakProfitPerQty().doubleValue();

                double baseSlPoints = -(entryPrice.doubleValue() * (stopLossPercent / 100.0));
                double effectiveSlPoints = baseSlPoints;

                if (trailingSlEnabled) {
                    if (peakPts >= trailStep2Trigger) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, trailStep2Lock);
                    } else if (peakPts >= trailStep1Trigger) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, trailStep1Lock);
                    }
                }

                if (stopLossPercent > 0.0 || trailingSlEnabled) {
                    if (profitPoints <= effectiveSlPoints) {
                        String exitReason =
                                effectiveSlPoints > baseSlPoints ? "TRAIL_SL_LOCK" : "HARD_SL_HIT";
                        log.info(
                                "[RSI-STRATEGY] 🛑 SHORT {} for {}: Profit Points {:.2f} <= SL {:.2f} (Peak {:.2f} pts)",
                                exitReason,
                                current.getSymbol(),
                                profitPoints,
                                effectiveSlPoints,
                                peakPts);
                        executeExit(exitReason);
                        return;
                    }
                }

                if (targetProfitPercent > 0.0) {
                    BigDecimal tpThreshold =
                            entryPrice.multiply(
                                    BigDecimal.valueOf(1.0 - (targetProfitPercent / 100.0)));
                    if (currentPremium.compareTo(tpThreshold) <= 0) {
                        log.info(
                                "[RSI-STRATEGY] 🎯 SHORT TARGET PROFIT HIT for {}: Current ₹{} <= TP ₹{} (-{}%)",
                                current.getSymbol(),
                                currentPremium,
                                tpThreshold,
                                targetProfitPercent);
                        executeExit("TARGET_PROFIT_HIT");
                        return;
                    }
                }
            } else {
                // For Option Buying
                double profitPoints = currentPremium.doubleValue() - entryPrice.doubleValue();
                current.updatePeakProfitPerQty(BigDecimal.valueOf(profitPoints));
                double peakPts = current.getPeakProfitPerQty().doubleValue();

                double baseSlPoints = -(entryPrice.doubleValue() * (stopLossPercent / 100.0));
                double effectiveSlPoints = baseSlPoints;

                if (trailingSlEnabled) {
                    if (peakPts >= trailStep2Trigger) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, trailStep2Lock);
                    } else if (peakPts >= trailStep1Trigger) {
                        effectiveSlPoints = Math.max(effectiveSlPoints, trailStep1Lock);
                    }
                }

                if (stopLossPercent > 0.0 || trailingSlEnabled) {
                    if (profitPoints <= effectiveSlPoints) {
                        String exitReason =
                                effectiveSlPoints > baseSlPoints ? "TRAIL_SL_LOCK" : "HARD_SL_HIT";
                        log.info(
                                "[RSI-STRATEGY] 🛑 LONG {} for {}: Profit Points {:.2f} <= SL {:.2f} (Peak {:.2f} pts)",
                                exitReason,
                                current.getSymbol(),
                                profitPoints,
                                effectiveSlPoints,
                                peakPts);
                        executeExit(exitReason);
                        return;
                    }
                }

                if (targetProfitPercent > 0.0) {
                    BigDecimal tpThreshold =
                            entryPrice.multiply(
                                    BigDecimal.valueOf(1.0 + (targetProfitPercent / 100.0)));
                    if (currentPremium.compareTo(tpThreshold) >= 0) {
                        log.info(
                                "[RSI-STRATEGY] 🎯 LONG TARGET PROFIT HIT for {}: Current ₹{} >= TP ₹{} (+{}%)",
                                current.getSymbol(),
                                currentPremium,
                                tpThreshold,
                                targetProfitPercent);
                        executeExit("TARGET_PROFIT_HIT");
                        return;
                    }
                }
            }
        }

        // Crossover / SuperTrend Reversal check
        evaluateExitOnReversal(current, rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr, isStBullish);
    }

    /** Evaluates reversal exit condition for an active open position. */
    public void evaluateExitOnReversal(
            RsiCrossoverPosition current,
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr) {
        evaluateExitOnReversal(
                current, rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr, latestSupertrendBullish);
    }

    /** Evaluates reversal exit condition with Supertrend support. */
    public void evaluateExitOnReversal(
            RsiCrossoverPosition current,
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            boolean isStBullish) {
        String action = current.getAction() != null ? current.getAction().toUpperCase() : "BUY";
        String optionType = current.getOptionType();

        boolean isBullishPosition =
                ("BUY".equals(action) && "CE".equalsIgnoreCase(optionType))
                        || ("SELL".equals(action) && "PE".equalsIgnoreCase(optionType));

        if (isBullishPosition) {
            // Holding Bullish trade (Buy CE or Sell PE)
            if (supertrendFilterEnabled && !isStBullish) {
                log.info(
                        "[RSI-STRATEGY] 🏁 Bullish Position Exit Triggered: 15m Supertrend flipped Bearish.");
                executeExit("ST_FLIP_BEARISH");
                return;
            }
            if (rsi5Curr < rsi15Curr) {
                log.info(
                        "[RSI-STRATEGY] 🏁 Bullish Position Exit Reversal Triggered: 5m RSI ({:.2f}) < 15m RSI ({:.2f})",
                        rsi5Curr,
                        rsi15Curr);
                executeExit("RSI_REVERSAL_BEARISH");
            }
        } else {
            // Holding Bearish trade (Buy PE or Sell CE)
            if (supertrendFilterEnabled && isStBullish) {
                log.info(
                        "[RSI-STRATEGY] 🏁 Bearish Position Exit Triggered: 15m Supertrend flipped Bullish.");
                executeExit("ST_FLIP_BULLISH");
                return;
            }
            if (rsi5Curr > rsi15Curr) {
                log.info(
                        "[RSI-STRATEGY] 🏁 Bearish Position Exit Reversal Triggered: 5m RSI ({:.2f}) > 15m RSI ({:.2f})",
                        rsi5Curr,
                        rsi15Curr);
                executeExit("RSI_REVERSAL_BULLISH");
            }
        }
    }

    /** Executes Option Trade Entry (Buy, Sell, or 2% OTM Hedged Credit Spread). */
    public synchronized void executeTrade(
            String action,
            String optionType,
            double spotPrice,
            double rsi5Curr,
            double rsi15Curr,
            double rsi5Prev,
            double rsi15Prev) {
        // Calculate ATM Strike
        BigDecimal atmStrike =
                StockFnoRegistry.calculateAtmStrike("NIFTY50", BigDecimal.valueOf(spotPrice));
        if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
            atmStrike = BigDecimal.valueOf(Math.round(spotPrice / 50.0) * 50);
        }

        int totalQuantity = lots * lotSize;
        String tradeId = "RSI_TRD_" + tradeCounter.getAndIncrement();

        // Resolve Option Symbol and Premium for ATM Leg
        String optionSymbol = resolveOptionSymbol(atmStrike, optionType);
        BigDecimal entryPremium = fetchOptionPremium(atmStrike, optionType);
        if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) <= 0) {
            entryPremium = BigDecimal.valueOf(150.0); // Safe simulated fallback
        }

        boolean applyHedge = hedgeEnabled && "SELL".equalsIgnoreCase(action);
        String hedgeSymbol = null;
        BigDecimal hedgeStrike = null;
        BigDecimal hedgeEntryPremium = BigDecimal.ZERO;

        if (applyHedge) {
            // Calculate 2% OTM Strike (PE lower, CE higher)
            double rawHedge =
                    "PE".equalsIgnoreCase(optionType)
                            ? spotPrice * (1.0 - hedgeOtmPercent / 100.0)
                            : spotPrice * (1.0 + hedgeOtmPercent / 100.0);
            hedgeStrike = BigDecimal.valueOf(Math.round(rawHedge / 50.0) * 50);
            if ("PE".equalsIgnoreCase(optionType)) {
                if (hedgeStrike.compareTo(atmStrike) >= 0) {
                    hedgeStrike = atmStrike.subtract(BigDecimal.valueOf(50));
                }
            } else {
                if (hedgeStrike.compareTo(atmStrike) <= 0) {
                    hedgeStrike = atmStrike.add(BigDecimal.valueOf(50));
                }
            }
            hedgeSymbol = resolveOptionSymbol(hedgeStrike, optionType);
            hedgeEntryPremium = fetchOptionPremium(hedgeStrike, optionType);
            if (hedgeEntryPremium == null || hedgeEntryPremium.compareTo(BigDecimal.ZERO) <= 0) {
                hedgeEntryPremium = BigDecimal.valueOf(12.0); // Safe simulated fallback
            }
        }

        RsiCrossoverPosition position =
                new RsiCrossoverPosition(
                        tradeId,
                        optionSymbol,
                        action,
                        optionType,
                        atmStrike,
                        entryPremium,
                        totalQuantity,
                        Instant.now(clock),
                        applyHedge,
                        hedgeSymbol,
                        hedgeStrike,
                        hedgeEntryPremium,
                        applyHedge ? totalQuantity : 0);

        // If Live Auto-Execution is enabled
        if (autoExecute && config.isEnabled()) {
            if (applyHedge) {
                // Leg 1: BUY Hedge first (margin benefit)
                try {
                    OrderRequest hedgeReq =
                            OrderRequest.market(
                                    hedgeSymbol,
                                    "NFO",
                                    TransactionType.BUY,
                                    totalQuantity,
                                    tradeId + "_HEDGE");
                    orderService.placeOrder(hedgeReq);
                    log.info(
                            "[RSI-STRATEGY] [LIVE] Placed BUY Hedge Order for {} Qty {}",
                            totalQuantity,
                            hedgeSymbol);

                    // Leg 2: SELL ATM Leg
                    try {
                        OrderRequest mainReq =
                                OrderRequest.market(
                                        optionSymbol,
                                        "NFO",
                                        TransactionType.SELL,
                                        totalQuantity,
                                        tradeId);
                        orderService.placeOrder(mainReq);
                        log.info(
                                "[RSI-STRATEGY] [LIVE] Placed SELL ATM Order for {} Qty {}",
                                totalQuantity,
                                optionSymbol);
                    } catch (Exception e) {
                        log.error(
                                "[RSI-STRATEGY] [LIVE] Failed to place ATM Sell order after hedge fill. Rolling back hedge leg: {}",
                                e.getMessage(),
                                e);
                        orderService.placeOrder(
                                OrderRequest.market(
                                        hedgeSymbol,
                                        "NFO",
                                        TransactionType.SELL,
                                        totalQuantity,
                                        tradeId + "_ROLLBACK"));
                        return;
                    }
                } catch (Exception e) {
                    log.error(
                            "[RSI-STRATEGY] [LIVE] Hedge buy placement failed. Aborting trade entry: {}",
                            e.getMessage(),
                            e);
                    return;
                }
            } else {
                try {
                    TransactionType txType =
                            "SELL".equalsIgnoreCase(action)
                                    ? TransactionType.SELL
                                    : TransactionType.BUY;
                    OrderRequest orderReq =
                            OrderRequest.market(
                                    optionSymbol, "NFO", txType, totalQuantity, tradeId);
                    orderService.placeOrder(orderReq);
                    log.info(
                            "[RSI-STRATEGY] [LIVE] Placed {} Order for {} Qty {}",
                            action,
                            totalQuantity,
                            optionSymbol);
                } catch (Exception e) {
                    log.error("[RSI-STRATEGY] Live order placement failed: {}", e.getMessage(), e);
                }
            }
        }

        this.openPosition.set(position);
        this.tradesExecutedToday.incrementAndGet();

        log.info(
                "[RSI-STRATEGY] {} FILLED: {} | {} Strike ₹{} @ ₹{} (Hedge: {} Strike ₹{} @ ₹{}) | Qty: {}",
                applyHedge ? "HEDGED SPREAD" : "OPTION " + action,
                tradeId,
                optionSymbol,
                atmStrike,
                entryPremium,
                applyHedge ? hedgeSymbol : "NONE",
                applyHedge ? hedgeStrike : BigDecimal.ZERO,
                applyHedge ? hedgeEntryPremium : BigDecimal.ZERO,
                totalQuantity);

        if (telegramAlerts) {
            telegramService.sendRsiCrossoverEntryAlert(
                    position,
                    rsi5Curr,
                    rsi15Curr,
                    rsi5Prev,
                    rsi15Prev,
                    latestVwap,
                    latestSupertrend,
                    latestAdx15m);
        }
    }

    /** Compatibility helper for Option Buy entry. */
    public synchronized void executeOptionBuy(
            String optionType,
            double spotPrice,
            double rsi5Curr,
            double rsi15Curr,
            double rsi5Prev,
            double rsi15Prev) {
        executeTrade("BUY", optionType, spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
    }

    /** Closes open position with specified reason. */
    public synchronized void executeExit(String reason) {
        RsiCrossoverPosition current = openPosition.get();
        if (current == null || current.isClosed()) {
            return;
        }

        BigDecimal exitPremium = fetchOptionPremium(current.getStrike(), current.getOptionType());
        if (exitPremium == null || exitPremium.compareTo(BigDecimal.ZERO) <= 0) {
            exitPremium = current.getEntryPrice(); // Fallback to breakeven if quote unavailable
        }

        BigDecimal hedgeExitPremium = BigDecimal.ZERO;
        if (current.isHedgeEnabled()) {
            hedgeExitPremium =
                    fetchOptionPremium(current.getHedgeStrike(), current.getOptionType());
            if (hedgeExitPremium == null || hedgeExitPremium.compareTo(BigDecimal.ZERO) <= 0) {
                hedgeExitPremium = current.getHedgeEntryPrice();
            }
        }

        // If Live Auto-Execution is enabled, place opposing order(s) to close
        if (autoExecute && config.isEnabled()) {
            try {
                // Leg 1: Close main leg
                TransactionType exitTxType =
                        "SELL".equalsIgnoreCase(current.getAction())
                                ? TransactionType.BUY
                                : TransactionType.SELL;
                OrderRequest exitReq =
                        OrderRequest.market(
                                current.getSymbol(),
                                "NFO",
                                exitTxType,
                                current.getQuantity(),
                                current.getTradeId() + "_EXIT");
                orderService.placeOrder(exitReq);
                log.info(
                        "[RSI-STRATEGY] [LIVE] Placed {} Exit Order for {} Qty {}",
                        exitTxType,
                        current.getQuantity(),
                        current.getSymbol());

                // Leg 2: Close hedge leg if applicable
                if (current.isHedgeEnabled() && current.getHedgeSymbol() != null) {
                    OrderRequest hedgeExitReq =
                            OrderRequest.market(
                                    current.getHedgeSymbol(),
                                    "NFO",
                                    TransactionType.SELL,
                                    current.getHedgeQuantity(),
                                    current.getTradeId() + "_HEDGE_EXIT");
                    orderService.placeOrder(hedgeExitReq);
                    log.info(
                            "[RSI-STRATEGY] [LIVE] Placed SELL Hedge Exit Order for {} Qty {}",
                            current.getHedgeQuantity(),
                            current.getHedgeSymbol());
                }
            } catch (Exception e) {
                log.error("[RSI-STRATEGY] Live exit order placement failed: {}", e.getMessage(), e);
            }
        }

        current.close(exitPremium, hedgeExitPremium, reason, Instant.now(clock));
        tradeHistory.add(current);
        openPosition.set(null);

        log.info(
                "[RSI-STRATEGY] POSITION EXITED: {} {} | Main Entry/Exit: ₹{}/₹{} | Hedge Entry/Exit: ₹{}/₹{} | Total P&L: ₹{} | Reason: {}",
                current.getAction(),
                current.getSymbol(),
                current.getEntryPrice(),
                exitPremium,
                current.getHedgeEntryPrice(),
                hedgeExitPremium,
                current.getTotalRealizedPnl(),
                reason);

        if (telegramAlerts) {
            telegramService.sendRsiCrossoverExitAlert(current, reason);
        }
    }

    /** Mandatory EOD Square-Off (called at 15:05:10 IST). */
    public synchronized void executeSquareOff(String reason) {
        RsiCrossoverPosition current = openPosition.get();
        if (current != null && !current.isClosed()) {
            log.info(
                    "[RSI-STRATEGY] 15:05 Mandatory Square-Off triggered for {}",
                    current.getSymbol());
            executeExit(reason);
        }
    }

    /** Resets the daily state for a new trading day (called at 09:15:00 IST). */
    public synchronized void resetDaily() {
        log.info(
                "[RSI-STRATEGY] Daily reset invoked: Clearing open positions and daily trade limit.");
        tradesExecutedToday.set(0);
        openPosition.set(null);
        tradeHistory.clear();
        tradeCounter.set(1);
        latestRsi5m = Double.NaN;
        latestRsi15m = Double.NaN;
        prevRsi5m = Double.NaN;
        prevRsi15m = Double.NaN;
        latestNiftyLtp = Double.NaN;
    }

    // --- Helper Methods ---

    private BigDecimal fetchOptionPremium(BigDecimal strike, String optionType) {
        try {
            OptionChainResponse chain = optionChainService.getNifty50OptionChain(strike, 3, true);
            if (chain != null && chain.strikes() != null) {
                for (OptionStrike os : chain.strikes()) {
                    if (os.strikePrice().compareTo(strike) == 0) {
                        OptionContract contract =
                                "CE".equalsIgnoreCase(optionType) ? os.call() : os.put();
                        if (contract != null
                                && contract.ltp() != null
                                && contract.ltp().compareTo(BigDecimal.ZERO) > 0) {
                            return contract.ltp();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[RSI-STRATEGY] Option chain premium fetch error: {}", e.getMessage());
        }
        return null;
    }

    private String resolveOptionSymbol(BigDecimal strike, String optionType) {
        try {
            OptionChainResponse chain = optionChainService.getNifty50OptionChain(strike, 3, true);
            if (chain != null && chain.strikes() != null) {
                for (OptionStrike os : chain.strikes()) {
                    if (os.strikePrice().compareTo(strike) == 0) {
                        OptionContract contract =
                                "CE".equalsIgnoreCase(optionType) ? os.call() : os.put();
                        if (contract != null
                                && contract.symbol() != null
                                && !contract.symbol().isBlank()) {
                            return contract.symbol();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[RSI-STRATEGY] Symbol resolution error: {}", e.getMessage());
        }
        return formatNiftyOptionSymbol(strike, optionType);
    }

    private String formatNiftyOptionSymbol(BigDecimal strike, String optionType) {
        LocalDate today = LocalDate.now(clock);
        LocalDate expiry = StockFnoRegistry.calculateWeeklyTargetExpiry(today, 1);
        String year = String.valueOf(expiry.getYear()).substring(2);
        String month = expiry.getMonth().name().substring(0, 3).toUpperCase();
        int strikeInt = strike.intValue();
        // NSE index option format (both weekly & monthly expiry):
        // NIFTY{DD}{MMM}{YY}{STRIKE}{CE/PE} e.g. NIFTY18SEP2524850PE
        return String.format(
                "NIFTY%02d%s%s%d%s",
                expiry.getDayOfMonth(), month, year, strikeInt, optionType.toUpperCase());
    }

    // --- Getters and Setters for Testing & Configuration ---

    public boolean isTradeExecutedToday() {
        return tradesExecutedToday.get() >= maxTradesPerDay;
    }

    public void setTradeExecutedToday(boolean executed) {
        this.tradesExecutedToday.set(executed ? maxTradesPerDay : 0);
    }

    public int getTradesExecutedToday() {
        return tradesExecutedToday.get();
    }

    public void setTradesExecutedToday(int count) {
        this.tradesExecutedToday.set(count);
    }

    public boolean isHedgeEnabled() {
        return hedgeEnabled;
    }

    public void setHedgeEnabled(boolean hedgeEnabled) {
        this.hedgeEnabled = hedgeEnabled;
    }

    public double getHedgeOtmPercent() {
        return hedgeOtmPercent;
    }

    public void setHedgeOtmPercent(double hedgeOtmPercent) {
        this.hedgeOtmPercent = hedgeOtmPercent;
    }

    public int getMaxTradesPerDay() {
        return maxTradesPerDay;
    }

    public void setMaxTradesPerDay(int maxTradesPerDay) {
        this.maxTradesPerDay = maxTradesPerDay;
    }

    public RsiCrossoverPosition getOpenPosition() {
        return openPosition.get();
    }

    public List<RsiCrossoverPosition> getTradeHistory() {
        return tradeHistory;
    }

    public double getLatestRsi5m() {
        return latestRsi5m;
    }

    public double getLatestRsi15m() {
        return latestRsi15m;
    }

    public double getPrevRsi5m() {
        return prevRsi5m;
    }

    public double getPrevRsi15m() {
        return prevRsi15m;
    }

    public double getLatestAdx15m() {
        return latestAdx15m;
    }

    public double getLatestNiftyLtp() {
        return latestNiftyLtp;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoExecute() {
        return autoExecute;
    }

    public void setAutoExecute(boolean autoExecute) {
        this.autoExecute = autoExecute;
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

    public int getRsiPeriod() {
        return rsiPeriod;
    }

    public void setRsiPeriod(int rsiPeriod) {
        this.rsiPeriod = rsiPeriod;
    }

    public boolean isAdxFilterEnabled() {
        return adxFilterEnabled;
    }

    public void setAdxFilterEnabled(boolean adxFilterEnabled) {
        this.adxFilterEnabled = adxFilterEnabled;
    }

    public double getAdxThreshold() {
        return adxThreshold;
    }

    public void setAdxThreshold(double adxThreshold) {
        this.adxThreshold = adxThreshold;
    }

    public double getStopLossPercent() {
        return stopLossPercent;
    }

    public void setStopLossPercent(double stopLossPercent) {
        this.stopLossPercent = stopLossPercent;
    }

    public double getTargetProfitPercent() {
        return targetProfitPercent;
    }

    public void setTargetProfitPercent(double targetProfitPercent) {
        this.targetProfitPercent = targetProfitPercent;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public boolean isVwapFilterEnabled() {
        return vwapFilterEnabled;
    }

    public void setVwapFilterEnabled(boolean vwapFilterEnabled) {
        this.vwapFilterEnabled = vwapFilterEnabled;
    }

    public boolean isSupertrendFilterEnabled() {
        return supertrendFilterEnabled;
    }

    public void setSupertrendFilterEnabled(boolean supertrendFilterEnabled) {
        this.supertrendFilterEnabled = supertrendFilterEnabled;
    }

    public int getSupertrendPeriod() {
        return supertrendPeriod;
    }

    public void setSupertrendPeriod(int supertrendPeriod) {
        this.supertrendPeriod = supertrendPeriod;
    }

    public double getSupertrendMultiplier() {
        return supertrendMultiplier;
    }

    public void setSupertrendMultiplier(double supertrendMultiplier) {
        this.supertrendMultiplier = supertrendMultiplier;
    }

    public boolean isDiFilterEnabled() {
        return diFilterEnabled;
    }

    public void setDiFilterEnabled(boolean diFilterEnabled) {
        this.diFilterEnabled = diFilterEnabled;
    }

    public double getVwapMaxDistance() {
        return vwapMaxDistance;
    }

    public void setVwapMaxDistance(double vwapMaxDistance) {
        this.vwapMaxDistance = vwapMaxDistance;
    }

    public boolean isTrailingSlEnabled() {
        return trailingSlEnabled;
    }

    public void setTrailingSlEnabled(boolean trailingSlEnabled) {
        this.trailingSlEnabled = trailingSlEnabled;
    }

    public double getTrailStep1Trigger() {
        return trailStep1Trigger;
    }

    public void setTrailStep1Trigger(double trailStep1Trigger) {
        this.trailStep1Trigger = trailStep1Trigger;
    }

    public double getTrailStep1Lock() {
        return trailStep1Lock;
    }

    public void setTrailStep1Lock(double trailStep1Lock) {
        this.trailStep1Lock = trailStep1Lock;
    }

    public double getTrailStep2Trigger() {
        return trailStep2Trigger;
    }

    public void setTrailStep2Trigger(double trailStep2Trigger) {
        this.trailStep2Trigger = trailStep2Trigger;
    }

    public double getTrailStep2Lock() {
        return trailStep2Lock;
    }

    public void setTrailStep2Lock(double trailStep2Lock) {
        this.trailStep2Lock = trailStep2Lock;
    }

    public double getLatestVwap() {
        return latestVwap;
    }

    public double getLatestSupertrend() {
        return latestSupertrend;
    }

    public boolean isLatestSupertrendBullish() {
        return latestSupertrendBullish;
    }

    public double getLatestPlusDi15m() {
        return latestPlusDi15m;
    }

    public double getLatestMinusDi15m() {
        return latestMinusDi15m;
    }

    public Clock getClock() {
        return clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }
}

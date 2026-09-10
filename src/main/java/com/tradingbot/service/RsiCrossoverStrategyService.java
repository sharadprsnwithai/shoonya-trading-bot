package com.tradingbot.service;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Automated Intraday Option Buying Strategy: NIFTY 5m vs 15m RSI(14) Crossover.
 *
 * <p>Rules:
 * 1. Monitored on NIFTY 50 index (NSE:10576).
 * 2. Active evaluation starts at 09:45:10 IST and runs every 5 minutes until 15:00:10 IST.
 * 3. 5m RSI(14) crosses above 15m RSI(14) -> Buy ATM CE (Current Weekly Expiry).
 * 4. 5m RSI(14) crosses below 15m RSI(14) -> Buy ATM PE (Current Weekly Expiry).
 * 5. Strict 1 trade per day limit.
 * 6. Exit on reverse crossover or mandatory 15:05:10 IST EOD square-off.
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

    @Value("${trading-bot.strategy.rsi-crossover.adx-threshold:20.0}")
    private double adxThreshold = 20.0;

    @Value("${trading-bot.strategy.rsi-crossover.stop-loss-percent:20.0}")
    private double stopLossPercent = 20.0;

    @Value("${trading-bot.strategy.rsi-crossover.target-profit-percent:50.0}")
    private double targetProfitPercent = 50.0;

    @Value("${trading-bot.strategy.rsi-crossover.telegram-alerts:true}")
    private boolean telegramAlerts = true;

    private final AtomicBoolean tradeExecutedToday = new AtomicBoolean(false);
    private final AtomicReference<RsiCrossoverPosition> openPosition = new AtomicReference<>(null);
    private final List<RsiCrossoverPosition> tradeHistory = new CopyOnWriteArrayList<>();
    private final AtomicInteger tradeCounter = new AtomicInteger(1);

    // Latest evaluated indicator values for monitoring
    private volatile double latestRsi5m = Double.NaN;
    private volatile double latestRsi15m = Double.NaN;
    private volatile double prevRsi5m = Double.NaN;
    private volatile double prevRsi15m = Double.NaN;
    private volatile double latestAdx15m = Double.NaN;
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
            log.debug("[RSI-STRATEGY] Current time {} is before strategy start window (09:45 IST).", nowTime);
            return;
        }

        if (nowTime.isAfter(TIME_SQUARE_OFF)) {
            log.debug("[RSI-STRATEGY] Current time {} is past square-off time (15:05 IST).", nowTime);
            return;
        }

        log.info("[RSI-STRATEGY] Running 5-min RSI Crossover cycle at {} IST...", nowTime);

        // Fetch 5-day 5-min historical candles (guarantees >= 350 bars for warm RSI)
        List<Candle> fiveMinCandles = marketDataService.fetchHistoricalCandles(NIFTY_EXCHANGE, NIFTY_TOKEN, NIFTY_SYMBOL, "5", 5);
        if (fiveMinCandles == null || fiveMinCandles.size() < (rsiPeriod + 10)) {
            log.warn("[RSI-STRATEGY] Insufficient 5m candles retrieved: {}", (fiveMinCandles != null ? fiveMinCandles.size() : 0));
            return;
        }

        List<Candle> fifteenMinCandles = CandleResamplingUtil.resample5MinTo15Min(fiveMinCandles);
        if (fifteenMinCandles == null || fifteenMinCandles.size() < (rsiPeriod + 5)) {
            log.warn("[RSI-STRATEGY] Insufficient 15m resampled candles: {}", (fifteenMinCandles != null ? fifteenMinCandles.size() : 0));
            return;
        }

        // Calculate RSI(14) series
        double[] close5m = fiveMinCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] rsi5mSeries = taService.calculateRsiSeries(close5m, rsiPeriod);

        double[] close15m = fifteenMinCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double[] high15m = fifteenMinCandles.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
        double[] low15m = fifteenMinCandles.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
        double[] rsi15mSeries = taService.calculateRsiSeries(close15m, rsiPeriod);
        double[] adx15mSeries = taService.calculateAdxSeries(high15m, low15m, close15m, rsiPeriod);

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
        double adx15mCurr = (adx15mSeries.length > 0) ? adx15mSeries[adx15mSeries.length - 1] : Double.NaN;

        if (Double.isNaN(rsi5Curr) || Double.isNaN(rsi5Prev) || Double.isNaN(rsi15Curr) || Double.isNaN(rsi15Prev)) {
            log.warn("[RSI-STRATEGY] One or more RSI values evaluated to NaN: 5m=[{}, {}], 15m=[{}, {}]", rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr);
            return;
        }

        Candle latest5mCandle = fiveMinCandles.get(fiveMinCandles.size() - 1);
        double spotPrice = latest5mCandle.close().doubleValue();

        this.latestRsi5m = rsi5Curr;
        this.prevRsi5m = rsi5Prev;
        this.latestRsi15m = rsi15Curr;
        this.prevRsi15m = rsi15Prev;
        this.latestAdx15m = adx15mCurr;
        this.latestNiftyLtp = spotPrice;

        log.info(
                "[RSI-STRATEGY] NIFTY: ₹{} | 5m RSI: {:.2f} (prev: {:.2f}) | 15m RSI: {:.2f} (prev: {:.2f}) | 15m ADX: {:.2f}",
                spotPrice, rsi5Curr, rsi5Prev, rsi15Curr, rsi15Prev, adx15mCurr);

        // 1. Manage Active Open Position (Check SL, Target, or Crossover Reversal)
        RsiCrossoverPosition current = openPosition.get();
        if (current != null && !current.isClosed()) {
            evaluatePositionExit(current, rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr, spotPrice);
            return;
        }

        // 2. If no position is open, check Entry (Strict 1 trade per day limit)
        if (tradeExecutedToday.get()) {
            log.debug("[RSI-STRATEGY] 1 Trade per day limit reached for today. Skipping new entries.");
            return;
        }

        evaluateEntry(rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr, adx15mCurr, spotPrice);
    }

    /** Evaluates crossover entry condition with optional ADX momentum filter. */
    public void evaluateEntry(
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            double adx15mCurr,
            double spotPrice) {
        // Bullish Crossover: 5m RSI crosses above 15m RSI
        boolean bullishCrossover = (rsi5Prev <= rsi15Prev) && (rsi5Curr > rsi15Curr);

        // Bearish Crossover: 5m RSI crosses below 15m RSI
        boolean bearishCrossover = (rsi5Prev >= rsi15Prev) && (rsi5Curr < rsi15Curr);

        if (!bullishCrossover && !bearishCrossover) {
            log.debug(
                    "[RSI-STRATEGY] No crossover detected. (5m: {:.2f}, 15m: {:.2f})",
                    rsi5Curr, rsi15Curr);
            return;
        }

        // ADX Trend Strength Filter
        if (adxFilterEnabled && !Double.isNaN(adx15mCurr) && adx15mCurr < adxThreshold) {
            log.info(
                    "[RSI-STRATEGY] ⚠️ Crossover detected but 15m ADX ({:.2f}) < threshold ({:.2f}). Skipping low-momentum entry.",
                    adx15mCurr, adxThreshold);
            return;
        }

        if (bullishCrossover) {
            log.info(
                    "[RSI-STRATEGY] 🟢 BULLISH CROSSOVER DETECTED: 5m ({:.2f}) crossed ABOVE 15m ({:.2f}) [ADX: {:.2f}]! Entering ATM CE Buy...",
                    rsi5Curr, rsi15Curr, adx15mCurr);
            executeOptionBuy("CE", spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
        } else {
            log.info(
                    "[RSI-STRATEGY] 🔴 BEARISH CROSSOVER DETECTED: 5m ({:.2f}) crossed BELOW 15m ({:.2f}) [ADX: {:.2f}]! Entering ATM PE Buy...",
                    rsi5Curr, rsi15Curr, adx15mCurr);
            executeOptionBuy("PE", spotPrice, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
        }
    }

    /** Evaluates exit condition for an active open position (Stop Loss, Target, or RSI Reversal). */
    public void evaluatePositionExit(
            RsiCrossoverPosition current,
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr,
            double currentSpotPrice) {
        BigDecimal currentPremium = fetchOptionPremium(current.getStrike(), current.getOptionType());
        if (currentPremium == null || currentPremium.compareTo(BigDecimal.ZERO) <= 0) {
            // Estimate via delta 0.50 if quote fetch fails
            double spotDiff = "CE".equalsIgnoreCase(current.getOptionType())
                    ? currentSpotPrice - current.getStrike().doubleValue()
                    : current.getStrike().doubleValue() - currentSpotPrice;
            currentPremium = current.getEntryPrice().add(BigDecimal.valueOf(spotDiff * 0.50));
        }

        BigDecimal entryPrice = current.getEntryPrice();
        if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
            // Hard Stop Loss check
            if (stopLossPercent > 0.0) {
                BigDecimal slThreshold = entryPrice.multiply(BigDecimal.valueOf(1.0 - (stopLossPercent / 100.0)));
                if (currentPremium.compareTo(slThreshold) <= 0) {
                    log.info(
                            "[RSI-STRATEGY] 🛑 HARD STOP-LOSS HIT for {}: Current ₹{} <= SL ₹{} ({}%)",
                            current.getSymbol(), currentPremium, slThreshold, stopLossPercent);
                    executeExit("HARD_SL_HIT");
                    return;
                }
            }

            // Target Profit check
            if (targetProfitPercent > 0.0) {
                BigDecimal tpThreshold = entryPrice.multiply(BigDecimal.valueOf(1.0 + (targetProfitPercent / 100.0)));
                if (currentPremium.compareTo(tpThreshold) >= 0) {
                    log.info(
                            "[RSI-STRATEGY] 🎯 TARGET PROFIT HIT for {}: Current ₹{} >= TP ₹{} (+{}%)",
                            current.getSymbol(), currentPremium, tpThreshold, targetProfitPercent);
                    executeExit("TARGET_PROFIT_HIT");
                    return;
                }
            }
        }

        // Crossover Reversal check
        evaluateExitOnReversal(current, rsi5Prev, rsi5Curr, rsi15Prev, rsi15Curr);
    }

    /** Evaluates reversal exit condition for an active open position. */
    public void evaluateExitOnReversal(
            RsiCrossoverPosition current,
            double rsi5Prev,
            double rsi5Curr,
            double rsi15Prev,
            double rsi15Curr) {
        String optionType = current.getOptionType();

        if ("CE".equalsIgnoreCase(optionType)) {
            // Holding CE: Exit when 5m RSI crosses below 15m RSI
            if (rsi5Curr < rsi15Curr) {
                log.info(
                        "[RSI-STRATEGY] 🏁 CE Exit Reversal Triggered: 5m RSI ({:.2f}) < 15m RSI ({:.2f})",
                        rsi5Curr, rsi15Curr);
                executeExit("RSI_REVERSAL_BEARISH");
            }
        } else if ("PE".equalsIgnoreCase(optionType)) {
            // Holding PE: Exit when 5m RSI crosses above 15m RSI
            if (rsi5Curr > rsi15Curr) {
                log.info(
                        "[RSI-STRATEGY] 🏁 PE Exit Reversal Triggered: 5m RSI ({:.2f}) > 15m RSI ({:.2f})",
                        rsi5Curr, rsi15Curr);
                executeExit("RSI_REVERSAL_BULLISH");
            }
        }
    }

    /** Executes Option Buy Entry (Paper or Live). */
    public synchronized void executeOptionBuy(
            String optionType,
            double spotPrice,
            double rsi5Curr,
            double rsi15Curr,
            double rsi5Prev,
            double rsi15Prev) {
        // Calculate ATM Strike
        BigDecimal atmStrike = StockFnoRegistry.calculateAtmStrike("NIFTY50", BigDecimal.valueOf(spotPrice));
        if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
            atmStrike = BigDecimal.valueOf(Math.round(spotPrice / 50.0) * 50);
        }

        int totalQuantity = lots * lotSize;
        String tradeId = "RSI_TRD_" + tradeCounter.getAndIncrement();

        // Resolve Option Symbol and Premium
        String optionSymbol = resolveOptionSymbol(atmStrike, optionType);
        BigDecimal entryPremium = fetchOptionPremium(atmStrike, optionType);
        if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) <= 0) {
            entryPremium = BigDecimal.valueOf(150.0); // Safe simulated fallback
        }

        RsiCrossoverPosition position =
                new RsiCrossoverPosition(
                        tradeId,
                        optionSymbol,
                        optionType,
                        atmStrike,
                        entryPremium,
                        totalQuantity,
                        Instant.now(clock));

        // If Live Auto-Execution is enabled
        if (autoExecute && config.isEnabled()) {
            try {
                OrderRequest orderReq = OrderRequest.market(optionSymbol, "NFO", TransactionType.BUY, totalQuantity, tradeId);
                orderService.placeOrder(orderReq);
                log.info("[RSI-STRATEGY] [LIVE] Placed Buy Order for {} Qty {}", totalQuantity, optionSymbol);
            } catch (Exception e) {
                log.error("[RSI-STRATEGY] Live order placement failed: {}", e.getMessage(), e);
            }
        }

        this.openPosition.set(position);
        this.tradeExecutedToday.set(true);

        log.info(
                "[RSI-STRATEGY] OPTION BUY FILLED: {} | {} Strike ₹{} @ ₹{} | Qty: {}",
                tradeId, optionSymbol, atmStrike, entryPremium, totalQuantity);

        if (telegramAlerts) {
            telegramService.sendRsiCrossoverEntryAlert(
                    position, rsi5Curr, rsi15Curr, rsi5Prev, rsi15Prev);
        }
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

        // If Live Auto-Execution is enabled, place Sell exit order
        if (autoExecute && config.isEnabled()) {
            try {
                OrderRequest exitReq = OrderRequest.market(current.getSymbol(), "NFO", TransactionType.SELL, current.getQuantity(), current.getTradeId() + "_EXIT");
                orderService.placeOrder(exitReq);
                log.info("[RSI-STRATEGY] [LIVE] Placed Sell Exit Order for {} Qty {}", current.getQuantity(), current.getSymbol());
            } catch (Exception e) {
                log.error("[RSI-STRATEGY] Live exit order placement failed: {}", e.getMessage(), e);
            }
        }

        current.close(exitPremium, reason, Instant.now(clock));
        tradeHistory.add(current);
        openPosition.set(null);

        log.info(
                "[RSI-STRATEGY] POSITION EXITED: {} | Entry: ₹{} | Exit: ₹{} | P&L: ₹{} | Reason: {}",
                current.getSymbol(), current.getEntryPrice(), exitPremium, current.getPnl(), reason);

        if (telegramAlerts) {
            telegramService.sendRsiCrossoverExitAlert(current, reason);
        }
    }

    /** Mandatory EOD Square-Off (called at 15:05:10 IST). */
    public synchronized void executeSquareOff(String reason) {
        RsiCrossoverPosition current = openPosition.get();
        if (current != null && !current.isClosed()) {
            log.info("[RSI-STRATEGY] 15:05 Mandatory Square-Off triggered for {}", current.getSymbol());
            executeExit(reason);
        }
    }

    /** Resets the daily state for a new trading day (called at 09:15:00 IST). */
    public synchronized void resetDaily() {
        log.info("[RSI-STRATEGY] Daily reset invoked: Clearing open positions and daily trade limit.");
        tradeExecutedToday.set(false);
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
                        OptionContract contract = "CE".equalsIgnoreCase(optionType) ? os.call() : os.put();
                        if (contract != null && contract.ltp() != null && contract.ltp().compareTo(BigDecimal.ZERO) > 0) {
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
                        OptionContract contract = "CE".equalsIgnoreCase(optionType) ? os.call() : os.put();
                        if (contract != null && contract.symbol() != null) {
                            return contract.symbol();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[RSI-STRATEGY] Symbol resolution error: {}", e.getMessage());
        }
        return "NIFTY_ATM_" + strike.intValue() + "_" + optionType;
    }

    // --- Getters and Setters for Testing & Configuration ---

    public boolean isTradeExecutedToday() {
        return tradeExecutedToday.get();
    }

    public void setTradeExecutedToday(boolean executed) {
        this.tradeExecutedToday.set(executed);
    }

    public RsiCrossoverPosition getOpenPosition() {
        return openPosition.get();
    }

    public List<RsiCrossoverPosition> getTradeHistory() {
        return tradeHistory;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
    }

    public double getLatestAdx15m() {
        return latestAdx15m;
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

    public void setAutoExecute(boolean autoExecute) {
        this.autoExecute = autoExecute;
    }

    public double getLatestRsi5m() {
        return latestRsi5m;
    }

    public double getLatestRsi15m() {
        return latestRsi15m;
    }

    public double getLatestNiftyLtp() {
        return latestNiftyLtp;
    }
}

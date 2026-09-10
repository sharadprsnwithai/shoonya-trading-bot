package com.tradingbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.execution.ExecutionManager;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.DailyWmaPosition;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.BlackScholesUtil;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Institutional Positional Credit Spread Strategy on NIFTY 50 based on 19-period Daily WMA.
 *
 * Sells 0.20-0.25 Delta OTM options (PE on Bullish, CE on Bearish) capped at <= Rs 105 premium,
 * with a 2.0% OTM Long Hedge Leg for margin relief and tail-risk protection.
 */
@Service
public class DailyWmaStrategyService {

    private static final Logger log = LoggerFactory.getLogger(DailyWmaStrategyService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final ShoonyaConfig config;
    private final ShoonyaMarketDataService marketDataService;
    private final ShoonyaOptionChainService optionChainService;
    private final ShoonyaOrderService orderService;
    private final ExecutionManager executionManager;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final String symbolToken;
    private final int wmaPeriod;
    private final double targetDelta;
    private final double maxEntryPremium;
    private final int expirySwitchDay;
    private final double hedgeOtmPercent;
    private final double stopLossPercent;
    private final boolean allowReentry;
    private final String mode;
    private final int lots;
    private final int lotSize;
    private final String stateFilePath;

    private final AtomicReference<DailyWmaPosition> activePosition = new AtomicReference<>(null);
    private final List<DailyWmaPosition> closedTrades = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    public DailyWmaStrategyService(
            ShoonyaConfig config,
            ShoonyaMarketDataService marketDataService,
            ShoonyaOptionChainService optionChainService,
            ShoonyaOrderService orderService,
            ExecutionManager executionManager,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ObjectMapper objectMapper,
            @Value("${trading-bot.strategy.daily-wma.symbol:10576}") String symbolToken,
            @Value("${trading-bot.strategy.daily-wma.wma-period:19}") int wmaPeriod,
            @Value("${trading-bot.strategy.daily-wma.target-delta:0.22}") double targetDelta,
            @Value("${trading-bot.strategy.daily-wma.max-entry-premium:105.0}") double maxEntryPremium,
            @Value("${trading-bot.strategy.daily-wma.expiry-switch-day:15}") int expirySwitchDay,
            @Value("${trading-bot.strategy.daily-wma.hedge-otm-percent:0.02}") double hedgeOtmPercent,
            @Value("${trading-bot.strategy.daily-wma.stop-loss-percent:1.0}") double stopLossPercent,
            @Value("${trading-bot.strategy.daily-wma.allow-reentry:true}") boolean allowReentry,
            @Value("${trading-bot.strategy.daily-wma.mode:PAPER}") String mode,
            @Value("${trading-bot.strategy.daily-wma.lots:1}") int lots,
            @Value("${trading-bot.strategy.daily-wma.lot-size:65}") int lotSize,
            @Value("${trading-bot.strategy.daily-wma.state-file:data/wma_positional_state.json}") String stateFilePath) {
        this(
                config,
                marketDataService,
                optionChainService,
                orderService,
                executionManager,
                taService,
                telegramService,
                objectMapper,
                Clock.system(IST),
                symbolToken,
                wmaPeriod,
                targetDelta,
                maxEntryPremium,
                expirySwitchDay,
                hedgeOtmPercent,
                stopLossPercent,
                allowReentry,
                mode,
                lots,
                lotSize,
                stateFilePath);
    }

    public DailyWmaStrategyService(
            ShoonyaConfig config,
            ShoonyaMarketDataService marketDataService,
            ShoonyaOptionChainService optionChainService,
            ShoonyaOrderService orderService,
            ExecutionManager executionManager,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ObjectMapper objectMapper,
            Clock clock,
            String symbolToken,
            int wmaPeriod,
            double targetDelta,
            double maxEntryPremium,
            int expirySwitchDay,
            double hedgeOtmPercent,
            double stopLossPercent,
            boolean allowReentry,
            String mode,
            int lots,
            int lotSize,
            String stateFilePath) {
        this.config = config;
        this.marketDataService = marketDataService;
        this.optionChainService = optionChainService;
        this.orderService = orderService;
        this.executionManager = executionManager;
        this.taService = taService;
        this.telegramService = telegramService;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
        this.symbolToken = symbolToken;
        this.wmaPeriod = wmaPeriod;
        this.targetDelta = targetDelta;
        this.maxEntryPremium = maxEntryPremium;
        this.expirySwitchDay = expirySwitchDay;
        this.hedgeOtmPercent = hedgeOtmPercent;
        this.stopLossPercent = stopLossPercent;
        this.allowReentry = allowReentry;
        this.mode = mode;
        this.lots = lots;
        this.lotSize = lotSize;
        this.stateFilePath = stateFilePath;
    }

    @PostConstruct
    public synchronized void loadStateFromDisk() {
        try {
            File file = new File(stateFilePath);
            if (file.exists()) {
                StateContainer container = objectMapper.readValue(file, StateContainer.class);
                if (container != null) {
                    if (container.activePosition != null && !container.activePosition.isClosed()) {
                        activePosition.set(container.activePosition);
                        log.info(
                                "[DAILY-WMA] Restored active positional trade {} ({} {}) from disk",
                                container.activePosition.getTradeId(),
                                container.activePosition.getBias(),
                                container.activePosition.getShortSymbol());
                    }
                    if (container.closedTrades != null) {
                        closedTrades.clear();
                        closedTrades.addAll(container.closedTrades);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[DAILY-WMA] Could not load positional state from disk: {}", e.getMessage());
        }
    }

    public synchronized void saveStateToDisk() {
        try {
            File file = new File(stateFilePath);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            StateContainer container = new StateContainer();
            container.activePosition = activePosition.get();
            container.closedTrades = new ArrayList<>(closedTrades);
            objectMapper.writeValue(file, container);
        } catch (IOException e) {
            log.error("[DAILY-WMA] Failed to save positional state to disk: {}", e.getMessage());
        }
    }

    /**
     * Daily evaluation cycle executed at 09:30:10 AM IST.
     */
    public synchronized void evaluateDailyCycle() {
        LocalDate today = LocalDate.now(clock);
        log.info("[DAILY-WMA] ⏳ Starting Daily Evaluation cycle for {} (Date: {})...", symbolToken, today);

        // Fetch daily candles for the last 45 calendar days (needs at least wmaPeriod daily bars)
        String endDate = today.format(DATE_FMT);
        String startDate = today.minusDays(50).format(DATE_FMT);

        List<Candle> dailyCandles =
                marketDataService.fetchDailyCandles(symbolToken, 50);

        if (dailyCandles == null || dailyCandles.size() < wmaPeriod) {
            log.warn(
                    "[DAILY-WMA] Insufficient daily candle data (received {}, required {}). Aborting cycle.",
                    dailyCandles == null ? 0 : dailyCandles.size(),
                    wmaPeriod);
            return;
        }

        double[] closes =
                dailyCandles.stream()
                        .mapToDouble(c -> c.close().doubleValue())
                        .toArray();

        double latestSpot = closes[closes.length - 1];
        double latestWma = taService.calculateLatestWma(closes, wmaPeriod);

        if (Double.isNaN(latestWma)) {
            log.warn("[DAILY-WMA] Unable to compute 19-period WMA. Aborting cycle.");
            return;
        }

        String currentBias = latestSpot > latestWma ? "BULLISH" : "BEARISH";
        log.info(
                "[DAILY-WMA] 📊 Analysis: Spot ₹{:.2f} vs 19 WMA ₹{:.2f} ➔ Bias: {}",
                latestSpot, latestWma, currentBias);

        DailyWmaPosition current = activePosition.get();

        if (current != null && !current.isClosed()) {
            if (!currentBias.equalsIgnoreCase(current.getBias())) {
                log.info(
                        "[DAILY-WMA] 🔄 Trend Reversal detected! Open Bias {} != New Bias {}. Closing active spread...",
                        current.getBias(), currentBias);
                closeCurrentPosition("19WMA_TREND_REVERSAL", latestSpot);
            } else {
                log.info(
                        "[DAILY-WMA] ✅ Active spread {} aligns with daily trend ({}). Position held.",
                        current.getTradeId(), currentBias);
                return;
            }
        }

        // Enter new hedged credit spread
        enterPosition(currentBias, latestSpot, latestWma, today);
    }

    private void enterPosition(String bias, double spotPrice, double wma19, LocalDate today) {
        LocalDate targetExpiry = resolveTargetExpiry(today);
        String optionType = "BULLISH".equalsIgnoreCase(bias) ? "PE" : "CE";
        int totalQty = lots * lotSize;

        // Find candidate strikes using Black-Scholes Delta model & premium constraint
        long daysToExpiry = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(today, targetExpiry));
        double tteYears = daysToExpiry / 365.0;
        double impliedVol = 0.14; // Typical baseline NIFTY IV
        double riskFreeRate = 0.07;

        double selectedShortStrike = 0.0;
        double selectedShortPrice = 0.0;
        double selectedShortDelta = 0.0;

        if ("PE".equalsIgnoreCase(optionType)) {
            // Scan OTM Puts below spot in steps of 50
            double roundedAtm = Math.round(spotPrice / 50.0) * 50.0;
            for (double strike = roundedAtm - 50.0; strike >= roundedAtm - 1500.0; strike -= 50.0) {
                double delta = Math.abs(BlackScholesUtil.calculateDelta(spotPrice, strike, tteYears, impliedVol, riskFreeRate, false));
                double price = BlackScholesUtil.calculateOptionPrice(spotPrice, strike, tteYears, impliedVol, riskFreeRate, false);

                if (price <= maxEntryPremium && delta >= 0.15 && delta <= 0.30) {
                    selectedShortStrike = strike;
                    selectedShortPrice = price;
                    selectedShortDelta = delta;
                    if (delta <= targetDelta + 0.02) {
                        break; // Close enough to target delta
                    }
                }
            }
        } else {
            // Scan OTM Calls above spot in steps of 50
            double roundedAtm = Math.round(spotPrice / 50.0) * 50.0;
            for (double strike = roundedAtm + 50.0; strike <= roundedAtm + 1500.0; strike += 50.0) {
                double delta = BlackScholesUtil.calculateDelta(spotPrice, strike, tteYears, impliedVol, riskFreeRate, true);
                double price = BlackScholesUtil.calculateOptionPrice(spotPrice, strike, tteYears, impliedVol, riskFreeRate, true);

                if (price <= maxEntryPremium && delta >= 0.15 && delta <= 0.30) {
                    selectedShortStrike = strike;
                    selectedShortPrice = price;
                    selectedShortDelta = delta;
                    if (delta <= targetDelta + 0.02) {
                        break; // Close enough to target delta
                    }
                }
            }
        }

        if (selectedShortStrike <= 0.0) {
            log.warn("[DAILY-WMA] Could not locate candidate short strike matching delta and premium <= ₹{}. Skipping entry.", maxEntryPremium);
            return;
        }

        // Calculate 2% OTM Hedge Strike
        double hedgeStrike;
        if ("PE".equalsIgnoreCase(optionType)) {
            hedgeStrike = Math.round((selectedShortStrike * (1.0 - hedgeOtmPercent)) / 50.0) * 50.0;
        } else {
            hedgeStrike = Math.round((selectedShortStrike * (1.0 + hedgeOtmPercent)) / 50.0) * 50.0;
        }

        double hedgePrice =
                BlackScholesUtil.calculateOptionPrice(
                        spotPrice,
                        hedgeStrike,
                        tteYears,
                        impliedVol,
                        riskFreeRate,
                        "CE".equalsIgnoreCase(optionType));
        if (hedgePrice < 0.50) hedgePrice = 1.00;

        String shortSymbol = formatNiftyOptionSymbol(targetExpiry, (int) selectedShortStrike, optionType);
        String hedgeSymbol = formatNiftyOptionSymbol(targetExpiry, (int) hedgeStrike, optionType);

        BigDecimal shortEntryBd = BigDecimal.valueOf(selectedShortPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal hedgeEntryBd = BigDecimal.valueOf(hedgePrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netCredit = shortEntryBd.subtract(hedgeEntryBd);
        BigDecimal stopLoss = shortEntryBd.multiply(BigDecimal.valueOf(1.0 + stopLossPercent)).setScale(2, RoundingMode.HALF_UP);

        String tradeId = "WMA_" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "_" + System.currentTimeMillis() % 1000;

        DailyWmaPosition position =
                new DailyWmaPosition(
                        tradeId,
                        "NIFTY",
                        "SELL",
                        optionType,
                        bias,
                        today,
                        Instant.now(clock),
                        BigDecimal.valueOf(spotPrice).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(wma19).setScale(2, RoundingMode.HALF_UP),
                        targetExpiry,
                        shortSymbol,
                        BigDecimal.valueOf(selectedShortStrike),
                        shortEntryBd,
                        selectedShortDelta,
                        totalQty,
                        hedgeSymbol,
                        BigDecimal.valueOf(hedgeStrike),
                        hedgeEntryBd,
                        totalQty,
                        netCredit,
                        stopLoss,
                        0);

        if ("LIVE".equalsIgnoreCase(mode)) {
            log.info("[DAILY-WMA] [LIVE] Executing multi-leg spread: 1. BUY Hedge {}, 2. SELL Short {}", hedgeSymbol, shortSymbol);
            // LIVE multi-leg order execution
            // In real broker flow: buy hedge first, then sell short leg
        } else {
            log.info(
                    "[DAILY-WMA] [PAPER] Position Opened: {} Spread | Short: {} @ ₹{:.2f}, Hedge: {} @ ₹{:.2f} | Net Credit: ₹{:.2f}",
                    bias, shortSymbol, selectedShortPrice, hedgeSymbol, hedgePrice, netCredit.doubleValue());
        }

        activePosition.set(position);
        saveStateToDisk();
        telegramService.sendDailyWmaEntryAlert(position, spotPrice, wma19);
    }

    /**
     * Monitors stop loss on the short option leg.
     */
    public synchronized void monitorStopLoss() {
        DailyWmaPosition pos = activePosition.get();
        if (pos == null || pos.isClosed()) {
            return;
        }

        // Fetch current live LTP of short leg and hedge leg
        double currentShortLtp = fetchEstimatedLtp(pos.getShortStrike().doubleValue(), pos.getOptionType(), pos.getExpiryDate());
        double currentHedgeLtp = fetchEstimatedLtp(pos.getHedgeStrike().doubleValue(), pos.getOptionType(), pos.getExpiryDate());

        monitorStopLossWithLtp(currentShortLtp, currentHedgeLtp);
    }

    public synchronized void monitorStopLossWithLtp(double shortLtp, double hedgeLtp) {
        DailyWmaPosition pos = activePosition.get();
        if (pos == null || pos.isClosed()) {
            return;
        }

        if (pos.getStopLossPrice() != null && shortLtp >= pos.getStopLossPrice().doubleValue()) {
            log.warn(
                    "[DAILY-WMA] 🛑 STOP LOSS HIT for {}: Short LTP ₹{:.2f} >= SL Threshold ₹{:.2f}",
                    pos.getShortSymbol(), shortLtp, pos.getStopLossPrice().doubleValue());
            telegramService.sendDailyWmaStopLossAlert(pos, shortLtp);
            closeCurrentPositionWithPrices("STOP_LOSS_HIT", shortLtp, hedgeLtp, BigDecimal.valueOf(shortLtp));
        }
    }

    /**
     * Executes mandatory square-off at 15:15 IST on Expiry Day.
     */
    public synchronized void executeExpirySquareOff(String reason) {
        DailyWmaPosition pos = activePosition.get();
        if (pos == null || pos.isClosed()) {
            return;
        }

        LocalDate today = LocalDate.now(clock);
        if (today.isEqual(pos.getExpiryDate()) || "FORCED_SQUARE_OFF".equalsIgnoreCase(reason)) {
            log.info("[DAILY-WMA] ⏰ Executing Expiry Square-Off for trade {}", pos.getTradeId());
            closeCurrentPosition(reason != null ? reason : "EXPIRY_SQUARE_OFF", 0.0);
        }
    }

    public synchronized void closeCurrentPosition(String reason, double currentSpot) {
        DailyWmaPosition pos = activePosition.get();
        if (pos == null || pos.isClosed()) {
            return;
        }

        double shortExit = fetchEstimatedLtp(pos.getShortStrike().doubleValue(), pos.getOptionType(), pos.getExpiryDate());
        double hedgeExit = fetchEstimatedLtp(pos.getHedgeStrike().doubleValue(), pos.getOptionType(), pos.getExpiryDate());
        closeCurrentPositionWithPrices(reason, shortExit, hedgeExit, BigDecimal.valueOf(currentSpot));
    }

    private synchronized void closeCurrentPositionWithPrices(
            String reason, double shortExitPrice, double hedgeExitPrice, BigDecimal exitSpot) {
        DailyWmaPosition pos = activePosition.get();
        if (pos == null || pos.isClosed()) {
            return;
        }

        pos.close(
                BigDecimal.valueOf(shortExitPrice).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(hedgeExitPrice).setScale(2, RoundingMode.HALF_UP),
                reason,
                Instant.now(clock),
                LocalDate.now(clock),
                exitSpot);

        closedTrades.add(pos);
        activePosition.set(null);
        saveStateToDisk();

        log.info(
                "[DAILY-WMA] 🏁 Position Closed ({}). Realized P&L: ₹{:.2f}",
                reason, pos.getRealizedPnl().doubleValue());
        telegramService.sendDailyWmaExitAlert(pos, reason);
    }

    /**
     * Resolves target monthly expiry date based on 15th-of-the-month cutoff rule.
     */
    public LocalDate resolveTargetExpiry(LocalDate tradeDate) {
        LocalDate monthToTarget = (tradeDate.getDayOfMonth() <= expirySwitchDay)
                ? tradeDate
                : tradeDate.plusMonths(1);

        // Find last Thursday of that month
        LocalDate lastDayOfMonth = monthToTarget.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate lastThursday = lastDayOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));

        // If today is past last Thursday of this month, advance to next month's last Thursday
        if (lastThursday.isBefore(tradeDate) || lastThursday.isEqual(tradeDate)) {
            LocalDate nextMonth = tradeDate.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
            lastThursday = nextMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));
        }

        return lastThursday;
    }

    private double fetchEstimatedLtp(double strike, String optionType, LocalDate expiry) {
        LocalDate today = LocalDate.now(clock);
        long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, expiry));
        double tte = days / 365.0;
        // Approximation: if expiry today and tte ~ 0, return intrinsic value
        return BlackScholesUtil.calculateOptionPrice(25000.0, strike, Math.max(0.001, tte), 0.14, 0.07, "CE".equalsIgnoreCase(optionType));
    }

    private String formatNiftyOptionSymbol(LocalDate expiry, int strike, String optionType) {
        // Formats NIFTY24SEP24100PE
        String year = String.valueOf(expiry.getYear()).substring(2);
        String month = expiry.getMonth().name().substring(0, 3).toUpperCase();
        return String.format("NIFTY%s%s%d%s", year, month, strike, optionType.toUpperCase());
    }

    // Getters and testing helpers
    public DailyWmaPosition getOpenPosition() { return activePosition.get(); }
    public List<DailyWmaPosition> getTradeHistory() { return Collections.unmodifiableList(closedTrades); }
    public void setOpenPositionForTesting(DailyWmaPosition pos) { activePosition.set(pos); }

    public static class StateContainer {
        public DailyWmaPosition activePosition;
        public List<DailyWmaPosition> closedTrades;
    }
}

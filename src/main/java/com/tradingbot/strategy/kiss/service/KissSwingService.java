package com.tradingbot.strategy.kiss.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.kiss.config.KissStrategyConfig;
import com.tradingbot.strategy.kiss.indicator.KissIndicatorService;
import com.tradingbot.strategy.kiss.model.KissPosition;
import com.tradingbot.strategy.kiss.model.KissSignal;
import com.tradingbot.strategy.kiss.model.KissSignalType;
import com.tradingbot.strategy.kiss.model.KissSnapshot;
import com.tradingbot.strategy.kiss.model.KissState;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CommodityRegistry;
import com.tradingbot.util.Nifty200Registry;
import com.tradingbot.util.StockFnoRegistry;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Core Orchestrator for the KISS (Keep It Swing Systematic) Multi-Timeframe Strategy. Evaluates
 * Nifty 200 Futures and Major Commodities on 1-Hour Heikin-Ashi charts with Weekly HA trend filters
 * and 55-EMA Bands.
 */
@Service
public class KissSwingService {

    private static final Logger log = LoggerFactory.getLogger(KissSwingService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final KissStrategyConfig config;
    private final KissIndicatorService indicatorService;
    private final HistoricalOhlcCacheService ohlcCacheService;
    private final YahooFinanceService yahooFinanceService;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private KissState state = new KissState();

    public KissSwingService(
            KissStrategyConfig config,
            KissIndicatorService indicatorService,
            HistoricalOhlcCacheService ohlcCacheService,
            YahooFinanceService yahooFinanceService,
            TelegramService telegramService,
            ObjectMapper objectMapper) {
        this.config = config;
        this.indicatorService = indicatorService;
        this.ohlcCacheService = ohlcCacheService;
        this.yahooFinanceService = yahooFinanceService;
        this.telegramService = telegramService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadState();
    }

    /**
     * Executes a full scan across Nifty 200 and Commodities universe. Evaluates new entry setups
     * and manages existing open positions.
     */
    public List<KissSignal> scanAndExecute() {
        return scanUniverse(getScanUniverse(), "ALL");
    }

    /** Executes an hourly scan across the NSE Nifty 200 universe. */
    public List<KissSignal> scanNseUniverse() {
        return scanUniverse(Nifty200Registry.getNifty200Symbols(), "NSE Equities");
    }

    /** Executes an hourly scan across the MCX Commodities universe (09:00 - 23:00 IST). */
    public List<KissSignal> scanMcxUniverse() {
        return scanUniverse(CommodityRegistry.getAllSymbols(), "MCX Commodities");
    }

    /** Scans a specific list of symbols and manages open positions. */
    public List<KissSignal> scanUniverse(List<String> symbols, String universeLabel) {
        if (!config.isEnabled()) {
            log.debug("KISS strategy is disabled in configuration.");
            return List.of();
        }

        lock.lock();
        try {
            log.info(
                    "Starting KISS Multi-Timeframe Strategy Scan for {} ({} symbols)...",
                    universeLabel,
                    symbols.size());
            List<KissSignal> emittedSignals = new ArrayList<>();

            // 1. Manage active positions first
            manageOpenPositions();

            // 2. Scan Universe for new setups
            for (String symbol : symbols) {
                try {
                    KissSignal signal = evaluateSymbol(symbol);
                    if (signal != null) {
                        emittedSignals.add(signal);
                        handleNewSignal(signal);
                    }
                    if (config.getScanDelayMs() > 0) {
                        Thread.sleep(config.getScanDelayMs());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn(
                            "Error scanning symbol {} for KISS strategy: {}",
                            symbol,
                            e.getMessage());
                }
            }

            state.setLastScanTime(Instant.now());
            persistState();
            log.info(
                    "KISS {} scan completed. Active positions: {}, New signals: {}",
                    universeLabel,
                    state.getPositions().size(),
                    emittedSignals.size());
            return emittedSignals;
        } finally {
            lock.unlock();
        }
    }

    /** Evaluates a single symbol for entry setups. */
    public KissSignal evaluateSymbol(String symbol) {
        List<Candle> hourlyCandles = loadHourlyCandles(symbol);
        if (hourlyCandles == null || hourlyCandles.size() < config.getEmaPeriod() + 5) {
            return null;
        }

        List<Candle> dailyCandles = loadDailyCandles(symbol);
        KissSnapshot snapshot =
                indicatorService.computeSnapshot(symbol, hourlyCandles, dailyCandles);
        if (snapshot == null) {
            return null;
        }

        // Avoid duplicate entry if position is already active
        if (state.getPositions().containsKey(symbol)
                && state.getPositions().get(symbol).isActive()) {
            return null;
        }

        double ltp = snapshot.currentPrice();
        if (snapshot.isBullishSetup()) {
            double sl = snapshot.suggestedSl();
            double tp = snapshot.suggestedTarget();
            double risk = ltp - sl;
            double riskPct = (risk / ltp) * 100.0;
            return new KissSignal(
                    symbol,
                    KissSignalType.BUY_SIGNAL,
                    ltp,
                    sl,
                    tp,
                    risk,
                    riskPct,
                    snapshot.weeklyHaBullish(),
                    snapshot.ema55High(),
                    snapshot.ema55Low(),
                    snapshot.macdLine(),
                    snapshot.macdSignal(),
                    "Weekly HA Bullish + 1H Breakout above 55 EMA High + MACD > Signal & 0",
                    Instant.now());
        }

        if (snapshot.isBearishSetup()) {
            double sl = snapshot.suggestedSl();
            double tp = snapshot.suggestedTarget();
            double risk = sl - ltp;
            double riskPct = (risk / ltp) * 100.0;
            return new KissSignal(
                    symbol,
                    KissSignalType.SHORT_SIGNAL,
                    ltp,
                    sl,
                    tp,
                    risk,
                    riskPct,
                    snapshot.weeklyHaBullish(),
                    snapshot.ema55High(),
                    snapshot.ema55Low(),
                    snapshot.macdLine(),
                    snapshot.macdSignal(),
                    "Weekly HA Bearish + 1H Breakdown below 55 EMA Low + MACD < Signal & 0",
                    Instant.now());
        }

        return null;
    }

    /** Manages active positions for target, stop-loss, MACD momentum reversal, and weekend exit. */
    public void manageOpenPositions() {
        ZonedDateTime nowIst = ZonedDateTime.now(IST_ZONE);
        boolean isFridayAfternoon =
                config.isEnableWeekendExit()
                        && nowIst.getDayOfWeek() == DayOfWeek.FRIDAY
                        && !nowIst.toLocalTime().isBefore(java.time.LocalTime.of(15, 15));

        Map<String, KissPosition> activePositions = new LinkedHashMap<>(state.getPositions());
        boolean stateChanged = false;

        for (Map.Entry<String, KissPosition> entry : activePositions.entrySet()) {
            String symbol = entry.getKey();
            KissPosition pos = entry.getValue();
            if (!pos.isActive()) continue;

            List<Candle> hourly = loadHourlyCandles(symbol);
            if (hourly == null || hourly.isEmpty()) continue;

            Candle latestCandle = hourly.get(hourly.size() - 1);
            double currentLtp = latestCandle.close().doubleValue();
            pos.updateMarketPrice(currentLtp);

            KissSnapshot snapshot =
                    indicatorService.computeSnapshot(symbol, hourly, loadDailyCandles(symbol));

            // Check Weekend Exit (equities only)
            if (isFridayAfternoon && !CommodityRegistry.isCommodity(symbol)) {
                closePosition(
                        pos,
                        currentLtp,
                        "Friday 15:15 IST Weekend Risk Protection",
                        KissSignalType.EXIT_WEEKEND);
                stateChanged = true;
                continue;
            }

            if (pos.getSignalType() == KissSignalType.BUY_SIGNAL) {
                // Long Target Hit
                if (currentLtp >= pos.getTargetPrice()) {
                    closePosition(
                            pos,
                            currentLtp,
                            "Target 1:3 Hit (+Profit)",
                            KissSignalType.EXIT_TARGET);
                    stateChanged = true;
                }
                // Long Stop Loss Hit (Hourly candle close below SL)
                else if (currentLtp <= pos.getStopLoss()) {
                    closePosition(
                            pos, currentLtp, "Stop Loss Breached", KissSignalType.EXIT_STOP_LOSS);
                    stateChanged = true;
                }
                // Long MACD Reversal (MACD Line crossed below Signal Line)
                else if (snapshot != null && snapshot.macdLine() < snapshot.macdSignal()) {
                    closePosition(
                            pos,
                            currentLtp,
                            "MACD Momentum Reversal Cross",
                            KissSignalType.EXIT_MACD_REVERSAL);
                    stateChanged = true;
                }
            } else if (pos.getSignalType() == KissSignalType.SHORT_SIGNAL) {
                // Short Target Hit
                if (currentLtp <= pos.getTargetPrice()) {
                    closePosition(
                            pos,
                            currentLtp,
                            "Target 1:3 Hit (+Profit)",
                            KissSignalType.EXIT_TARGET);
                    stateChanged = true;
                }
                // Short Stop Loss Hit (Hourly candle close above SL)
                else if (currentLtp >= pos.getStopLoss()) {
                    closePosition(
                            pos, currentLtp, "Stop Loss Breached", KissSignalType.EXIT_STOP_LOSS);
                    stateChanged = true;
                }
                // Short MACD Reversal (MACD Line crossed above Signal Line)
                else if (snapshot != null && snapshot.macdLine() > snapshot.macdSignal()) {
                    closePosition(
                            pos,
                            currentLtp,
                            "MACD Momentum Reversal Cross",
                            KissSignalType.EXIT_MACD_REVERSAL);
                    stateChanged = true;
                }
            }
        }

        if (stateChanged) {
            persistState();
        }
    }

    private void handleNewSignal(KissSignal signal) {
        if (state.getPositions().containsKey(signal.symbol())) {
            log.debug(
                    "Already holding an active position for {}. Skipping duplicate signal.",
                    signal.symbol());
            return;
        }

        if (state.getPositions().size() >= config.getMaxConcurrentPositions()) {
            log.info(
                    "Max concurrent positions reached ({}). Skipping signal for {}",
                    config.getMaxConcurrentPositions(),
                    signal.symbol());
            return;
        }

        boolean isComm = CommodityRegistry.isCommodity(signal.symbol());
        double fxRate = isComm ? 86.5 : 1.0;
        double unitMultiplier = CommodityRegistry.getUnitMultiplier(signal.symbol());
        String curSymbol = isComm ? "$" : "₹";

        int lotSize = resolveLotSize(signal.symbol());
        double accountEquity = state.getTotalPortfolioEquity();
        double maxRiskBudget = accountEquity * config.getMaxRiskPerTradePct();
        double riskPerUnit = Math.max(0.01, signal.riskAmount());

        double riskPerLotInINR = riskPerUnit * (lotSize * unitMultiplier) * fxRate;
        int calculatedUnits = (int) (maxRiskBudget / riskPerLotInINR);
        int lots = Math.max(1, calculatedUnits);
        int totalQty = lots * lotSize;

        KissPosition position =
                new KissPosition(
                        signal.symbol(),
                        signal.signalType(),
                        signal.entryPrice(),
                        signal.stopLoss(),
                        signal.targetPrice(),
                        totalQty,
                        lotSize,
                        fxRate,
                        unitMultiplier,
                        Instant.now());

        state.getPositions().put(signal.symbol(), position);
        state.getRecentSignals().add(0, signal);
        if (state.getRecentSignals().size() > 50) {
            state.getRecentSignals().remove(state.getRecentSignals().size() - 1);
        }

        String msg =
                String.format(
                        "🚀 *KISS Strategy Signal*\n"
                                + "• Symbol: `%s`\n"
                                + "• Action: *%s*\n"
                                + "• Entry: `%s%.2f`\n"
                                + "• Stop Loss: `%s%.2f`\n"
                                + "• Target: `%s%.2f`\n"
                                + "• Lots: `%d` (Qty: %d)\n"
                                + "• Reason: %s",
                        signal.symbol(),
                        signal.signalType(),
                        curSymbol,
                        signal.entryPrice(),
                        curSymbol,
                        signal.stopLoss(),
                        curSymbol,
                        signal.targetPrice(),
                        lots,
                        totalQty,
                        signal.reason());
        telegramService.sendTextMessage(msg);
    }

    private void closePosition(
            KissPosition pos, double exitPrice, String reason, KissSignalType exitType) {
        pos.closePosition(exitPrice, reason, Instant.now());
        state.getPositions().remove(pos.getSymbol());
        state.getClosedPositions().add(0, pos);
        if (state.getClosedPositions().size() > 100) {
            state.getClosedPositions().remove(state.getClosedPositions().size() - 1);
        }

        state.setAvailableCapital(state.getAvailableCapital() + pos.getRealizedPnl());

        boolean isComm = CommodityRegistry.isCommodity(pos.getSymbol());
        String curSymbol = isComm ? "$" : "₹";

        String msg =
                String.format(
                        "🏁 *KISS Position Closed*\n"
                                + "• Symbol: `%s` (%s)\n"
                                + "• Exit Price: `%s%.2f` (Entry: `%s%.2f`)\n"
                                + "• Realized PnL: *₹%.2f*\n"
                                + "• Reason: %s",
                        pos.getSymbol(),
                        pos.getSignalType(),
                        curSymbol,
                        exitPrice,
                        curSymbol,
                        pos.getEntryPrice(),
                        pos.getRealizedPnl(),
                        reason);
        telegramService.sendTextMessage(msg);
    }

    private int resolveLotSize(String symbol) {
        if (CommodityRegistry.isCommodity(symbol)) {
            var meta = CommodityRegistry.getMetadata(symbol);
            return meta != null ? meta.lotSize() : 1;
        }
        int lotSize = StockFnoRegistry.getLotSize(symbol);
        return lotSize > 0 ? lotSize : 1;
    }

    public List<Candle> loadHourlyCandles(String symbol) {
        if (ohlcCacheService.getSqliteRepository() != null) {
            List<Candle> cached = ohlcCacheService.getSqliteRepository().getCandles(symbol, "60");
            if (cached != null && cached.size() >= 30) {
                Candle latest = cached.get(cached.size() - 1);
                Instant cutoff = Instant.now().minus(Duration.ofMinutes(65));
                if (latest.timestamp() != null && latest.timestamp().isAfter(cutoff)) {
                    return cached;
                }
            }
        }
        // Fallback to Yahoo Finance 1-Hour
        List<Candle> fetched = yahooFinanceService.fetchHourlyCandles(symbol, 60);
        if (!fetched.isEmpty() && ohlcCacheService.getSqliteRepository() != null) {
            ohlcCacheService.getSqliteRepository().batchUpsertCandles(symbol, "60", fetched);
            return fetched;
        }
        return (fetched != null && !fetched.isEmpty())
                ? fetched
                : (ohlcCacheService.getSqliteRepository() != null
                        ? ohlcCacheService.getSqliteRepository().getCandles(symbol, "60")
                        : List.of());
    }

    public List<Candle> loadDailyCandles(String symbol) {
        List<Candle> cached = ohlcCacheService.getDailyCandles(symbol);
        if (cached != null && cached.size() >= 30) {
            return cached;
        }
        List<Candle> fetched = yahooFinanceService.fetchDailyCandles(symbol, 2);
        return fetched != null ? fetched : List.of();
    }

    private List<String> getScanUniverse() {
        List<String> symbols = new ArrayList<>(Nifty200Registry.getNifty200Symbols());
        symbols.addAll(CommodityRegistry.getAllSymbols());
        return symbols;
    }

    public KissState getState() {
        return state;
    }

    public void setState(KissState state) {
        this.state = state;
    }

    private void persistState() {
        try {
            File file = new File(config.getStateFilePath());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, state);
        } catch (Exception e) {
            log.error("Failed to persist KISS strategy state: {}", e.getMessage());
        }
    }

    private void loadState() {
        try {
            File file = new File(config.getStateFilePath());
            if (file.exists() && file.length() > 0) {
                this.state = objectMapper.readValue(file, KissState.class);
                log.info(
                        "Loaded KISS strategy state. Active positions: {}",
                        state.getPositions().size());
            } else {
                this.state = new KissState();
                this.state.setAvailableCapital(config.getPaperCapital());
            }
        } catch (Exception e) {
            log.warn(
                    "Could not load KISS strategy state. Initializing fresh state: {}",
                    e.getMessage());
            this.state = new KissState();
            this.state.setAvailableCapital(config.getPaperCapital());
        }
    }
}

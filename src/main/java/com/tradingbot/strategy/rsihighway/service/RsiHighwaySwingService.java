package com.tradingbot.strategy.rsihighway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiService;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.MultiTimeframeRsiSnapshot;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayPosition;
import com.tradingbot.strategy.rsihighway.model.RsiHighwaySignal;
import com.tradingbot.strategy.rsihighway.model.RsiHighwaySignalType;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayState;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayTranche;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.Nifty500Registry;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Core Strategy Service orchestrating the RSI Highway Multi-Timeframe Swing Strategy.
 * Executes daily scans at 15:00 IST and morning plunge risk checks at 09:30 IST.
 */
@Service
public class RsiHighwaySwingService {

    private static final Logger log = LoggerFactory.getLogger(RsiHighwaySwingService.class);
    private static final int CANDLES_HISTORY_DAYS = 750;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ShoonyaMarketDataService marketDataService;
    private final MultiTimeframeRsiService multiTimeframeRsiService;
    private final RsiHighwayMarketBreadthService breadthService;
    private final RsiHighwayExecutionService executionService;
    private final TelegramService telegramService;
    private final RsiHighwayConfig config;
    private final ObjectMapper objectMapper;

    private RsiHighwayState state = new RsiHighwayState();

    public RsiHighwaySwingService(
            ShoonyaMarketDataService marketDataService,
            MultiTimeframeRsiService multiTimeframeRsiService,
            RsiHighwayMarketBreadthService breadthService,
            RsiHighwayExecutionService executionService,
            TelegramService telegramService,
            RsiHighwayConfig config,
            ObjectMapper objectMapper) {
        this.marketDataService = marketDataService;
        this.multiTimeframeRsiService = multiTimeframeRsiService;
        this.breadthService = breadthService;
        this.executionService = executionService;
        this.telegramService = telegramService;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadState();
    }

    public synchronized void loadState() {
        try {
            File file = new File(config.getStateFilePath());
            if (file.exists() && file.length() > 0) {
                this.state = objectMapper.readValue(file, RsiHighwayState.class);
                log.info("[RSI-HIGHWAY] Loaded state with {} active positions from {}",
                        state.getPositions().size(), config.getStateFilePath());
            } else {
                this.state = new RsiHighwayState();
                this.state.setAvailableCapital(config.getPaperCapital());
                log.info("[RSI-HIGHWAY] Initialized fresh state at {}", config.getStateFilePath());
            }
        } catch (Exception e) {
            log.error("[RSI-HIGHWAY] Error loading state from {}, initializing empty state", config.getStateFilePath(), e);
            this.state = new RsiHighwayState();
            this.state.setAvailableCapital(config.getPaperCapital());
        }
    }

    public synchronized void saveState() {
        try {
            File file = new File(config.getStateFilePath());
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, state);
        } catch (Exception e) {
            log.error("[RSI-HIGHWAY] Failed to save state to {}", config.getStateFilePath(), e);
        }
    }

    public synchronized void evaluateEodScan() {
        evaluateEodScanForSymbols(Nifty500Registry.getAllSymbols());
    }

    public synchronized void evaluateEodScanForSymbols(List<String> symbols) {
        if (!config.isEnabled()) {
            log.info("[RSI-HIGHWAY] Strategy is disabled, skipping EOD scan.");
            return;
        }

        log.info("[RSI-HIGHWAY] Starting 15:00 IST EOD scan for {} symbols...", symbols.size());
        state.setLastEodScanTime(Instant.now());

        // 1. Fetch candles & evaluate Market Breadth
        Map<String, List<Candle>> candlesMap = new HashMap<>();
        for (String sym : symbols) {
            try {
                List<Candle> candles = marketDataService.fetchDailyCandles(sym, CANDLES_HISTORY_DAYS);
                if (candles != null && !candles.isEmpty()) {
                    candlesMap.put(sym, candles);
                }
            } catch (Exception e) {
                log.warn("[RSI-HIGHWAY] Failed fetching daily candles for {}: {}", sym, e.getMessage());
            }
        }

        List<Candle> indexCandles = candlesMap.get("NIFTY 50");
        if (indexCandles == null || indexCandles.isEmpty()) {
            try {
                indexCandles = marketDataService.fetchDailyCandles("NIFTY 50", CANDLES_HISTORY_DAYS);
            } catch (Exception e) {
                log.warn("[RSI-HIGHWAY] Failed fetching NIFTY 50 index candles: {}", e.getMessage());
            }
        }

        MarketBreadthSnapshot breadth;
        if (symbols.size() >= config.getMin52wLeaders()) {
            breadth = breadthService.evaluateBreadth(
                    candlesMap,
                    indexCandles,
                    config.getMin52wLeaders(),
                    config.getMaxIndexDrawdownPct()
            );
            state.setLastBreadthSnapshot(breadth);
        } else if (state.getLastBreadthSnapshot() != null) {
            breadth = state.getLastBreadthSnapshot();
            log.info("[RSI-HIGHWAY] Reusing active market breadth snapshot for partial scan of {} symbols (Highway Open: {})",
                    symbols.size(), breadth.isHighwayOpen());
        } else {
            breadth = new MarketBreadthSnapshot(true, symbols.size(), symbols.size(), symbols, 0.0, "Manual single-stock scan bypass", Instant.now());
        }

        // 2. Evaluate Exits & Pyramids for Existing Positions
        List<String> activeSymbols = new ArrayList<>(state.getPositions().keySet());
        for (String sym : activeSymbols) {
            RsiHighwayPosition position = state.getPositions().get(sym);
            if (position == null || !position.isActive()) continue;

            List<Candle> candles = candlesMap.get(sym);
            if (candles == null || candles.isEmpty()) {
                candles = marketDataService.fetchDailyCandles(sym, CANDLES_HISTORY_DAYS);
            }
            if (candles == null || candles.isEmpty()) continue;

            MultiTimeframeRsiSnapshot snap = multiTimeframeRsiService.computeSnapshot(sym, candles);
            if (snap == null) continue;

            double currentPrice = snap.currentPrice();
            position.setHighestPriceSeen(Math.max(position.getHighestPriceSeen(), currentPrice));
            position.setHighestDailyRsiSeen(Math.max(position.getHighestDailyRsiSeen(), snap.dailyRsi()));
            position.setLastEvaluatedAt(Instant.now());

            // Stop loss breach exit
            if (currentPrice < position.getCurrentSlPrice()) {
                log.warn("[RSI-HIGHWAY] SL Breach Exit for {}: Current ₹{} < SL ₹{}", sym, currentPrice, position.getCurrentSlPrice());
                if (executionService.executeExit(position, currentPrice, "SL Breach Exit")) {
                    archivePosition(position, currentPrice);
                    notifyTelegram(String.format("🛑 *RSI Highway SL Exit*\nSymbol: %s\nPrice: ₹%.2f\nAvg Entry: ₹%.2f",
                            sym, currentPrice, position.getAveragePrice()));
                }
                continue;
            }

            // Daily RSI < 50 Close Exit
            if (snap.dailyRsi() < config.getDailyRsiExit()) {
                log.info("[RSI-HIGHWAY] RSI 50 Trailing Exit for {}: Daily RSI {} < {}", sym, snap.dailyRsi(), config.getDailyRsiExit());
                if (executionService.executeExit(position, currentPrice, "Daily RSI < 50 Close Exit")) {
                    archivePosition(position, currentPrice);
                    notifyTelegram(String.format("🚪 *RSI Highway RSI 50 Exit*\nSymbol: %s\nDaily RSI: %.2f\nExit Price: ₹%.2f\nAvg Entry: ₹%.2f",
                            sym, snap.dailyRsi(), currentPrice, position.getAveragePrice()));
                }
                continue;
            }

            // Inverted Pyramiding Check (Tranche 2 or 3)
            if (position.getTrancheCount() < config.getMaxTranches()
                    && snap.isHighwayCandidate()
                    && snap.isDailySetupValid()
                    && currentPrice > position.getAveragePrice()
                    && canAddPyramidTranche(position, currentPrice, snap.timestamp())) {

                int nextTranche = position.getTrancheCount() + 1;
                RsiHighwaySignalType sigType = (nextTranche == 2)
                        ? RsiHighwaySignalType.PYRAMID_TRANCHE_2
                        : RsiHighwaySignalType.PYRAMID_TRANCHE_3;

                RsiHighwaySignal pyramidSignal = new RsiHighwaySignal(
                        sym,
                        sigType,
                        currentPrice,
                        Math.max(position.getCurrentSlPrice(), snap.signalCandleLow()),
                        nextTranche,
                        snap.monthlyRsi(),
                        snap.weeklyRsi(),
                        snap.dailyRsi(),
                        snap.dailyAtr(),
                        snap.pattern().orElse(null),
                        "Pyramid Tranche " + nextTranche + " Bounce",
                        snap.timestamp() != null ? snap.timestamp() : Instant.now()
                );

                Optional<RsiHighwayTranche> tranche = executionService.executeEntrySignal(
                        pyramidSignal,
                        state.getTotalPortfolioEquity(),
                        state.getAvailableCapital()
                );
                if (tranche.isPresent()) {
                    RsiHighwayTranche t = tranche.get();
                    state.setAvailableCapital(Math.max(0.0, state.getAvailableCapital() - (t.quantity() * t.entryPrice())));
                    position.addTranche(t);
                    // Trail SL up to the new swing bounce low
                    position.setCurrentSlPrice(Math.max(position.getCurrentSlPrice(), snap.signalCandleLow()));
                    state.getRecentSignals().add(pyramidSignal);
                    notifyTelegram(String.format("🚀 *RSI Highway Pyramid Tranche %d*\nSymbol: %s\nPrice: ₹%.2f\nQty: %d\nNew Avg: ₹%.2f",
                            nextTranche, sym, currentPrice, t.quantity(), position.getAveragePrice()));
                }
            }
        }

        // 3. Screen Universe for New Initial Entries (if Highway Open & Slot available)
        if (!breadth.isHighwayOpen()) {
            log.info("[RSI-HIGHWAY] Market Highway is CLOSED ({}). Skipping new entries.", breadth.reason());
            saveState();
            return;
        }

        int availableSlots = config.getMaxConcurrentPositions() - state.getPositions().size();
        if (availableSlots <= 0) {
            log.info("[RSI-HIGHWAY] Max concurrent positions ({}) reached. Skipping new entries.", config.getMaxConcurrentPositions());
            saveState();
            return;
        }

        List<MultiTimeframeRsiSnapshot> candidates = new ArrayList<>();
        for (String sym : symbols) {
            if (state.getPositions().containsKey(sym)) continue; // Already active

            List<Candle> candles = candlesMap.get(sym);
            if (candles == null || candles.isEmpty()) continue;

            MultiTimeframeRsiSnapshot optSnap = multiTimeframeRsiService.computeSnapshot(sym, candles);
            if (optSnap != null && optSnap.isHighwayCandidate() && optSnap.isDailySetupValid()) {
                candidates.add(optSnap);
            }
        }

        log.info("[RSI-HIGHWAY] Found {} valid entry setups for {} available portfolio slots.",
                candidates.size(), availableSlots);

        // Sort candidates by Daily RSI proximity to 50 (ascending)
        candidates.sort((a, b) -> Double.compare(Math.abs(a.dailyRsi() - 50.0), Math.abs(b.dailyRsi() - 50.0)));

        int slotsToFill = Math.min(availableSlots, candidates.size());
        for (int i = 0; i < slotsToFill; i++) {
            MultiTimeframeRsiSnapshot snap = candidates.get(i);
            double slPrice = Math.min(snap.signalCandleLow(), snap.currentPrice() - snap.dailyAtr());

            RsiHighwaySignal entrySignal = new RsiHighwaySignal(
                    snap.symbol(),
                    RsiHighwaySignalType.INITIAL_ENTRY,
                    snap.currentPrice(),
                    slPrice,
                    1,
                    snap.monthlyRsi(),
                    snap.weeklyRsi(),
                    snap.dailyRsi(),
                    snap.dailyAtr(),
                    snap.pattern().orElse(null),
                    "Initial Setup Confirmation (" + snap.pattern().map(PriceActionPattern::getDisplayName).orElse("Bounce") + ")",
                    snap.timestamp() != null ? snap.timestamp() : Instant.now()
            );

            Optional<RsiHighwayTranche> tranche = executionService.executeEntrySignal(
                    entrySignal,
                    state.getTotalPortfolioEquity(),
                    state.getAvailableCapital()
            );
            if (tranche.isPresent()) {
                RsiHighwayTranche t = tranche.get();
                state.setAvailableCapital(Math.max(0.0, state.getAvailableCapital() - (t.quantity() * t.entryPrice())));
                RsiHighwayPosition position = new RsiHighwayPosition(snap.symbol(), "NSE", snap.currentPrice(), slPrice);
                position.addTranche(t);
                state.getPositions().put(snap.symbol(), position);
                state.getRecentSignals().add(entrySignal);

                notifyTelegram(String.format("🟢 *RSI Highway New Entry*\nSymbol: %s\nEntry: ₹%.2f\nSL: ₹%.2f\nMonthly RSI: %.1f | Weekly: %.1f | Daily: %.1f\nPattern: %s",
                        snap.symbol(), snap.currentPrice(), slPrice, snap.monthlyRsi(), snap.weeklyRsi(), snap.dailyRsi(),
                        snap.pattern().map(PriceActionPattern::getDisplayName).orElse("RSI 50 Bounce")));
            }
        }

        saveState();
        log.info("[RSI-HIGHWAY] EOD Scan complete. Active positions: {}", state.getPositions().size());
    }

    public synchronized void evaluateMorningPlungeCheck() {
        if (!config.isEnabled()) return;

        log.info("[RSI-HIGHWAY] Starting 09:30 IST Morning Plunge Check for {} positions...", state.getPositions().size());
        state.setLastMorningCheckTime(Instant.now());

        List<String> activeSymbols = new ArrayList<>(state.getPositions().keySet());
        for (String sym : activeSymbols) {
            RsiHighwayPosition position = state.getPositions().get(sym);
            if (position == null || !position.isActive()) continue;

            List<Candle> candles = marketDataService.fetchDailyCandles(sym, CANDLES_HISTORY_DAYS);
            if (candles == null || candles.isEmpty()) continue;

            MultiTimeframeRsiSnapshot snap = multiTimeframeRsiService.computeSnapshot(sym, candles);
            if (snap == null) continue;

            double currentPrice = snap.currentPrice();
            position.setHighestPriceSeen(Math.max(position.getHighestPriceSeen(), currentPrice));
            position.setHighestDailyRsiSeen(Math.max(position.getHighestDailyRsiSeen(), snap.dailyRsi()));
            position.setLastEvaluatedAt(Instant.now());

            // Emergency plunge RSI < 45
            if (snap.dailyRsi() < config.getMorningEmergencyRsi()) {
                log.warn("[RSI-HIGHWAY] EMERGENCY PLUNGE EXIT for {}: Daily RSI {} < {}",
                        sym, snap.dailyRsi(), config.getMorningEmergencyRsi());
                if (executionService.executeExit(position, currentPrice, "Emergency Morning Plunge Exit")) {
                    archivePosition(position, currentPrice);
                    notifyTelegram(String.format("🚨 *RSI Highway EMERGENCY PLUNGE Exit*\nSymbol: %s\nDaily RSI: %.2f\nExit Price: ₹%.2f",
                            sym, snap.dailyRsi(), currentPrice));
                }
                continue;
            }

            // Stop loss breach
            if (currentPrice < position.getCurrentSlPrice()) {
                log.warn("[RSI-HIGHWAY] Morning SL Breach Exit for {}: Current ₹{} < SL ₹{}",
                        sym, currentPrice, position.getCurrentSlPrice());
                if (executionService.executeExit(position, currentPrice, "Morning SL Breach Exit")) {
                    archivePosition(position, currentPrice);
                    notifyTelegram(String.format("🛑 *RSI Highway Morning SL Exit*\nSymbol: %s\nExit Price: ₹%.2f",
                            sym, currentPrice));
                }
            }
        }

        saveState();
    }

    private boolean canAddPyramidTranche(RsiHighwayPosition position, double currentPrice, Instant candleTimestamp) {
        if (position.getTranches().isEmpty()) return true;
        RsiHighwayTranche lastTranche = position.getTranches().get(position.getTranches().size() - 1);
        if (lastTranche.entryTime() != null && candleTimestamp != null) {
            var lastDate = lastTranche.entryTime().atZone(IST).toLocalDate();
            var candleDate = candleTimestamp.atZone(IST).toLocalDate();
            if (lastDate.isEqual(candleDate)) {
                return false; // Already pyramided on this trading date
            }
        }
        return currentPrice > lastTranche.entryPrice();
    }

    private void archivePosition(RsiHighwayPosition position, double exitPrice) {
        position.setActive(false);
        position.setLastEvaluatedAt(Instant.now());
        double returnedCapital = position.getTotalQuantity() * exitPrice;
        state.setAvailableCapital(state.getAvailableCapital() + returnedCapital);
        state.getPositions().remove(position.getSymbol());
        state.getClosedPositions().add(position);
    }

    private void notifyTelegram(String message) {
        if (config.isTelegramAlerts() && telegramService != null) {
            try {
                telegramService.sendAsync(message);
            } catch (Exception e) {
                log.warn("[RSI-HIGHWAY] Failed to dispatch Telegram alert: {}", e.getMessage());
            }
        }
    }

    // Accessors
    public RsiHighwayState getState() {
        return state;
    }

    public Map<String, RsiHighwayPosition> getActivePositions() {
        return Collections.unmodifiableMap(state.getPositions());
    }

    public List<RsiHighwayPosition> getClosedPositions() {
        return Collections.unmodifiableList(state.getClosedPositions());
    }

    public MarketBreadthSnapshot getLastBreadthSnapshot() {
        return state.getLastBreadthSnapshot();
    }
}

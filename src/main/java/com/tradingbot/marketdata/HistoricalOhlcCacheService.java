package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.model.HistoricalOhlcData;
import com.tradingbot.marketdata.model.SymbolOhlcBundle;
import com.tradingbot.model.Candle;
import com.tradingbot.util.CandleResamplingUtil;
import com.tradingbot.util.Nifty500Registry;
import com.tradingbot.util.StockFnoRegistry;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages in-memory and disk-persisted daily, weekly, and monthly historical OHLC candles.
 * Populates from Yahoo Finance and resamples higher timeframe candles.
 */
@Service
public class HistoricalOhlcCacheService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalOhlcCacheService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final YahooFinanceService yahooService;
    private final ObjectMapper objectMapper;
    private final String stateFilePath;
    private final Map<String, SymbolOhlcBundle> cache = new ConcurrentHashMap<>();
    private volatile Instant lastUpdated;

    @Autowired
    public HistoricalOhlcCacheService(
            YahooFinanceService yahooService,
            ObjectMapper objectMapper,
            @Value("${trading-bot.ohlc.cache-file-path:data/historical_ohlc.json}")
                    String stateFilePath) {
        this.yahooService = yahooService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.stateFilePath = stateFilePath;
    }

    @PostConstruct
    public synchronized void init() {
        loadFromFile();
    }

    /** Loads cached OHLC data from the JSON state file. */
    public synchronized void loadFromFile() {
        File file = new File(stateFilePath);
        if (!file.exists()) {
            log.info("No historical OHLC cache file found at {}. Cache initialized empty.", stateFilePath);
            return;
        }

        try {
            HistoricalOhlcData data = objectMapper.readValue(file, HistoricalOhlcData.class);
            if (data != null && data.symbols() != null) {
                this.cache.clear();
                this.cache.putAll(data.symbols());
                this.lastUpdated = data.lastUpdated();
                log.info(
                        "[OHLC-CACHE] Loaded {} cached symbols from {} (lastUpdated: {})",
                        cache.size(),
                        stateFilePath,
                        lastUpdated);
            }
        } catch (Exception e) {
            log.error("[OHLC-CACHE] Failed to load historical OHLC cache from {}", stateFilePath, e);
        }
    }

    /** Atomically saves in-memory cache to the JSON state file. */
    public synchronized void saveToFile() {
        try {
            File file = new File(stateFilePath);
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            File tempFile = new File(file.getAbsolutePath() + ".tmp");
            HistoricalOhlcData data = new HistoricalOhlcData(Instant.now(), cache.size(), cache);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, data);

            try {
                Files.move(
                        tempFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                Files.move(
                        tempFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            this.lastUpdated = data.lastUpdated();
            log.info("[OHLC-CACHE] Successfully persisted {} symbols to {}", cache.size(), stateFilePath);
        } catch (IOException e) {
            log.error("[OHLC-CACHE] Failed to save historical OHLC cache to {}", stateFilePath, e);
        }
    }

    /** Synchronizes a single symbol's daily, weekly, and monthly candles from Yahoo Finance. */
    public boolean syncSymbol(String symbol, int yearsBack) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String clean = normalizeSymbol(symbol);
        try {
            List<Candle> daily = yahooService.fetchDailyCandles(clean, yearsBack);
            if (daily == null || daily.isEmpty()) {
                log.debug("[OHLC-CACHE] No daily candles returned from Yahoo for {}", clean);
                return false;
            }

            List<Candle> weekly = CandleResamplingUtil.resampleDailyToWeekly(daily);
            List<Candle> monthly = CandleResamplingUtil.resampleDailyToMonthly(daily);

            SymbolOhlcBundle bundle = new SymbolOhlcBundle(daily, weekly, monthly);
            cache.put(clean, bundle);
            return true;
        } catch (Exception e) {
            log.warn("[OHLC-CACHE] Error syncing symbol {}: {}", clean, e.getMessage());
            return false;
        }
    }

    /**
     * Synchronizes universe symbols (Nifty 500 + F&O + Nifty Index) from Yahoo Finance.
     * Skips symbols that already have fresh daily, weekly, and monthly data in JSON unless force is true.
     *
     * @param force If false, skips download for all symbols that already have fresh data.
     * @return Number of total symbols in cache
     */
    public int syncAll(boolean force) {
        if (!force && isCacheValidForToday()) {
            log.info("[OHLC-CACHE] Cache file is already globally valid for today. Skipping full sync.");
            return cache.size();
        }

        Set<String> allSymbols = new LinkedHashSet<>();
        allSymbols.add("NIFTY 50");
        allSymbols.addAll(StockFnoRegistry.getAllInstruments().keySet());
        allSymbols.addAll(Nifty500Registry.getAllMetadata().keySet());

        List<String> symbolsToFetch = new ArrayList<>();
        for (String sym : allSymbols) {
            if (force || !isSymbolFresh(sym)) {
                symbolsToFetch.add(sym);
            }
        }

        if (symbolsToFetch.isEmpty()) {
            log.info(
                    "[OHLC-CACHE] All {} symbols already have fresh historical data in JSON. Skipping network calls.",
                    allSymbols.size());
            return cache.size();
        }

        log.info(
                "[OHLC-CACHE] Fetching historical OHLC data for {} missing or stale symbols (out of {} total)...",
                symbolsToFetch.size(),
                allSymbols.size());

        int poolSize = 5;
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        try {
            for (String sym : symbolsToFetch) {
                executor.submit(() -> {
                    syncSymbol(sym, 2);
                });
            }

            executor.shutdown();
            boolean completed = executor.awaitTermination(3, TimeUnit.MINUTES);
            if (!completed) {
                log.warn("[OHLC-CACHE] Sync timed out waiting for all symbols to finish downloading");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[OHLC-CACHE] Sync interrupted");
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }

        saveToFile();
        int successCount = cache.size();
        log.info("[OHLC-CACHE] OHLC Sync completed. Total active cached symbols: {}", successCount);
        return successCount;
    }

    /**
     * Checks if a symbol's historical data is already present in cache and fresh (i.e. contains
     * the latest closed trading day/week/month data).
     *
     * @param symbol Symbol name (e.g. "RELIANCE", "NIFTY 50")
     * @return true if the symbol is present and its latest candle timestamp is up to date
     */
    public boolean isSymbolFresh(String symbol) {
        String clean = normalizeSymbol(symbol);
        SymbolOhlcBundle bundle = cache.get(clean);
        if (bundle == null || bundle.daily() == null || bundle.daily().isEmpty()) {
            return false;
        }

        List<Candle> daily = bundle.daily();
        Candle latest = daily.get(daily.size() - 1);
        if (latest.timestamp() == null) {
            return false;
        }

        LocalDate latestDate = latest.timestamp().atZone(IST).toLocalDate();
        LocalDate today = LocalDate.now(IST);

        // If today is Monday: latest candle from Friday (within 3 calendar days) is fresh
        if (today.getDayOfWeek().getValue() == 1) {
            return !latestDate.isBefore(today.minusDays(3));
        }
        // If today is weekend (Saturday/Sunday): Friday's candle is fresh
        if (today.getDayOfWeek().getValue() == 6) { // Saturday
            return !latestDate.isBefore(today.minusDays(1));
        }
        if (today.getDayOfWeek().getValue() == 7) { // Sunday
            return !latestDate.isBefore(today.minusDays(2));
        }
        // Tuesday through Friday: yesterday's or today's candle is fresh
        return !latestDate.isBefore(today.minusDays(1));
    }

    /** Checks if the cache was updated within the last 18 hours or matches today's trading date. */
    public boolean isCacheValidForToday() {
        if (cache.isEmpty() || lastUpdated == null) {
            return false;
        }
        LocalDate lastUpdateDate = lastUpdated.atZone(IST).toLocalDate();
        LocalDate today = LocalDate.now(IST);

        // If today is Monday, Friday's cache is still valid until market close
        if (today.getDayOfWeek().getValue() == 1 && lastUpdateDate.equals(today.minusDays(3))) {
            return true;
        }
        // If today is weekend
        if (today.getDayOfWeek().getValue() >= 6) {
            return true;
        }
        // Same date or updated within last 18 hours
        return lastUpdateDate.equals(today)
                || Duration.between(lastUpdated, Instant.now()).toHours() < 18;
    }

    public List<Candle> getDailyCandles(String symbol) {
        String clean = normalizeSymbol(symbol);
        SymbolOhlcBundle bundle = cache.get(clean);
        if (bundle == null || bundle.daily() == null || bundle.daily().isEmpty()) {
            if (syncSymbol(clean, 2)) {
                saveToFile();
                bundle = cache.get(clean);
            }
        }
        return bundle != null ? bundle.daily() : Collections.emptyList();
    }

    public List<Candle> getWeeklyCandles(String symbol) {
        String clean = normalizeSymbol(symbol);
        SymbolOhlcBundle bundle = cache.get(clean);
        if (bundle == null || bundle.weekly() == null || bundle.weekly().isEmpty()) {
            if (syncSymbol(clean, 2)) {
                saveToFile();
                bundle = cache.get(clean);
            }
        }
        return bundle != null ? bundle.weekly() : Collections.emptyList();
    }

    public List<Candle> getMonthlyCandles(String symbol) {
        String clean = normalizeSymbol(symbol);
        SymbolOhlcBundle bundle = cache.get(clean);
        if (bundle == null || bundle.monthly() == null || bundle.monthly().isEmpty()) {
            if (syncSymbol(clean, 2)) {
                saveToFile();
                bundle = cache.get(clean);
            }
        }
        return bundle != null ? bundle.monthly() : Collections.emptyList();
    }

    public int getCachedSymbolCount() {
        return cache.size();
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String clean = symbol.trim().toUpperCase();
        if (clean.startsWith("NSE:")) clean = clean.substring(4).trim();
        if ("NIFTY".equalsIgnoreCase(clean) || "NIFTY50".equalsIgnoreCase(clean)) {
            return "NIFTY 50";
        }
        return clean;
    }
}

package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.model.HistoricalOhlcData;
import com.tradingbot.marketdata.model.SymbolOhlcBundle;
import com.tradingbot.marketdata.repository.SqliteHistoricalOhlcRepository;
import com.tradingbot.model.Candle;
import com.tradingbot.util.CandleResamplingUtil;
import com.tradingbot.util.CommodityRegistry;
import com.tradingbot.util.Nifty500Registry;
import com.tradingbot.util.StockFnoRegistry;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
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
 * Manages in-memory and SQLite-persisted daily, weekly, and monthly historical OHLC candles.
 * Populates from Yahoo Finance and resamples higher timeframe candles. Supports transparent
 * migration from legacy JSON file to SQLite database.
 */
@Service
public class HistoricalOhlcCacheService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalOhlcCacheService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final YahooFinanceService yahooService;
    private final ObjectMapper objectMapper;
    private final SqliteHistoricalOhlcRepository sqliteRepository;
    private final String stateFilePath;
    private final boolean jsonBackupEnabled;

    private final Map<String, SymbolOhlcBundle> cache = new ConcurrentHashMap<>();
    private volatile Instant lastUpdated;
    private Clock clock = Clock.system(IST);

    @Autowired
    public HistoricalOhlcCacheService(
            YahooFinanceService yahooService,
            ObjectMapper objectMapper,
            @Autowired(required = false) SqliteHistoricalOhlcRepository sqliteRepository,
            @Value("${trading-bot.ohlc.cache-file-path:data/historical_ohlc.json}")
                    String stateFilePath,
            @Value("${trading-bot.ohlc.json-backup-enabled:true}") boolean jsonBackupEnabled) {
        this.yahooService = yahooService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.sqliteRepository = sqliteRepository;
        this.stateFilePath = stateFilePath;
        this.jsonBackupEnabled = jsonBackupEnabled;
    }

    public HistoricalOhlcCacheService(
            YahooFinanceService yahooService, ObjectMapper objectMapper, String stateFilePath) {
        this(yahooService, objectMapper, null, stateFilePath, true);
    }

    @PostConstruct
    public synchronized void init() {
        if (sqliteRepository != null) {
            int sqliteSymbols = sqliteRepository.getSymbolCount();
            if (sqliteSymbols > 0) {
                log.info(
                        "[OHLC-CACHE] Loading historical OHLC data from SQLite repository ({} symbols)...",
                        sqliteSymbols);
                Set<String> symbols = sqliteRepository.getAllCachedSymbols();
                for (String sym : symbols) {
                    SymbolOhlcBundle bundle = sqliteRepository.getSymbolBundle(sym);
                    if (bundle != null) {
                        cache.put(sym, bundle);
                    }
                }
                String lastUpdatedStr = sqliteRepository.getMetadata("LAST_UPDATED");
                if (lastUpdatedStr != null && !lastUpdatedStr.isBlank()) {
                    try {
                        this.lastUpdated = Instant.parse(lastUpdatedStr);
                    } catch (Exception ignored) {
                        this.lastUpdated = Instant.now(clock);
                    }
                } else {
                    this.lastUpdated = Instant.now(clock);
                }
                log.info(
                        "[OHLC-CACHE] Successfully loaded {} symbols from SQLite DB (lastUpdated: {})",
                        cache.size(),
                        lastUpdated);
                return;
            }
        }

        // If SQLite is empty or not configured, load from JSON state file
        loadFromJsonFile();

        // Migrate loaded JSON data to SQLite if repository is available
        if (sqliteRepository != null && !cache.isEmpty()) {
            log.info(
                    "[OHLC-CACHE] Migrating {} symbols from JSON to SQLite database...",
                    cache.size());
            for (Map.Entry<String, SymbolOhlcBundle> entry : cache.entrySet()) {
                sqliteRepository.saveSymbolBundle(entry.getKey(), entry.getValue());
            }
            if (lastUpdated != null) {
                sqliteRepository.setMetadata("LAST_UPDATED", lastUpdated.toString());
            }
            log.info(
                    "[OHLC-CACHE] Successfully migrated {} symbols to SQLite database at {}",
                    cache.size(),
                    sqliteRepository.getDbPath());
        }
    }

    /** Loads cached OHLC data from the JSON state file. */
    public synchronized void loadFromJsonFile() {
        if (stateFilePath == null || stateFilePath.isBlank()) return;
        File file = new File(stateFilePath);
        if (!file.exists()) {
            log.info(
                    "No historical OHLC cache JSON file found at {}. Cache initialized empty.",
                    stateFilePath);
            return;
        }

        try {
            HistoricalOhlcData data = objectMapper.readValue(file, HistoricalOhlcData.class);
            if (data != null && data.symbols() != null) {
                this.cache.clear();
                this.cache.putAll(data.symbols());
                this.lastUpdated = data.lastUpdated();
                log.info(
                        "[OHLC-CACHE] Loaded {} cached symbols from JSON {} (lastUpdated: {})",
                        cache.size(),
                        stateFilePath,
                        lastUpdated);
            }
        } catch (Exception e) {
            log.error(
                    "[OHLC-CACHE] Failed to load historical OHLC cache from {}", stateFilePath, e);
        }
    }

    /** Backwards-compatible loadFromFile wrapper. */
    public synchronized void loadFromFile() {
        init();
    }

    /** Atomically saves in-memory cache to SQLite and optionally to JSON backup. */
    public synchronized void saveToFile() {
        Instant now = Instant.now(clock);
        this.lastUpdated = now;

        if (sqliteRepository != null) {
            for (Map.Entry<String, SymbolOhlcBundle> entry : cache.entrySet()) {
                sqliteRepository.saveSymbolBundle(entry.getKey(), entry.getValue());
            }
            sqliteRepository.setMetadata("LAST_UPDATED", now.toString());
            log.info(
                    "[OHLC-CACHE] Persisted {} symbols to SQLite database at {}",
                    cache.size(),
                    sqliteRepository.getDbPath());
        }

        if (jsonBackupEnabled && stateFilePath != null && !stateFilePath.isBlank()) {
            try {
                File file = new File(stateFilePath);
                if (file.getParentFile() != null && !file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }

                File tempFile = new File(file.getAbsolutePath() + ".tmp");
                HistoricalOhlcData data = new HistoricalOhlcData(now, cache.size(), cache);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, data);

                try {
                    Files.move(
                            tempFile.toPath(),
                            file.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    Files.move(
                            tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                log.info(
                        "[OHLC-CACHE] Successfully saved {} symbols to JSON backup {}",
                        cache.size(),
                        stateFilePath);
            } catch (IOException e) {
                log.error(
                        "[OHLC-CACHE] Failed to save historical OHLC cache to {}",
                        stateFilePath,
                        e);
            }
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

            if (sqliteRepository != null) {
                sqliteRepository.saveSymbolBundle(clean, bundle);
            }
            return true;
        } catch (Exception e) {
            log.warn("[OHLC-CACHE] Error syncing symbol {}: {}", clean, e.getMessage());
            return false;
        }
    }

    /**
     * Synchronizes universe symbols (Nifty 500 + F&O + Nifty Index) from Yahoo Finance. Skips
     * symbols that already have fresh daily, weekly, and monthly data unless force is true.
     *
     * @param force If false, skips download for all symbols that already have fresh data.
     * @return Number of total symbols in cache
     */
    public int syncAll(boolean force) {
        Set<String> allSymbols = new LinkedHashSet<>();
        allSymbols.add("NIFTY 50");
        allSymbols.addAll(CommodityRegistry.getAllSymbols());
        allSymbols.addAll(StockFnoRegistry.getAllInstruments().keySet());
        allSymbols.addAll(Nifty500Registry.getAllMetadata().keySet());

        if (!force && isCacheValidForToday() && cache.size() >= allSymbols.size()) {
            log.info(
                    "[OHLC-CACHE] Cache is already globally valid and complete ({} symbols). Skipping full sync.",
                    cache.size());
            return cache.size();
        }

        List<String> symbolsToFetch = new ArrayList<>();
        for (String sym : allSymbols) {
            if (force || !isSymbolFresh(sym)) {
                symbolsToFetch.add(sym);
            }
        }

        if (symbolsToFetch.isEmpty()) {
            log.info(
                    "[OHLC-CACHE] All {} symbols already have fresh historical data. Skipping network calls.",
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
                executor.submit(
                        () -> {
                            syncSymbol(sym, 2);
                        });
            }

            executor.shutdown();
            boolean completed = executor.awaitTermination(3, TimeUnit.MINUTES);
            if (!completed) {
                log.warn(
                        "[OHLC-CACHE] Sync timed out waiting for all symbols to finish downloading");
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
     * Checks if a symbol's historical data is already present in cache and fresh (i.e. contains the
     * latest closed trading day/week/month data).
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
        LocalDate today = LocalDate.now(clock);

        // If today is Monday: latest candle from Thursday/Friday (within 4 calendar days) is fresh
        if (today.getDayOfWeek().getValue() == 1) {
            return !latestDate.isBefore(today.minusDays(4));
        }
        // If today is weekend (Saturday/Sunday): Thursday/Friday's candle is fresh
        if (today.getDayOfWeek().getValue() >= 6) {
            return !latestDate.isBefore(today.minusDays(3));
        }
        // Tuesday through Friday: must be at least yesterday (minusDays(1)) or 2 days ago if
        // mid-week holiday
        return !latestDate.isBefore(today.minusDays(2));
    }

    /** Checks if the cache has already been synchronized today or is valid over the weekend. */
    public boolean isCacheValidForToday() {
        if (cache.isEmpty() || lastUpdated == null) {
            return false;
        }
        LocalDate lastUpdateDate = lastUpdated.atZone(IST).toLocalDate();
        LocalDate today = LocalDate.now(clock);

        // If today is weekend (Saturday/Sunday), Friday's update is valid
        if (today.getDayOfWeek().getValue() >= 6) {
            return !lastUpdateDate.isBefore(
                    today.minusDays(today.getDayOfWeek().getValue() == 6 ? 1 : 2));
        }
        // On trading days (Monday - Friday): cache is only valid for today if synchronized today
        return lastUpdateDate.equals(today);
    }

    public void setClock(Clock clock) {
        this.clock = clock;
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

    public SqliteHistoricalOhlcRepository getSqliteRepository() {
        return sqliteRepository;
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

package com.tradingbot.marketdata.repository;

import com.tradingbot.marketdata.model.SymbolOhlcBundle;
import com.tradingbot.model.Candle;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Embedded SQLite repository for managing historical and intraday OHLC candles with high
 * performance, WAL concurrency, and B-Tree indexing.
 */
@Repository
public class SqliteHistoricalOhlcRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteHistoricalOhlcRepository.class);

    private final String dbPath;
    private final String jdbcUrl;
    private final DataSource dataSource;

    @Autowired
    public SqliteHistoricalOhlcRepository(
            @Value("${trading-bot.ohlc.sqlite-db-path:data/trading_bot.db}") String dbPath,
            @Autowired(required = false) DataSource dataSource) {
        this.dbPath = dbPath;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.dataSource = dataSource;
    }

    public SqliteHistoricalOhlcRepository(String dbPath) {
        this(dbPath, null);
    }

    @PostConstruct
    public void init() {
        ensureDirectoryExists();
        initSchema();
    }

    private void ensureDirectoryExists() {
        File file = new File(dbPath);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
    }

    public Connection getConnection() throws SQLException {
        Connection conn =
                (dataSource != null)
                        ? dataSource.getConnection()
                        : DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }
        return conn;
    }

    public void initSchema() {
        String createTableCandles =
                """
            CREATE TABLE IF NOT EXISTS historical_candles (
                symbol      TEXT NOT NULL,
                timeframe   TEXT NOT NULL,
                timestamp   INTEGER NOT NULL,
                open        REAL NOT NULL,
                high        REAL NOT NULL,
                low         REAL NOT NULL,
                close       REAL NOT NULL,
                volume      INTEGER NOT NULL,
                PRIMARY KEY (symbol, timeframe, timestamp)
            );
            """;

        String createIndexCandles =
                """
            CREATE INDEX IF NOT EXISTS idx_candles_lookup
            ON historical_candles (symbol, timeframe, timestamp DESC);
            """;

        String createTableMeta =
                """
            CREATE TABLE IF NOT EXISTS ohlc_metadata (
                key         TEXT PRIMARY KEY,
                value       TEXT NOT NULL,
                updated_at  INTEGER NOT NULL
            );
            """;

        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(createTableCandles);
            stmt.execute(createIndexCandles);
            stmt.execute(createTableMeta);
            log.info("[SQLITE-OHLC] Initialized SQLite schema at {}", dbPath);
        } catch (SQLException e) {
            log.error("[SQLITE-OHLC] Failed to initialize SQLite schema: {}", e.getMessage(), e);
        }
    }

    /** Batch inserts or replaces candles for a specific symbol and timeframe. */
    public void batchUpsertCandles(String symbol, String timeframe, List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;

        String sql =
                """
            INSERT OR REPLACE INTO historical_candles
            (symbol, timeframe, timestamp, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Candle c : candles) {
                    pstmt.setString(1, symbol);
                    pstmt.setString(2, timeframe);
                    pstmt.setLong(3, c.timestamp() != null ? c.timestamp().getEpochSecond() : 0L);
                    pstmt.setDouble(4, c.open().doubleValue());
                    pstmt.setDouble(5, c.high().doubleValue());
                    pstmt.setDouble(6, c.low().doubleValue());
                    pstmt.setDouble(7, c.close().doubleValue());
                    pstmt.setLong(8, c.volume());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-OHLC] Error batch inserting candles for {} ({}): {}",
                    symbol,
                    timeframe,
                    e.getMessage(),
                    e);
        }
    }

    /** Saves all daily, weekly, and monthly candles in a single atomic database transaction. */
    public void saveSymbolBundle(String symbol, SymbolOhlcBundle bundle) {
        if (bundle == null) return;
        if (bundle.daily() != null && !bundle.daily().isEmpty()) {
            batchUpsertCandles(symbol, "D", bundle.daily());
        }
        if (bundle.weekly() != null && !bundle.weekly().isEmpty()) {
            batchUpsertCandles(symbol, "W", bundle.weekly());
        }
        if (bundle.monthly() != null && !bundle.monthly().isEmpty()) {
            batchUpsertCandles(symbol, "M", bundle.monthly());
        }
    }

    /** Retrieves all candles for a symbol and timeframe sorted chronologically (ASC). */
    public List<Candle> getCandles(String symbol, String timeframe) {
        String sql =
                """
            SELECT symbol, timeframe, timestamp, open, high, low, close, volume
            FROM historical_candles
            WHERE symbol = ? AND timeframe = ?
            ORDER BY timestamp ASC
            """;

        List<Candle> result = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, symbol);
            pstmt.setString(2, timeframe);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRowToCandle(rs));
                }
            }
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-OHLC] Error fetching candles for {} ({}): {}",
                    symbol,
                    timeframe,
                    e.getMessage(),
                    e);
        }
        return result;
    }

    /** Retrieves the latest N candles for a symbol and timeframe sorted chronologically (ASC). */
    public List<Candle> getLatestCandles(String symbol, String timeframe, int limit) {
        String sql =
                """
            SELECT symbol, timeframe, timestamp, open, high, low, close, volume
            FROM (
                SELECT symbol, timeframe, timestamp, open, high, low, close, volume
                FROM historical_candles
                WHERE symbol = ? AND timeframe = ?
                ORDER BY timestamp DESC
                LIMIT ?
            )
            ORDER BY timestamp ASC
            """;

        List<Candle> result = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, symbol);
            pstmt.setString(2, timeframe);
            pstmt.setInt(3, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRowToCandle(rs));
                }
            }
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-OHLC] Error fetching latest candles for {} ({}): {}",
                    symbol,
                    timeframe,
                    e.getMessage(),
                    e);
        }
        return result;
    }

    /** Retrieves the full daily, weekly, and monthly bundle for a symbol from SQLite. */
    public SymbolOhlcBundle getSymbolBundle(String symbol) {
        List<Candle> daily = getCandles(symbol, "D");
        if (daily.isEmpty()) return null;
        List<Candle> weekly = getCandles(symbol, "W");
        List<Candle> monthly = getCandles(symbol, "M");
        return new SymbolOhlcBundle(daily, weekly, monthly);
    }

    /** Returns all unique symbols currently persisted in the SQLite database. */
    public Set<String> getAllCachedSymbols() {
        String sql = "SELECT DISTINCT symbol FROM historical_candles";
        Set<String> symbols = new HashSet<>();
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
                ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                symbols.add(rs.getString("symbol"));
            }
        } catch (SQLException e) {
            log.error("[SQLITE-OHLC] Error fetching distinct symbols: {}", e.getMessage(), e);
        }
        return symbols;
    }

    /** Returns the total count of unique symbols stored. */
    public int getSymbolCount() {
        String sql = "SELECT COUNT(DISTINCT symbol) FROM historical_candles";
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
                ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("[SQLITE-OHLC] Error fetching symbol count: {}", e.getMessage(), e);
        }
        return 0;
    }

    /** Returns the epoch second of the latest candle for a symbol and timeframe, or 0. */
    public long getLatestTimestamp(String symbol, String timeframe) {
        String sql =
                "SELECT MAX(timestamp) FROM historical_candles WHERE symbol = ? AND timeframe = ?";
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, symbol);
            pstmt.setString(2, timeframe);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-OHLC] Error fetching latest timestamp for {}: {}",
                    symbol,
                    e.getMessage(),
                    e);
        }
        return 0L;
    }

    public void setMetadata(String key, String value) {
        String sql =
                "INSERT OR REPLACE INTO ohlc_metadata (key, value, updated_at) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.setLong(3, Instant.now().getEpochSecond());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error("[SQLITE-OHLC] Error setting metadata {}: {}", key, e.getMessage(), e);
        }
    }

    public String getMetadata(String key) {
        String sql = "SELECT value FROM ohlc_metadata WHERE key = ?";
        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            log.error("[SQLITE-OHLC] Error getting metadata {}: {}", key, e.getMessage(), e);
        }
        return null;
    }

    private Candle mapRowToCandle(ResultSet rs) throws SQLException {
        String symbol = rs.getString("symbol");
        String timeframe = rs.getString("timeframe");
        long epochSec = rs.getLong("timestamp");
        Instant instant = Instant.ofEpochSecond(epochSec);
        BigDecimal open = BigDecimal.valueOf(rs.getDouble("open"));
        BigDecimal high = BigDecimal.valueOf(rs.getDouble("high"));
        BigDecimal low = BigDecimal.valueOf(rs.getDouble("low"));
        BigDecimal close = BigDecimal.valueOf(rs.getDouble("close"));
        long volume = rs.getLong("volume");

        return new Candle(symbol, timeframe, instant, open, high, low, close, volume);
    }

    public String getDbPath() {
        return dbPath;
    }
}

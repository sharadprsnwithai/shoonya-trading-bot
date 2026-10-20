package com.tradingbot.marketdata.repository;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Embedded SQLite repository storing the closing option-chain snapshot captured by the 20:00 IST
 * post-close job, one row per (session date, underlying symbol).
 *
 * <p>The table lives in the same {@code data/trading_bot.db} file as the OHLC cache so it is
 * covered by the nightly SQLite backup. The full {@code OptionChainResponse} JSON is the source of
 * truth; the scalar {@code pcr}/{@code synthetic} columns exist only so a human can inspect the
 * data with a plain {@code SELECT} without parsing JSON.
 */
@Repository
public class SqliteOptionChainSnapshotRepository {

    private static final Logger log =
            LoggerFactory.getLogger(SqliteOptionChainSnapshotRepository.class);

    private final String dbPath;
    private final String jdbcUrl;
    private final DataSource dataSource;

    /** One stored option-chain snapshot. */
    public record SnapshotRow(
            LocalDate sessionDate,
            String symbol,
            Instant capturedAt,
            double pcr,
            boolean synthetic,
            String chainJson) {}

    @Autowired
    public SqliteOptionChainSnapshotRepository(
            @Value("${trading-bot.ohlc.sqlite-db-path:data/trading_bot.db}") String dbPath,
            @Autowired(required = false) DataSource dataSource) {
        this.dbPath = dbPath;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.dataSource = dataSource;
    }

    public SqliteOptionChainSnapshotRepository(String dbPath) {
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

    private Connection getConnection() throws SQLException {
        Connection conn =
                dataSource != null
                        ? dataSource.getConnection()
                        : DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }
        return conn;
    }

    /** Creates the snapshot table if it does not exist yet. Safe to call repeatedly. */
    public void initSchema() {
        String createTable =
                """
            CREATE TABLE IF NOT EXISTS option_chain_snapshots (
                session_date   TEXT NOT NULL,
                symbol         TEXT NOT NULL,
                captured_at    INTEGER NOT NULL,
                pcr            REAL NOT NULL,
                synthetic      INTEGER NOT NULL DEFAULT 0,
                chain_json     TEXT NOT NULL,
                PRIMARY KEY (session_date, symbol)
            );
            """;

        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(createTable);
            log.info("[SQLITE-CHAIN-SNAPSHOT] Initialized SQLite schema at {}", dbPath);
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-CHAIN-SNAPSHOT] Failed to initialize SQLite schema: {}",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Inserts or replaces the snapshot for the given session date and symbol.
     *
     * <p>Keyed by the trading session the data <em>reflects</em> (not the wall-clock date of the
     * insert), so a re-run of the job is idempotent and a snapshot captured on a weekday holiday is
     * stored under the last session that actually traded.
     *
     * @param sessionDate the trading session the chain reflects, in yyyy-MM-dd form
     * @param symbol the underlying; trimmed and upper-cased before storing
     * @param capturedAt when the chain was fetched from the exchange
     * @param pcr the put-call ratio of the stored window (denormalised for plain SQL inspection)
     * @param synthetic whether the chain is the fabricated fallback; stored for completeness but
     *     consumers must treat it as unavailable
     * @param chainJson the serialised {@code OptionChainResponse}, the source of truth
     */
    public void save(
            LocalDate sessionDate,
            String symbol,
            Instant capturedAt,
            double pcr,
            boolean synthetic,
            String chainJson) {
        if (sessionDate == null || symbol == null || chainJson == null) {
            return;
        }
        String sql =
                """
            INSERT OR REPLACE INTO option_chain_snapshots
            (session_date, symbol, captured_at, pcr, synthetic, chain_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionDate.toString());
            pstmt.setString(2, normalize(symbol));
            pstmt.setLong(3, capturedAt != null ? capturedAt.getEpochSecond() : 0L);
            pstmt.setDouble(4, pcr);
            pstmt.setInt(5, synthetic ? 1 : 0);
            pstmt.setString(6, chainJson);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-CHAIN-SNAPSHOT] Error saving snapshot for {} on {}: {}",
                    symbol,
                    sessionDate,
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Looks up one snapshot by session date and symbol.
     *
     * @param sessionDate the trading session to probe, in yyyy-MM-dd form
     * @param symbol the underlying; trimmed and upper-cased before lookup
     * @return the stored row, or empty when no snapshot exists for that session and symbol
     */
    public Optional<SnapshotRow> find(LocalDate sessionDate, String symbol) {
        if (sessionDate == null || symbol == null) {
            return Optional.empty();
        }
        String sql =
                """
            SELECT session_date, symbol, captured_at, pcr, synthetic, chain_json
            FROM option_chain_snapshots
            WHERE session_date = ? AND symbol = ?
            """;

        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionDate.toString());
            pstmt.setString(2, normalize(symbol));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(
                            new SnapshotRow(
                                    LocalDate.parse(rs.getString("session_date")),
                                    rs.getString("symbol"),
                                    Instant.ofEpochSecond(rs.getLong("captured_at")),
                                    rs.getDouble("pcr"),
                                    rs.getInt("synthetic") != 0,
                                    rs.getString("chain_json")));
                }
            }
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-CHAIN-SNAPSHOT] Error reading snapshot for {} on {}: {}",
                    symbol,
                    sessionDate,
                    e.getMessage(),
                    e);
        }
        return Optional.empty();
    }

    /**
     * Deletes every snapshot older than the given session date (retention pruning).
     *
     * @param cutoffSessionDate exclusive lower bound; sessions strictly before it are deleted
     * @return the number of rows deleted
     */
    public int deleteSessionDatesBefore(LocalDate cutoffSessionDate) {
        if (cutoffSessionDate == null) {
            return 0;
        }
        String sql = "DELETE FROM option_chain_snapshots WHERE session_date < ?";

        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, cutoffSessionDate.toString());
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-CHAIN-SNAPSHOT] Error pruning snapshots before {}: {}",
                    cutoffSessionDate,
                    e.getMessage(),
                    e);
            return 0;
        }
    }

    /**
     * @return how many snapshots exist for the given session date
     */
    public int countForSession(LocalDate sessionDate) {
        if (sessionDate == null) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM option_chain_snapshots WHERE session_date = ?";

        try (Connection conn = getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sessionDate.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-CHAIN-SNAPSHOT] Error counting snapshots for {}: {}",
                    sessionDate,
                    e.getMessage(),
                    e);
            return 0;
        }
    }

    /**
     * @return every stored session date, ascending — useful for auditing job coverage
     */
    public List<LocalDate> findStoredSessionDates() {
        List<LocalDate> dates = new ArrayList<>();
        String sql =
                "SELECT DISTINCT session_date FROM option_chain_snapshots ORDER BY session_date";

        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                dates.add(LocalDate.parse(rs.getString(1)));
            }
        } catch (SQLException e) {
            log.error("[SQLITE-CHAIN-SNAPSHOT] Error listing session dates: {}", e.getMessage(), e);
        }
        return dates;
    }

    /**
     * @return the backing database path, for logging and diagnostics
     */
    public String getDbPath() {
        return dbPath;
    }

    private static String normalize(String symbol) {
        return symbol != null ? symbol.trim().toUpperCase() : "";
    }
}

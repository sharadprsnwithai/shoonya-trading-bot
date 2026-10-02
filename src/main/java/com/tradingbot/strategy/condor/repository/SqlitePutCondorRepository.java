package com.tradingbot.strategy.condor.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.strategy.condor.model.PutCondorCycleHistory;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * SQLite repository for persisting active Put Condor positions and completed monthly cycle history.
 */
@Repository
public class SqlitePutCondorRepository {

    private static final Logger log = LoggerFactory.getLogger(SqlitePutCondorRepository.class);

    private final String dbPath;
    private final ObjectMapper objectMapper;

    public SqlitePutCondorRepository(@Value("${trading-bot.ohlc.sqlite-db-path:data/trading_bot.db}") String dbPath) {
        this.dbPath = dbPath;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        try {
            File dbFile = new File(dbPath);
            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {

                // Table for active strategy state (single active record key = 1)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS put_condor_state (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        state TEXT NOT NULL,
                        position_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    );
                """);

                // Table for completed cycle history
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS put_condor_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        cycle_month TEXT NOT NULL,
                        entry_date TEXT NOT NULL,
                        exit_date TEXT NOT NULL,
                        entry_spot REAL NOT NULL,
                        exit_spot REAL NOT NULL,
                        lots INTEGER NOT NULL,
                        total_quantity INTEGER NOT NULL,
                        initial_debit_rs REAL NOT NULL,
                        realized_pnl_rs REAL NOT NULL,
                        roi_pct REAL NOT NULL,
                        max_drawdown_rs REAL NOT NULL,
                        adjustments_summary TEXT,
                        exit_reason TEXT,
                        created_at INTEGER NOT NULL
                    );
                """);
                log.info("SQLite Put Condor state and history schema initialized at {}", dbPath);
            }
        } catch (SQLException e) {
            log.error("Failed to initialize SQLite Put Condor schema: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /**
     * Saves or updates the active Put Condor position.
     */
    public synchronized void saveActivePosition(PutCondorPosition position) {
        if (position == null) {
            return;
        }
        String sql = """
            INSERT INTO put_condor_state (id, state, position_json, updated_at)
            VALUES (1, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                state = excluded.state,
                position_json = excluded.position_json,
                updated_at = excluded.updated_at;
        """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String json = objectMapper.writeValueAsString(position);
            ps.setString(1, position.getState().name());
            ps.setString(2, json);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.executeUpdate();
            log.debug("Active Put Condor position saved to SQLite (state={})", position.getState());
        } catch (Exception e) {
            log.error("Failed to save active Put Condor position to SQLite: {}", e.getMessage(), e);
        }
    }

    /**
     * Loads the active Put Condor position from SQLite if present.
     */
    public synchronized Optional<PutCondorPosition> loadActivePosition() {
        String sql = "SELECT position_json FROM put_condor_state WHERE id = 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String json = rs.getString("position_json");
                PutCondorPosition pos = objectMapper.readValue(json, PutCondorPosition.class);
                return Optional.ofNullable(pos);
            }
        } catch (Exception e) {
            log.error("Failed to load active Put Condor position from SQLite: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * Clears the active Put Condor position.
     */
    public synchronized void clearActivePosition() {
        String sql = "DELETE FROM put_condor_state WHERE id = 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
            log.info("Active Put Condor position cleared from SQLite");
        } catch (SQLException e) {
            log.error("Failed to clear active Put Condor position from SQLite: {}", e.getMessage(), e);
        }
    }

    /**
     * Records a completed monthly cycle into history.
     */
    public synchronized void saveCycleHistory(PutCondorCycleHistory history) {
        if (history == null) {
            return;
        }
        String sql = """
            INSERT INTO put_condor_history (
                cycle_month, entry_date, exit_date, entry_spot, exit_spot,
                lots, total_quantity, initial_debit_rs, realized_pnl_rs,
                roi_pct, max_drawdown_rs, adjustments_summary, exit_reason, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, history.getCycleMonth() != null ? history.getCycleMonth() : "");
            ps.setString(2, history.getEntryDate() != null ? history.getEntryDate().toString() : "");
            ps.setString(3, history.getExitDate() != null ? history.getExitDate().toString() : "");
            ps.setDouble(4, history.getEntrySpot() != null ? history.getEntrySpot().doubleValue() : 0.0);
            ps.setDouble(5, history.getExitSpot() != null ? history.getExitSpot().doubleValue() : 0.0);
            ps.setInt(6, history.getLots());
            ps.setInt(7, history.getTotalQuantity());
            ps.setDouble(8, history.getInitialNetDebitRs() != null ? history.getInitialNetDebitRs().doubleValue() : 0.0);
            ps.setDouble(9, history.getRealizedPnlRs() != null ? history.getRealizedPnlRs().doubleValue() : 0.0);
            ps.setDouble(10, history.getRoiPct() != null ? history.getRoiPct().doubleValue() : 0.0);
            ps.setDouble(11, history.getMaxDrawdownRs() != null ? history.getMaxDrawdownRs().doubleValue() : 0.0);
            ps.setString(12, history.getAdjustmentsSummary() != null ? history.getAdjustmentsSummary() : "");
            ps.setString(13, history.getExitReason() != null ? history.getExitReason() : "");
            ps.setLong(14, history.getCreatedAt() != null ? history.getCreatedAt().getEpochSecond() : Instant.now().getEpochSecond());
            ps.executeUpdate();
            log.info("Put Condor cycle history archived to SQLite for month {}", history.getCycleMonth());
        } catch (SQLException e) {
            log.error("Failed to save Put Condor cycle history to SQLite: {}", e.getMessage(), e);
        }
    }

    /**
     * Retrieves historical cycle records ordered by most recent.
     */
    public synchronized List<PutCondorCycleHistory> getHistory(int limit) {
        List<PutCondorCycleHistory> list = new ArrayList<>();
        String sql = "SELECT * FROM put_condor_history ORDER BY id DESC LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit > 0 ? limit : 50);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PutCondorCycleHistory h = new PutCondorCycleHistory();
                    h.setId(rs.getLong("id"));
                    h.setCycleMonth(rs.getString("cycle_month"));
                    h.setEntryDate(LocalDate.parse(rs.getString("entry_date")));
                    h.setExitDate(LocalDate.parse(rs.getString("exit_date")));
                    h.setEntrySpot(BigDecimal.valueOf(rs.getDouble("entry_spot")));
                    h.setExitSpot(BigDecimal.valueOf(rs.getDouble("exit_spot")));
                    h.setLots(rs.getInt("lots"));
                    h.setTotalQuantity(rs.getInt("total_quantity"));
                    h.setInitialNetDebitRs(BigDecimal.valueOf(rs.getDouble("initial_debit_rs")));
                    h.setRealizedPnlRs(BigDecimal.valueOf(rs.getDouble("realized_pnl_rs")));
                    h.setRoiPct(BigDecimal.valueOf(rs.getDouble("roi_pct")));
                    h.setMaxDrawdownRs(BigDecimal.valueOf(rs.getDouble("max_drawdown_rs")));
                    h.setAdjustmentsSummary(rs.getString("adjustments_summary"));
                    h.setExitReason(rs.getString("exit_reason"));
                    h.setCreatedAt(Instant.ofEpochSecond(rs.getLong("created_at")));
                    list.add(h);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch Put Condor history from SQLite: {}", e.getMessage(), e);
        }
        return list;
    }
}

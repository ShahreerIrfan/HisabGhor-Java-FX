package com.smartkhata.database;

import com.smartkhata.app.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages SQLite connection lifecycle, PRAGMA configuration,
 * and schema upgrades for SmartKhata.
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static String customJdbcUrl = null;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    private DatabaseManager() {}

    /**
     * Allows test fixtures to supply a temporary or in-memory database URL.
     */
    public static synchronized void setCustomJdbcUrl(String jdbcUrl) {
        customJdbcUrl = jdbcUrl;
        initialized.set(false);
        log.info("DatabaseManager custom JDBC URL configured: {}", jdbcUrl);
    }

    public static synchronized void resetCustomJdbcUrl() {
        customJdbcUrl = null;
        initialized.set(false);
    }

    public static Path getActiveDatabaseFile() {
        if (customJdbcUrl != null && customJdbcUrl.startsWith("jdbc:sqlite:")) {
            return Path.of(customJdbcUrl.substring("jdbc:sqlite:".length()));
        }
        return AppConfig.getDatabasePath();
    }

    public static synchronized void checkpointWal() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            log.info("SQLite WAL checkpoint (TRUNCATE) completed successfully");
        } catch (SQLException e) {
            log.warn("Could not checkpoint WAL: {}", e.getMessage());
        }
    }

    public static synchronized void resetInitialization() {
        initialized.set(false);
    }

    public static String getEffectiveJdbcUrl() {
        if (customJdbcUrl != null && !customJdbcUrl.isBlank()) {
            return customJdbcUrl;
        }
        Path dbPath = AppConfig.getDatabasePath();
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new DatabaseException("Failed to create database directory: " + dbPath.getParent(), e);
        }
        return "jdbc:sqlite:" + dbPath.toAbsolutePath().normalize().toString();
    }

    /**
     * Opens a new SQLite Connection with mandatory PRAGMA settings:
     * - foreign_keys = ON
     * - busy_timeout = 5000 ms
     * - journal_mode = WAL
     * - synchronous = NORMAL
     */
    public static Connection getConnection() throws SQLException {
        String url = getEffectiveJdbcUrl();

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);

        Connection conn = DriverManager.getConnection(url, config.toProperties());

        // Explicitly verify PRAGMA settings on the connection
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }

        return conn;
    }

    /**
     * Initializes the database on application startup and executes pending schema migrations.
     */
    public static synchronized void initializeDatabase() {
        if (initialized.get()) {
            return;
        }

        log.info("Initializing SmartKhata database persistence layer at: {}", getEffectiveJdbcUrl());
        try (Connection conn = getConnection()) {
            MigrationRunner.runMigrations(conn);
            verifyIntegrity(conn);
            initialized.set(true);
            log.info("Database initialized and verified successfully");
        } catch (SQLException e) {
            throw new DatabaseException("Could not initialize database persistence: " + e.getMessage(), e);
        }
    }

    /**
     * Executes PRAGMA integrity_check to detect physical file or indexing corruption.
     */
    public static void verifyIntegrity(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check;")) {
            if (rs.next()) {
                String result = rs.getString(1);
                if (!"ok".equalsIgnoreCase(result)) {
                    log.error("SQLite integrity check failed: {}", result);
                    throw new DatabaseException("Database integrity check failed: " + result);
                }
            }
        }
    }

    public static boolean isInitialized() {
        return initialized.get();
    }
}


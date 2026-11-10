package com.smartkhata.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Executes versioned, repeatable SQL schema migrations using SQLite PRAGMA user_version.
 * Never drops existing user tables during upgrades.
 */
public final class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private record Migration(int version, String filename, String description) {}

    // Registry of sequential schema migrations
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "V1__initial_schema.sql", "Initial 19-table core accounting and inventory schema"),
            new Migration(2, "V2__add_product_image_path.sql", "Add image_path attribute to products table"),
            new Migration(3, "V3__phase1_product_pricing_and_supplier_mapping.sql", "Extend supplier/product master pricing and add M:N product_suppliers"),
            new Migration(4, "V4__phase2_purchase_orders.sql", "Add purchase_orders and purchase_order_items tables"),
            new Migration(5, "V5__phase3_grn_costing_and_returns.sql", "Add GRN tables, inventory_cost_history, purchase_returns, and stock_movements doc extensions"),
            new Migration(6, "V6__phase4_supplier_invoices_and_matching.sql", "Add supplier_invoices, 3-way matching links, and payment allocation extensions"),
            new Migration(7, "V7__phase5_sales_cogs_and_returns.sql", "Add COGS/gross profit columns on sales/sale_items and partial sale_returns tables")
    );

    private MigrationRunner() {}

    public static int getLatestVersion() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    /**
     * Inspects current database schema version and executes pending migrations sequentially.
     */
    public static synchronized void runMigrations(Connection conn) {
        try {
            int currentVersion = getUserVersion(conn);
            log.info("Current SQLite database PRAGMA user_version is: {}", currentVersion);

            for (Migration migration : MIGRATIONS) {
                if (migration.version() > currentVersion) {
                    log.info("Applying migration V{}: {} ({})", migration.version(), migration.filename(), migration.description());
                    applyMigration(conn, migration);
                    setUserVersion(conn, migration.version());
                    log.info("Migration V{} applied successfully. Database user_version updated to {}", migration.version(), migration.version());
                } else {
                    log.debug("Migration V{} already applied. Skipping.", migration.version());
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to execute database migrations: " + e.getMessage(), e);
        }
    }

    public static int getUserVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    public static void setUserVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version = " + version + ";");
        }
    }

    private static void applyMigration(Connection conn, Migration migration) throws SQLException {
        String resourcePath = "/com/smartkhata/database/migrations/" + migration.filename();
        InputStream is = MigrationRunner.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new DatabaseException("Migration script not found on classpath: " + resourcePath);
        }

        String fullSql;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            fullSql = reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new DatabaseException("Failed to read migration script " + migration.filename() + ": " + e.getMessage(), e);
        }

        List<String> statements = parseStatements(fullSql);

        boolean originalAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    if (!sql.isBlank()) {
                        stmt.execute(sql);
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rbEx) {
                log.error("Failed to rollback migration V{}: {}", migration.version(), rbEx.getMessage());
            }
            throw new DatabaseException("Migration V" + migration.version() + " failed and was rolled back: " + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    /**
     * Splits SQL script into executable statements while safely stripping comments.
     */
    public static List<String> parseStatements(String fullScript) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStmt = new StringBuilder();

        for (String line : fullScript.split("\n")) {
            String trimmed = line.trim();
            // Skip pure comment lines
            if (trimmed.startsWith("--") || trimmed.startsWith("//")) {
                continue;
            }

            // Remove trailing inline comments
            int commentIdx = line.indexOf("--");
            if (commentIdx >= 0) {
                line = line.substring(0, commentIdx);
            }

            currentStmt.append(line).append("\n");

            if (trimmed.endsWith(";")) {
                String stmtStr = currentStmt.toString().trim();
                if (!stmtStr.isEmpty()) {
                    statements.add(stmtStr);
                }
                currentStmt.setLength(0);
            }
        }

        String leftover = currentStmt.toString().trim();
        if (!leftover.isEmpty()) {
            statements.add(leftover);
        }

        return statements;
    }
}


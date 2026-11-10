package com.smartkhata.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationAndSchemaIntegrationTest {

    private Path tempDbPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("test_migrations.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreateDatabaseAndApplyVersionOneMigrations() throws SQLException {
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            // 1. Verify PRAGMA user_version includes V7 sales COGS and returns
            int version = MigrationRunner.getUserVersion(conn);
            assertThat(version).isEqualTo(7);

            // 2. Verify core and Phase 1-5 supply chain tables exist
            Set<String> tables = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';")) {
                while (rs.next()) {
                    tables.add(rs.getString(1).toLowerCase());
                }
            }

            assertThat(tables).contains(
                    "businesses",
                    "users",
                    "customers",
                    "suppliers",
                    "categories",
                    "products",
                    "product_suppliers",
                    "purchase_orders",
                    "purchase_order_items",
                    "goods_receipt_notes",
                    "goods_receipt_items",
                    "inventory_cost_history",
                    "purchase_returns",
                    "purchase_return_items",
                    "supplier_invoices",
                    "supplier_invoice_items",
                    "grn_invoice_links",
                    "sale_returns",
                    "sale_return_items",
                    "stock_movements",
                    "sales",
                    "sale_items",
                    "purchases",
                    "purchase_items",
                    "payments",
                    "payment_allocations",
                    "customer_ledger",
                    "supplier_ledger",
                    "expenses",
                    "other_incomes",
                    "settings",
                    "audit_logs"
            );

            // 2b. Verify V2 + V3 columns on products
            Set<String> productColumns = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(products);")) {
                while (rs.next()) {
                    productColumns.add(rs.getString("name").toLowerCase());
                }
            }
            assertThat(productColumns).contains(
                    "image_path", "trade_price_paisha", "mrp_paisha",
                    "last_purchase_price_paisha", "purchase_unit", "pack_size"
            );

            // 2c. Verify V3 columns on suppliers
            Set<String> supplierColumns = new HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(suppliers);")) {
                while (rs.next()) {
                    supplierColumns.add(rs.getString("name").toLowerCase());
                }
            }
            assertThat(supplierColumns).contains(
                    "supplier_code", "email", "payment_terms", "credit_period_days", "is_active"
            );

            // 3. Verify repeated migration run is safe and idempotent
            MigrationRunner.runMigrations(conn);
            assertThat(MigrationRunner.getUserVersion(conn)).isEqualTo(7);
        }
    }

    @Test
    void test10_shouldUpgradeLegacyV2DatabasePreservingAllHistoricalDataAndLedgers(@TempDir Path legacyDir) throws Exception {
        Path legacyDb = legacyDir.resolve("legacy_v2_store.db");
        String legacyJdbc = "jdbc:sqlite:" + legacyDb.toAbsolutePath();

        // 1. Construct a genuine V2 database (only V1 + V2 migrations applied) with live historical records
        try (Connection conn = java.sql.DriverManager.getConnection(legacyJdbc)) {
            execSqlResource(conn, "/com/smartkhata/database/migrations/V1__initial_schema.sql");
            execSqlResource(conn, "/com/smartkhata/database/migrations/V2__add_product_image_path.sql");
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA user_version = 2");
                st.execute("INSERT INTO businesses (id, name, currency_code) VALUES (1, 'Legacy Bismillah Store', 'BDT')");
                st.execute("INSERT INTO users (id, business_id, username, full_name, password_hash, salt, role) " +
                        "VALUES (1, 1, 'legacy_owner', 'Owner', 'x', 'y', 'OWNER')");
                st.execute("INSERT INTO suppliers (id, business_id, name, phone, opening_balance_paisha) " +
                        "VALUES (10, 1, 'Legacy Millers', '01700000000', 2500000)");
                st.execute("INSERT INTO supplier_ledger (business_id, supplier_id, entry_type, reference_id, credit_paisha, debit_paisha, running_balance_paisha, notes) " +
                        "VALUES (1, 10, 'OPENING_BALANCE', 10, 2500000, 0, 2500000, 'Legacy opening balance')");
                st.execute("INSERT INTO categories (id, business_id, name) VALUES (5, 1, 'Oil')");
                st.execute("INSERT INTO products (id, business_id, category_id, name, barcode, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold, image_path) " +
                        "VALUES (100, 1, 5, '5 Liter Soyabean Oil', '646515216', 'SKU-OIL-00001', 'pkt', 78000, 84000, 5, '/images/oil.png')");
                st.execute("INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                        "VALUES (1, 100, 'OPENING', 20, 78000, 'MANUAL', 'Opening stock', 1)");
                st.execute("INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                        "VALUES (1, 100, 'SALE_OUT', -1, 78000, 'SALE', 'Past sale', 1)");
                st.execute("INSERT INTO sales (id, business_id, invoice_number, total_amount_paisha, discount_paisha, net_amount_paisha, paid_amount_paisha, due_amount_paisha, payment_status, created_by_user_id) " +
                        "VALUES (200, 1, 'INV-LEGACY-0001', 84000, 0, 84000, 84000, 0, 'PAID', 1)");
                st.execute("INSERT INTO sale_items (sale_id, product_id, quantity, unit_price_paisha, unit_cost_paisha, discount_paisha, line_total_paisha) " +
                        "VALUES (200, 100, 1, 84000, 78000, 0, 84000)");
            }
        }

        // 2. Boot SmartKhata database initializer against this V2 file -> runs V3..V7 cleanly
        DatabaseManager.setCustomJdbcUrl(legacyJdbc);
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            assertThat(MigrationRunner.getUserVersion(conn)).isEqualTo(7);

            // Verify legacy supplier preserved + auto-backfilled code
            try (ResultSet rs = st.executeQuery("SELECT name, supplier_code, opening_balance_paisha, is_active FROM suppliers WHERE id = 10")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Legacy Millers");
                assertThat(rs.getString("supplier_code")).isEqualTo("SUP-00010");
                assertThat(rs.getLong("opening_balance_paisha")).isEqualTo(2500000L);
                assertThat(rs.getInt("is_active")).isEqualTo(1);
            }

            // Verify legacy product pricing & stock movements completely intact
            try (ResultSet rs = st.executeQuery("SELECT name, cost_price_paisha, selling_price_paisha, trade_price_paisha, mrp_paisha, image_path FROM products WHERE id = 100")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("5 Liter Soyabean Oil");
                assertThat(rs.getLong("cost_price_paisha")).isEqualTo(78000L);
                assertThat(rs.getLong("selling_price_paisha")).isEqualTo(84000L);
                assertThat(rs.getLong("trade_price_paisha")).isEqualTo(78000L);
                assertThat(rs.getLong("mrp_paisha")).isEqualTo(84000L);
                assertThat(rs.getString("image_path")).isEqualTo("/images/oil.png");
            }
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE product_id = 100")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(19);
            }

            // Verify V7 backfilled historical sale COGS & Gross Profit accurately (840 - 780 = 60 BDT)
            try (ResultSet rs = st.executeQuery("SELECT total_cogs_paisha, gross_profit_paisha FROM sales WHERE id = 200")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("total_cogs_paisha")).isEqualTo(78000L);
                assertThat(rs.getLong("gross_profit_paisha")).isEqualTo(6000L);
            }
        }
    }

    private static void execSqlResource(Connection conn, String resourcePath) throws Exception {
        try (var is = MigrationAndSchemaIntegrationTest.class.getResourceAsStream(resourcePath)) {
            assertThat(is).isNotNull();
            String sql = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try (Statement st = conn.createStatement()) {
                for (String stmt : sql.split(";(?=(?:[^']*'[^']*')*[^']*$)")) {
                    String trimmed = stmt.replaceAll("(?m)^--.*$", "").trim();
                    if (!trimmed.isBlank()) {
                        st.execute(trimmed);
                    }
                }
            }
        }
    }
}


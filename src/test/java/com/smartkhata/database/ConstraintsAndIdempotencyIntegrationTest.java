package com.smartkhata.database;

import com.smartkhata.dao.BusinessDao;
import com.smartkhata.dao.SqliteBusinessDao;
import com.smartkhata.model.entity.Business;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConstraintsAndIdempotencyIntegrationTest {

    private Path tempDbPath;
    private long businessId;
    private final BusinessDao businessDao = new SqliteBusinessDao();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        tempDbPath = tempDir.resolve("constraints_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();

        try (Connection conn = DatabaseManager.getConnection()) {
            Business business = businessDao.save(conn, new Business(
                    null, "Constraint Store", null, null, null, null, "BDT", null, null
            ));
            businessId = business.id();

            // Insert a default user
            conn.createStatement().execute("INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                    "VALUES (" + businessId + ", 'owner', 'Owner', 'hash', 'salt', 'OWNER')");
        }
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldEnforceForeignKeyConstraints() {
        // Attempt to insert a sale_item for a non-existent sale and non-existent product
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThatThrownBy(() -> {
                String sql = "INSERT INTO sale_items (sale_id, product_id, quantity, unit_price_paisha, line_total_paisha) VALUES (99999, 88888, 1, 100, 100)";
                conn.createStatement().execute(sql);
            }).isInstanceOf(SQLException.class);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldEnforceIdempotencyKeyUniqueness() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection()) {
            String insertSql = "INSERT INTO sales (business_id, invoice_number, total_amount_paisha, net_amount_paisha, " +
                    "payment_status, idempotency_key, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, 1)";

            // First insertion succeeds
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, businessId);
                ps.setString(2, "INV-IDEMP-01");
                ps.setLong(3, 1000L);
                ps.setLong(4, 1000L);
                ps.setString(5, "PAID");
                ps.setString(6, "unique-token-abc-123");
                ps.executeUpdate();
            }

            // Second insertion with identical idempotency_key must fail
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setLong(1, businessId);
                    ps.setString(2, "INV-IDEMP-02");
                    ps.setLong(3, 1000L);
                    ps.setLong(4, 1000L);
                    ps.setString(5, "PAID");
                    ps.setString(6, "unique-token-abc-123"); // duplicate idempotency token!
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void shouldEnforceCheckConstraintsOnFinancialValues() {
        // Attempt to insert product with negative price
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThatThrownBy(() -> {
                String sql = "INSERT INTO products (business_id, name, selling_price_paisha) VALUES (" + businessId + ", 'Bad Product', -500)";
                conn.createStatement().execute(sql);
            }).isInstanceOf(SQLException.class);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}


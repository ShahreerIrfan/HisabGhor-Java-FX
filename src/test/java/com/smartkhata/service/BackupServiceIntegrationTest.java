package com.smartkhata.service;

import com.smartkhata.app.AppConfig;
import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.BackupService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private BackupService backupService;
    private SqliteCustomerService customerService;
    private SqliteInventoryService inventoryService;
    private SqliteSaleService saleService;

    private Business biz1;
    private User owner1;
    private User cashier1;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("smartkhata.home", tempDir.resolve("home").toString());
        AppConfig.initializeDirectories();

        Path dbPath = tempDir.resolve("active_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();

        backupService = new SqliteBackupService();
        customerService = new SqliteCustomerService();
        inventoryService = new SqliteInventoryService();
        saleService = new SqliteSaleService();

        try (Connection conn = DatabaseManager.getConnection()) {
            BusinessDao businessDao = new SqliteBusinessDao();
            UserDao userDao = new SqliteUserDao();

            biz1 = businessDao.save(conn, new Business(
                    null, "Jamuna Electronics", "Jamuna Electronics Ltd", "01755667788", "Dhaka", "TRAD-7788", "BDT", null, null
            ));
            owner1 = userDao.save(conn, new User(
                    null, biz1.id(), "jamuna_owner", "Jamuna Owner", "hash", "salt", UserRole.OWNER, true, null, null, null
            ));
            cashier1 = userDao.save(conn, new User(
                    null, biz1.id(), "jamuna_cashier", "Jamuna Staff", "hash", "salt", UserRole.CASHIER, true, null, null, null
            ));
        }

        SessionContext.setSession(biz1, owner1);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreateCoherentBackupWithVacuumIntoAndManifest() {
        // Arrange: Add sample customer and product
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Tanvir Ahmed", "01711002233", "Gulshan", Money.ZERO, Money.ofBdt(500.00), "Regular"
        ));
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "LED Light 15W", "8902223334445", "LED-15W", "pcs",
                Money.ofBdt(120.00), Money.ofBdt(200.00), 5, 20, null
        ));

        // Act: Create backup package
        Path backupPath = tempDir.resolve("jamuna_backup.skbak");
        BackupMetadataDto meta = backupService.createBackup(backupPath, "Initial verified backup");

        // Assert
        assertThat(Files.exists(backupPath)).isTrue();
        assertThat(meta.fileName()).isEqualTo("jamuna_backup.skbak");
        assertThat(meta.schemaVersion()).isEqualTo(7);
        assertThat(meta.integrityPassed()).isTrue();
        assertThat(meta.sha256()).isNotEmpty();
        assertThat(meta.customerCount()).isEqualTo(1);
        assertThat(meta.productCount()).isEqualTo(1);
        assertThat(meta.businessName()).isEqualTo("Jamuna Electronics");
        assertThat(meta.fileSizeBytes()).isGreaterThan(0);
    }

    @Test
    void shouldValidateValidBackupArchive() {
        // Arrange: Create backup
        Path backupPath = tempDir.resolve("valid_store.skbak");
        backupService.createBackup(backupPath, "Validation test");

        // Act
        RestoreValidationResult val = backupService.validateBackup(backupPath);

        // Assert
        assertThat(val.isValid()).isTrue();
        assertThat(val.errorMessage()).isNull();
        assertThat(val.metadata()).isNotNull();
        assertThat(val.metadata().businessName()).isEqualTo("Jamuna Electronics");
        assertThat(val.stagedDatabasePath()).isNotNull();
        assertThat(Files.exists(val.stagedDatabasePath())).isTrue();
    }

    @Test
    void shouldRejectCorruptOrInvalidBackupFiles() throws IOException {
        // 1. Non-existent file
        Path nonExistent = tempDir.resolve("does_not_exist.skbak");
        RestoreValidationResult res1 = backupService.validateBackup(nonExistent);
        assertThat(res1.isValid()).isFalse();
        assertThat(res1.errorMessage()).contains("does not exist");

        // 2. Empty 0-byte file
        Path emptyFile = tempDir.resolve("empty.skbak");
        Files.createFile(emptyFile);
        RestoreValidationResult res2 = backupService.validateBackup(emptyFile);
        assertThat(res2.isValid()).isFalse();
        assertThat(res2.errorMessage()).contains("empty");

        // 3. Corrupt file with invalid non-SQLite content
        Path corruptFile = tempDir.resolve("corrupt.db");
        Files.writeString(corruptFile, "RANDOM_GARBAGE_PAYLOAD_NOT_SQLITE");
        RestoreValidationResult res3 = backupService.validateBackup(corruptFile);
        assertThat(res3.isValid()).isFalse();
        assertThat(res3.errorMessage()).contains("missing valid SQLite database header");
    }

    @Test
    void shouldRejectIncompatibleSchemaVersion() throws Exception {
        // Create an incompatible SQLite database with schema user_version = 99
        Path futureDb = tempDir.resolve("future_v99.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + futureDb.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version = 99;");
            stmt.execute("CREATE TABLE businesses (id INTEGER PRIMARY KEY, name TEXT);");
        }

        RestoreValidationResult val = backupService.validateBackup(futureDb);
        assertThat(val.isValid()).isFalse();
        assertThat(val.errorMessage()).contains("Incompatible database schema version 99");
    }

    @Test
    void shouldRestoreDatabaseAndMatchAllOriginalEntities() throws Exception {
        // 1. In original database: record customer, product, and credit sale
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Mahmudul Hasan", "01811223344", "Uttara", Money.ZERO, Money.ofBdt(200.00), "VIP"
        ));
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Ceiling Fan 56-inch", "8903334445556", "FAN-56", "pcs",
                Money.ofBdt(1800.00), Money.ofBdt(2500.00), 2, 10, null
        ));

        // Sale: 2 fans @ BDT 2,500 = BDT 5,000. Paid BDT 1,000, Due BDT 4,000
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 2, Money.ofBdt(2500.00), Money.ofBdt(1800.00), Money.ZERO, Money.ofBdt(5000.00))),
                Money.ZERO, Money.ofBdt(1000.00), "CASH", "INV-KEY-RESTORE-1", "Restoration test sale", owner1.id()
        ));

        // 2. Take backup
        Path snapshotFile = tempDir.resolve("original_snapshot.skbak");
        BackupMetadataDto meta = backupService.createBackup(snapshotFile, "Full snapshot before corruption");
        assertThat(meta.customerCount()).isEqualTo(1);
        assertThat(meta.saleCount()).isEqualTo(1);

        // 3. Mutate active database by deleting customers and inserting a dummy business
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM sales;");
            stmt.executeUpdate("DELETE FROM customer_ledger;");
            stmt.executeUpdate("DELETE FROM customers;");
            stmt.executeUpdate("UPDATE businesses SET name = 'Destroyed Store' WHERE id = " + biz1.id());
        }

        // Verify active database is currently mutated
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers WHERE business_id = " + biz1.id())) {
            assertThat(rs.getLong(1)).isEqualTo(0);
        }

        // 4. Restore from snapshot with safety backup enabled
        backupService.restoreBackup(snapshotFile, true);

        // 5. Verify restored active database has exact original state
        try (Connection conn = DatabaseManager.getConnection()) {
            // Business restored
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM businesses WHERE id = ?")) {
                ps.setLong(1, biz1.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Jamuna Electronics");
                }
            }

            // Customer restored
            try (PreparedStatement ps = conn.prepareStatement("SELECT name, phone FROM customers WHERE id = ?")) {
                ps.setLong(1, cust.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Mahmudul Hasan");
                }
            }

            // Sale & Ledger restored
            try (PreparedStatement ps = conn.prepareStatement("SELECT net_amount_paisha, due_amount_paisha FROM sales WHERE customer_id = ?")) {
                ps.setLong(1, cust.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("net_amount_paisha")).isEqualTo(500000L); // BDT 5,000
                    assertThat(rs.getLong("due_amount_paisha")).isEqualTo(400000L); // BDT 4,000
                }
            }
        }

        // 6. Verify pre-restore safety backup was created in backup directory
        assertThat(Files.exists(AppConfig.getBackupDir())).isTrue();
        List<BackupMetadataDto> available = backupService.listAvailableBackups();
        assertThat(available).isNotEmpty();
    }

    @Test
    void shouldPreserveCurrentDataWhenRestoringCorruptBackupFails() throws Exception {
        // Arrange: Populate active DB with safe customer
        Customer safeCust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Safe Customer", "01733445566", "Banani", Money.ZERO, Money.ofBdt(100.00), null
        ));

        // Create corrupt backup file
        Path corruptFile = tempDir.resolve("bad_backup.skbak");
        Files.writeString(corruptFile, "CORRUPT_NOT_A_VALID_ZIP_OR_SQLITE", StandardCharsets.UTF_8);

        // Act & Assert: Restoring must throw and abort
        assertThatThrownBy(() -> backupService.restoreBackup(corruptFile, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot restore database from invalid backup");

        // Verify active data is 100% intact with zero data loss!
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM customers WHERE id = ?")) {
            ps.setLong(1, safeCust.id());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Safe Customer");
            }
        }
    }

    @Test
    void shouldEnforceOwnerOnlyForBackupAndRestore() {
        // Switch session to CASHIER
        SessionContext.setSession(biz1, cashier1);

        Path dummyPath = tempDir.resolve("test.skbak");

        assertThatThrownBy(() -> backupService.createBackup(dummyPath, "test"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("requires OWNER role");

        assertThatThrownBy(() -> backupService.restoreBackup(dummyPath, false))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("requires OWNER role");
    }
}

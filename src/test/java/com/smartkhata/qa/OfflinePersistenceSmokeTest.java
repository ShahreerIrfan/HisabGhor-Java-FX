package com.smartkhata.qa;

import com.smartkhata.app.AppConfig;
import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test validating offline persistence lifecycle:
 * 1. Clean first launch creates local directories and smartkhata.db
 * 2. Onboarding, customer, product, credit sale, and payment collection persisted
 * 3. App shutdown and WAL checkpoint
 * 4. Relaunch verifies 100% data retention without data loss or reset
 */
class OfflinePersistenceSmokeTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
        System.clearProperty("smartkhata.home");
    }

    @Test
    void shouldPersistDataAcrossCleanInstallRestartAndRelaunch() throws Exception {
        Path homePath = tempDir.resolve("SmartKhataInstall");
        System.setProperty("smartkhata.home", homePath.toAbsolutePath().toString());

        // ----------------------------------------------------
        // PHASE 1: Clean First Launch
        // ----------------------------------------------------
        AppConfig.initializeDirectories();
        Path dbFile = AppConfig.getDatabasePath();
        assertThat(Files.exists(dbFile)).isFalse();

        // Initialize SQLite persistence layer
        DatabaseManager.resetCustomJdbcUrl(); // Points to default AppConfig.getDatabasePath()
        DatabaseManager.initializeDatabase();
        assertThat(Files.exists(dbFile)).isTrue();
        assertThat(Files.size(dbFile)).isGreaterThan(0);

        // Instantiate services
        SqliteCustomerService customerService = new SqliteCustomerService();
        SqliteInventoryService inventoryService = new SqliteInventoryService();
        SqliteSaleService saleService = new SqliteSaleService();
        SqlitePaymentService paymentService = new SqlitePaymentService();

        // 1. Onboard Business and Owner
        Business business;
        User owner;
        try (Connection conn = DatabaseManager.getConnection()) {
            BusinessDao bDao = new SqliteBusinessDao();
            UserDao uDao = new SqliteUserDao();

            business = bDao.save(conn, new Business(
                    null, "Maa Bastralaya", "Maa Bastralaya Ltd", "01711223344",
                    "New Market, Dhaka", "TRAD-7788", "BDT", null, null
            ));
            owner = uDao.save(conn, new User(
                    null, business.id(), "al_amin", "Al-Amin Chowdhury",
                    "hashed_pw", "salt", UserRole.OWNER, true, null, null, null
            ));
        }

        SessionContext.setSession(business, owner);

        // 2. Add Customer
        Customer customer = customerService.createCustomer(new CustomerCreateDto(
                business.id(), "Rahim Chowdhury", "01711002233", "Elephant Road", Money.ZERO, Money.ZERO, "VIP Client"
        ));

        // 3. Add Product with opening stock of 10
        Product product = inventoryService.createProduct(new ProductCreateDto(
                business.id(), null, "Cotton Saree Premium", "8901234567890", "SAR-01", "pcs",
                Money.ofBdt(800.00), Money.ofBdt(1200.00), 2, 10, null
        ));

        // 4. Create Credit Sale: 2 units (BDT 2,400), Paid BDT 1,000, Due BDT 1,400
        Sale sale = saleService.createSale(new SaleCreateDto(
                business.id(), customer.id(),
                List.of(new SaleItemDto(product.id(), product.name(), 2, Money.ofBdt(1200.00), Money.ofBdt(800.00), Money.ZERO, Money.ofBdt(2400.00))),
                Money.ZERO, Money.ofBdt(1000.00), "CASH", "IDEM-SMOKE-S1", "Smoke sale", owner.id()
        ));

        assertThat(sale.netAmount()).isEqualTo(Money.ofBdt(2400.00));
        assertThat(sale.paidAmount()).isEqualTo(Money.ofBdt(1000.00));
        assertThat(sale.dueAmount()).isEqualTo(Money.ofBdt(1400.00));

        // 5. Receive Partial Due Payment: BDT 400 -> Due becomes BDT 1,000
        paymentService.receiveCustomerPayment(new PaymentCreateDto(
                business.id(), "CUSTOMER", customer.id(), Money.ofBdt(400.00),
                "CASH", "TRX-SMOKE-P1", "Partial collection", "IDEM-SMOKE-P1", owner.id()
        ));

        // Verify state before shutdown
        try (Connection conn = DatabaseManager.getConnection()) {
            ProductDao pDao = new SqliteProductDao();
            CustomerLedgerDao clDao = new SqliteCustomerLedgerDao();
            assertThat(pDao.getCurrentStock(conn, product.id())).isEqualTo(8);
            assertThat(clDao.getCurrentBalance(conn, customer.id())).isEqualTo(Money.ofBdt(1000.00));
        }

        // ----------------------------------------------------
        // PHASE 2: Application Shutdown
        // ----------------------------------------------------
        DatabaseManager.checkpointWal();
        SessionContext.clearSession();

        // ----------------------------------------------------
        // PHASE 3: Application Relaunch / Upgrade Simulation
        // ----------------------------------------------------
        // Re-initialize directories and database manager from existing files
        AppConfig.initializeDirectories();
        DatabaseManager.initializeDatabase(); // Must not wipe or corrupt existing data

        try (Connection conn = DatabaseManager.getConnection()) {
            BusinessDao bDao = new SqliteBusinessDao();
            ProductDao pDao = new SqliteProductDao();
            CustomerDao cDao = new SqliteCustomerDao();
            CustomerLedgerDao clDao = new SqliteCustomerLedgerDao();
            SaleDao sDao = new SqliteSaleDao();

            // Verify Business retained
            Business loadedBiz = bDao.findById(conn, business.id()).orElseThrow();
            assertThat(loadedBiz.name()).isEqualTo("Maa Bastralaya");

            // Verify Customer retained
            Customer loadedCustomer = cDao.findById(conn, customer.id()).orElseThrow();
            assertThat(loadedCustomer.name()).isEqualTo("Rahim Chowdhury");

            // Verify Product & Decremented Stock retained (8 units)
            Product loadedProduct = pDao.findById(conn, product.id()).orElseThrow();
            assertThat(loadedProduct.name()).isEqualTo("Cotton Saree Premium");
            assertThat(pDao.getCurrentStock(conn, product.id())).isEqualTo(8);

            // Verify Customer Outstanding Due retained (BDT 1,000)
            assertThat(clDao.getCurrentBalance(conn, customer.id())).isEqualTo(Money.ofBdt(1000.00));

            // Verify Sale record retained and reflects payment allocation
            Sale loadedSale = sDao.findById(conn, sale.id()).orElseThrow();
            assertThat(loadedSale.netAmount()).isEqualTo(Money.ofBdt(2400.00));
            assertThat(loadedSale.paidAmount()).isEqualTo(Money.ofBdt(1400.00));
            assertThat(loadedSale.dueAmount()).isEqualTo(Money.ofBdt(1000.00));
        }
    }
}

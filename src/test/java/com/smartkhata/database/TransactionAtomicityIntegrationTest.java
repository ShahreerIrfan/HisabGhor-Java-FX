package com.smartkhata.database;

import com.smartkhata.dao.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.vo.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionAtomicityIntegrationTest {

    private Path tempDbPath;
    private long businessId;
    private long customerId;
    private long productId;

    private final BusinessDao businessDao = new SqliteBusinessDao();
    private final CustomerDao customerDao = new SqliteCustomerDao();
    private final ProductDao productDao = new SqliteProductDao();
    private final SaleDao saleDao = new SqliteSaleDao();
    private final StockMovementDao stockDao = new SqliteStockMovementDao();
    private final CustomerLedgerDao ledgerDao = new SqliteCustomerLedgerDao();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        tempDbPath = tempDir.resolve("transaction_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();

        // Seed base store, customer, product, user
        try (Connection conn = DatabaseManager.getConnection()) {
            Business business = businessDao.save(conn, new Business(
                    null, "Test Shop", null, "01700000000", null, null, "BDT", null, null
            ));
            businessId = business.id();

            // Insert a default user for foreign keys
            conn.createStatement().execute("INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                    "VALUES (" + businessId + ", 'owner', 'Owner', 'hash', 'salt', 'OWNER')");

            Customer customer = customerDao.save(conn, new Customer(
                    null, businessId, "Karim Enterprise", "01800000000", null,
                    Money.ofBdt(100000), Money.ZERO, false, null, null, null
            ));
            customerId = customer.id();

            Product product = productDao.save(conn, new Product(
                    null, businessId, null, "Aci Detergent (1 kg)", null, null,
                    "pcs", Money.ofBdt(110), Money.ofBdt(130), 5, false, null, null
            ));
            productId = product.id();

            // Opening stock of 100 pcs
            stockDao.save(conn, new StockMovement(
                    null, businessId, productId, "OPENING", 100,
                    Money.ofBdt(110), "MANUAL", null, "Initial stock", 1L, null
            ));
        }
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldRollBackAllModificationsWhenTransactionFails() throws SQLException {
        // Attempt an atomic sale that fails mid-way
        assertThatThrownBy(() -> TransactionManager.runInTransaction(conn -> {
            // 1. Create Sale Header
            Sale sale = saleDao.save(conn, new Sale(
                    null, businessId, "INV-ROLLBACK-01", customerId,
                    Money.ofBdt(260), Money.ZERO, Money.ofBdt(260), Money.ZERO,
                    Money.ofBdt(260), "BAKI", "idemp-rb-1", LocalDateTime.now(),
                    "Credit sale test", 1L, false, null, null, null, null
            ));

            // 2. Insert Sale Items
            saleDao.saveItems(conn, List.of(new SaleItem(
                    null, sale.id(), productId, 2, Money.ofBdt(130), Money.ofBdt(110), Money.ZERO, Money.ofBdt(260)
            )));

            // 3. Deduct Stock
            stockDao.save(conn, new StockMovement(
                    null, businessId, productId, "SALE_OUT", -2,
                    Money.ofBdt(110), "SALE", sale.id(), "Sale deduction", 1L, null
            ));

            // 4. Simulate a failure before committing ledger
            throw new IllegalStateException("Simulated mid-transaction failure");
        })).isInstanceOf(IllegalStateException.class);

        // Verify that nothing was persisted!
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(saleDao.findByInvoiceNumber(conn, "INV-ROLLBACK-01")).isEmpty();
            // Stock should still be exactly 100 pcs
            assertThat(productDao.getCurrentStock(conn, productId)).isEqualTo(100);
            // Customer ledger should have zero balance
            assertThat(ledgerDao.getCurrentBalance(conn, customerId)).isEqualTo(Money.ZERO);
        }
    }

    @Test
    void shouldCommitAllModificationsAtomicallyWhenTransactionSucceeds() throws Exception {
        TransactionManager.runInTransaction(conn -> {
            // 1. Create Sale Header
            Sale sale = saleDao.save(conn, new Sale(
                    null, businessId, "INV-COMMIT-01", customerId,
                    Money.ofBdt(260), Money.ZERO, Money.ofBdt(260), Money.ZERO,
                    Money.ofBdt(260), "BAKI", "idemp-commit-1", LocalDateTime.now(),
                    "Credit sale test", 1L, false, null, null, null, null
            ));

            // 2. Insert Sale Items
            saleDao.saveItems(conn, List.of(new SaleItem(
                    null, sale.id(), productId, 2, Money.ofBdt(130), Money.ofBdt(110), Money.ZERO, Money.ofBdt(260)
            )));

            // 3. Deduct Stock
            stockDao.save(conn, new StockMovement(
                    null, businessId, productId, "SALE_OUT", -2,
                    Money.ofBdt(110), "SALE", sale.id(), "Sale deduction", 1L, null
            ));

            // 4. Update Customer Ledger
            ledgerDao.save(conn, new CustomerLedgerEntry(
                    null, businessId, customerId, LocalDateTime.now(), "SALE_INVOICE",
                    sale.id(), Money.ofBdt(260), Money.ZERO, Money.ofBdt(260), "Credit sale INV-COMMIT-01", null
            ));
        });

        // Verify all 4 changes committed atomically
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(saleDao.findByInvoiceNumber(conn, "INV-COMMIT-01")).isPresent();
            // Stock decreased from 100 to 98
            assertThat(productDao.getCurrentStock(conn, productId)).isEqualTo(98);
            // Customer due balance is now exactly BDT 260
            assertThat(ledgerDao.getCurrentBalance(conn, customerId)).isEqualTo(Money.ofBdt(260));
        }
    }
}

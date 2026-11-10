package com.smartkhata.service;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.SaleService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteSaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for Sales and Customer Credit Transactions (বিক্রয় ও বাকি লেনদেন).
 * Verifies all-credit, partial-paid, and cash walk-in sales, walk-in credit rejection,
 * atomic stock deductions, transactional rollback on insufficient stock,
 * idempotency replay protection, auditable void/reversals with stock and ledger restoration,
 * cross-business multi-tenant isolation, and session authentication guards.
 */
class SaleServiceIntegrationTest {

    private Path tempDbPath;
    private SaleService saleService;
    private ProductDao productDao;
    private CustomerLedgerDao customerLedgerDao;
    private StockMovementDao stockMovementDao;

    private Business biz1;
    private User owner1;
    private Customer cust1;
    private Product prod1;
    private Product prod2;

    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("sales_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        saleService = new SqliteSaleService();
        productDao = new SqliteProductDao();
        customerLedgerDao = new SqliteCustomerLedgerDao();
        stockMovementDao = new SqliteStockMovementDao();

        // Seed 2 distinct businesses, users, customers, and inventory for testing
        TransactionManager.runInTransaction(conn -> {
            // Business 1
            long b1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Bismillah General Store', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b1Id = rs.getLong(1);
                }
            }
            biz1 = new Business(b1Id, "Bismillah General Store", null, null, null, null, "BDT", null, null);

            // Owner 1
            long u1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'owner_bismillah', 'Al-Amin Hossain', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u1Id = rs.getLong(1);
                }
            }
            owner1 = new User(u1Id, b1Id, "owner_bismillah", "Al-Amin Hossain", "dummy", "dummy", UserRole.OWNER, true, null, null, null);

            // Customer 1 in Business 1
            long c1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO customers (business_id, name, phone, address) VALUES (?, 'Kamal Ahmed', '01711223344', 'Dhanmondi, Dhaka')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    c1Id = rs.getLong(1);
                }
            }
            cust1 = new Customer(c1Id, b1Id, "Kamal Ahmed", "01711223344", "Dhanmondi, Dhaka", Money.ZERO, Money.ZERO, false, null, null, null);

            // Product 1 in Business 1: 50 units in stock @ BDT 200 selling, BDT 150 cost
            long p1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (business_id, name, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold) " +
                            "VALUES (?, 'Miniket Rice 25kg', 'RICE-01', 'bag', 15000, 20000, 5)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    p1Id = rs.getLong(1);
                }
            }
            prod1 = new Product(p1Id, b1Id, null, "Miniket Rice 25kg", "RICE-01", null, "bag",
                    Money.ofBdt(150), Money.ofBdt(200), 5, true, null, null);

            // Product 1 initial stock movement: +50
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                            "VALUES (?, ?, 'OPENING', 50, 15000, 'MANUAL', 'Initial opening stock', ?)")) {
                ps.setLong(1, b1Id);
                ps.setLong(2, p1Id);
                ps.setLong(3, u1Id);
                ps.executeUpdate();
            }

            // Product 2 in Business 1: 10 units in stock @ BDT 500 selling, BDT 400 cost
            long p2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (business_id, name, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold) " +
                            "VALUES (?, 'Mustard Oil 5L', 'OIL-05', 'can', 40000, 50000, 3)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    p2Id = rs.getLong(1);
                }
            }
            prod2 = new Product(p2Id, b1Id, null, "Mustard Oil 5L", "OIL-05", null, "can",
                    Money.ofBdt(400), Money.ofBdt(500), 3, true, null, null);

            // Product 2 initial stock movement: +10
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                            "VALUES (?, ?, 'OPENING', 10, 40000, 'MANUAL', 'Initial opening stock', ?)")) {
                ps.setLong(1, b1Id);
                ps.setLong(2, p2Id);
                ps.setLong(3, u1Id);
                ps.executeUpdate();
            }

            // Business 2 for Multi-Tenant Isolation Tests
            long b2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Chittagong Traders', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b2Id = rs.getLong(1);
                }
            }
            biz2 = new Business(b2Id, "Chittagong Traders", null, null, null, null, "BDT", null, null);

            // Owner 2
            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'owner_ctg', 'Faruk Ahmed', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "owner_ctg", "Faruk Ahmed", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreateAllCreditSaleAndIncreaseCustomerDueByFullAmount() throws SQLException {
        // Arrange
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 5, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(1000));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Baki sale 5 bags", "IDEM-CREDIT-01", owner1.id()
        );

        // Act
        Sale sale = saleService.createSale(dto);

        // Assert
        assertThat(sale.id()).isNotNull();
        assertThat(sale.invoiceNumber()).startsWith("INV-");
        assertThat(sale.totalAmount()).isEqualTo(Money.ofBdt(1000));
        assertThat(sale.netAmount()).isEqualTo(Money.ofBdt(1000));
        assertThat(sale.paidAmount()).isEqualTo(Money.ZERO);
        assertThat(sale.dueAmount()).isEqualTo(Money.ofBdt(1000));
        assertThat(sale.paymentStatus()).isEqualTo("BAKI");

        // Verify Customer Ledger balance is BDT 1,000
        try (Connection conn = DatabaseManager.getConnection()) {
            Money balance = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(balance).isEqualTo(Money.ofBdt(1000));

            List<CustomerLedgerEntry> entries = customerLedgerDao.findByCustomerId(conn, cust1.id());
            assertThat(entries).hasSize(1);
            CustomerLedgerEntry e = entries.get(0);
            assertThat(e.entryType()).isEqualTo("SALE_INVOICE");
            assertThat(e.debit()).isEqualTo(Money.ofBdt(1000));
            assertThat(e.credit()).isEqualTo(Money.ZERO);
            assertThat(e.runningBalance()).isEqualTo(Money.ofBdt(1000));

            // Verify stock decreased from 50 to 45
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(45);

            List<StockMovement> movements = stockMovementDao.findByProductId(conn, prod1.id());
            // Opening + Sale_out = 2
            assertThat(movements).hasSize(2);
            StockMovement saleOut = movements.stream().filter(m -> "SALE_OUT".equals(m.movementType())).findFirst().orElseThrow();
            assertThat(saleOut.quantity()).isEqualTo(-5);
        }
    }

    @Test
    void shouldCreatePartialPaidSaleAndIncreaseCustomerDueByUnpaidDifference() throws SQLException {
        // Arrange: 5 units @ BDT 200 = BDT 1,000. Customer pays BDT 400 upfront -> BDT 600 Due.
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 5, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(1000));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(400),
                "CASH", "Partial payment sale", "IDEM-PARTIAL-01", owner1.id()
        );

        // Act
        Sale sale = saleService.createSale(dto);

        // Assert
        assertThat(sale.paymentStatus()).isEqualTo("PARTIAL");
        assertThat(sale.netAmount()).isEqualTo(Money.ofBdt(1000));
        assertThat(sale.paidAmount()).isEqualTo(Money.ofBdt(400));
        assertThat(sale.dueAmount()).isEqualTo(Money.ofBdt(600));

        try (Connection conn = DatabaseManager.getConnection()) {
            Money balance = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(balance).isEqualTo(Money.ofBdt(600));

            // Should have 2 ledger entries: 1 invoice DEBIT 1000, 1 payment collection CREDIT 400
            List<CustomerLedgerEntry> entries = customerLedgerDao.findByCustomerId(conn, cust1.id());
            assertThat(entries).hasSize(2);

            CustomerLedgerEntry invoiceEntry = entries.get(0);
            assertThat(invoiceEntry.entryType()).isEqualTo("SALE_INVOICE");
            assertThat(invoiceEntry.debit()).isEqualTo(Money.ofBdt(1000));
            assertThat(invoiceEntry.runningBalance()).isEqualTo(Money.ofBdt(1000));

            CustomerLedgerEntry paymentEntry = entries.get(1);
            assertThat(paymentEntry.entryType()).isEqualTo("PAYMENT_COLLECTION");
            assertThat(paymentEntry.credit()).isEqualTo(Money.ofBdt(400));
            assertThat(paymentEntry.runningBalance()).isEqualTo(Money.ofBdt(600));

            // Payments record created
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM payments WHERE party_id = ?")) {
                ps.setLong(1, cust1.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("amount_paisha")).isEqualTo(40000);
                }
            }

            // Stock decreased by 5
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(45);
        }
    }

    @Test
    void shouldCreateFullyPaidWalkInSaleWithoutCustomerAccount() throws SQLException {
        // Arrange: Walk-in customer (customerId == null), 2 cans of oil @ 500 = BDT 1,000 fully paid
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod2.id(), prod2.name(), 2, Money.ofBdt(500), prod2.costPrice(), Money.ZERO, Money.ofBdt(1000));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), null, List.of(item), Money.ZERO, Money.ofBdt(1000),
                "BKASH", "Walk-in cash counter sale", "IDEM-WALKIN-01", owner1.id()
        );

        // Act
        Sale sale = saleService.createSale(dto);

        // Assert
        assertThat(sale.customerId()).isNull();
        assertThat(sale.paymentStatus()).isEqualTo("PAID");
        assertThat(sale.dueAmount()).isEqualTo(Money.ZERO);

        try (Connection conn = DatabaseManager.getConnection()) {
            // No entries in customer_ledger
            List<CustomerLedgerEntry> entries = customerLedgerDao.findByCustomerId(conn, 0L);
            assertThat(entries).isEmpty();

            // Stock decreased from 10 to 8
            int currentStock = productDao.getCurrentStock(conn, prod2.id());
            assertThat(currentStock).isEqualTo(8);

            // Payment recorded with party_type CUSTOMER and party_id 0
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM payments WHERE business_id = ?")) {
                ps.setLong(1, biz1.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void shouldRejectWalkInCreditSaleWithoutCustomerAccount() throws SQLException {
        // Arrange: Walk-in customer trying to take credit (paid 300 out of 500)
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod2.id(), prod2.name(), 1, Money.ofBdt(500), prod2.costPrice(), Money.ZERO, Money.ofBdt(500));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), null, List.of(item), Money.ZERO, Money.ofBdt(300),
                "CASH", "Walk-in attempting baki", "IDEM-WALKIN-FAIL", owner1.id()
        );

        // Act & Assert
        assertThatThrownBy(() -> saleService.createSale(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credit sale (Baki) requires selecting a registered customer account");

        // Verify stock was NOT touched and no sales were inserted
        try (Connection conn = DatabaseManager.getConnection()) {
            int currentStock = productDao.getCurrentStock(conn, prod2.id());
            assertThat(currentStock).isEqualTo(10);

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sales")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void shouldDecreaseStockExactlyOncePerSale() throws SQLException {
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 7, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(1400));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(1400),
                "CASH", "Exact stock decrease test", null, owner1.id()
        );

        saleService.createSale(dto);

        try (Connection conn = DatabaseManager.getConnection()) {
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(43); // 50 - 7
        }
    }

    @Test
    void shouldRollbackEntireTransactionWhenStockIsInsufficient() throws SQLException {
        // Arrange: prod2 has only 10 in stock, attempt to sell 15
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod2.id(), prod2.name(), 15, Money.ofBdt(500), prod2.costPrice(), Money.ZERO, Money.ofBdt(7500));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(7500),
                "CASH", "Excess quantity attempt", null, owner1.id()
        );

        // Act & Assert
        assertThatThrownBy(() -> saleService.createSale(dto))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");

        // Verify zero side-effects across all tables
        try (Connection conn = DatabaseManager.getConnection()) {
            int currentStock = productDao.getCurrentStock(conn, prod2.id());
            assertThat(currentStock).isEqualTo(10); // Untouched

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sales")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payments")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }

            Money balance = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(balance).isEqualTo(Money.ZERO);
        }
    }

    @Test
    void shouldReturnExistingSaleWhenRetryingWithSameIdempotencyKey() throws SQLException {
        // Arrange: Sale with idempotency key
        SessionContext.setSession(biz1, owner1);
        String idempotencyKey = "POS-KEY-999";
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 3, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(600));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(600),
                "CASH", "Idempotent sale test", idempotencyKey, owner1.id()
        );

        // Act 1: Initial call
        Sale firstSale = saleService.createSale(dto);

        // Act 2: Retry with exact same idempotency key
        Sale retriedSale = saleService.createSale(dto);

        // Assert: Same sale returned, stock decremented ONLY ONCE
        assertThat(retriedSale.id()).isEqualTo(firstSale.id());
        assertThat(retriedSale.invoiceNumber()).isEqualTo(firstSale.invoiceNumber());

        try (Connection conn = DatabaseManager.getConnection()) {
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(47); // 50 - 3 (NOT 50 - 6)

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sales")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1); // Only 1 row persisted
            }
        }
    }

    @Test
    void shouldVoidSaleAndRestoreStockAndReverseCustomerDues() throws SQLException {
        // Arrange: Create credit sale for 4 units of prod1 (BDT 800)
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 4, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(800));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Sale to be voided", null, owner1.id()
        );
        Sale sale = saleService.createSale(dto);

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(productDao.getCurrentStock(conn, prod1.id())).isEqualTo(46);
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ofBdt(800));
        }

        // Act: Void sale
        saleService.voidSale(sale.id(), "Customer returned items due to wrong packaging", owner1.id());

        // Assert
        try (Connection conn = DatabaseManager.getConnection()) {
            // 1. Stock restored back to 50 via RETURN_IN
            int restoredStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(restoredStock).isEqualTo(50);

            List<StockMovement> movements = stockMovementDao.findByProductId(conn, prod1.id());
            StockMovement returnIn = movements.stream().filter(m -> "RETURN_IN".equals(m.movementType())).findFirst().orElseThrow();
            assertThat(returnIn.quantity()).isEqualTo(4);

            // 2. Customer ledger balance restored to 0 via INVOICE_REVERSAL
            Money newBalance = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(newBalance).isEqualTo(Money.ZERO);

            List<CustomerLedgerEntry> entries = customerLedgerDao.findByCustomerId(conn, cust1.id());
            assertThat(entries).hasSize(2);
            CustomerLedgerEntry reversal = entries.get(1);
            assertThat(reversal.entryType()).isEqualTo("INVOICE_REVERSAL");
            assertThat(reversal.credit()).isEqualTo(Money.ofBdt(800));
            assertThat(reversal.runningBalance()).isEqualTo(Money.ZERO);

            // 3. Sale is flagged as reversed
            Optional<SaleDetailDto> detailOpt = saleService.getSaleDetail(sale.id());
            assertThat(detailOpt).isPresent();
            assertThat(detailOpt.get().sale().isReversed()).isTrue();
            assertThat(detailOpt.get().sale().reversalReason()).contains("Customer returned items");
        }

        // 4. Repeated void attempts are rejected
        assertThatThrownBy(() -> saleService.voidSale(sale.id(), "Second void attempt", owner1.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already voided/reversed");
    }

    @Test
    void shouldEnforceCrossBusinessIsolationForSales() {
        // Arrange: Sale created in Business 1
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 1, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(200));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(200),
                "CASH", "Biz 1 sale", null, owner1.id()
        );
        Sale sale1 = saleService.createSale(dto);

        // Switch session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Attempt to create sale for Business 1 while authenticated as Business 2
        assertThatThrownBy(() -> saleService.createSale(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Attempt to view Business 1 sale details from Business 2
        assertThatThrownBy(() -> saleService.getSaleDetail(sale1.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Attempt to void Business 1 sale from Business 2
        assertThatThrownBy(() -> saleService.voidSale(sale1.id(), "Malicious void", owner2.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Attempt to search Business 1 sales from Business 2
        assertThatThrownBy(() -> saleService.searchSales(biz1.id(), null, null, null, null, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");
    }

    @Test
    void shouldRejectSaleOperationsWhenUnauthenticatedOrLocked() {
        SessionContext.clearSession();

        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 1, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(200));
        SaleCreateDto dto = new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(200),
                "CASH", "Unauthenticated sale", null, 1L
        );

        // Unauthenticated
        assertThatThrownBy(() -> saleService.createSale(dto))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> saleService.getSaleDetail(1L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> saleService.voidSale(1L, "Reason", 1L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> saleService.searchSales(biz1.id(), null, null, null, null, null))
                .isInstanceOf(SecurityException.class);

        // Locked
        SessionContext.setSession(biz1, owner1);
        SessionContext.lock();

        assertThatThrownBy(() -> saleService.createSale(dto))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldTreatAllStatusesFilterLabelAsUnfilteredInSearchSales() {
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 1, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(200));
        Sale created = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ofBdt(100),
                "CASH", "all-status-key-1", "Partial invoice", owner1.id()
        ));

        List<SaleListItemDto> resultsAllLabel = saleService.searchSales(biz1.id(), null, null, "All Statuses", null, null);
        List<SaleListItemDto> resultsNull = saleService.searchSales(biz1.id(), null, null, null, null, null);

        assertThat(resultsAllLabel).extracting(SaleListItemDto::id).contains(created.id());
        assertThat(resultsAllLabel).hasSameSizeAs(resultsNull);
    }

    @Test
    void shouldUpdateSaleNotesAndAllowOwnerToDeleteOnlyVoidedSale() throws SQLException {
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 2, Money.ofBdt(200), prod1.costPrice(), Money.ZERO, Money.ofBdt(400));
        Sale created = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Initial note", "IDEM-SALE-CRUD-01", owner1.id()
        ));

        // 1. Update notes on active sale
        saleService.updateSaleNotes(created.id(), "Delivered at evening");
        SaleDetailDto updatedDetail = saleService.getSaleDetail(created.id()).orElseThrow();
        assertThat(updatedDetail.sale().notes()).isEqualTo("Delivered at evening");

        // 2. Active sale cannot be deleted
        assertThatThrownBy(() -> saleService.deleteVoidedSale(created.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Void this invoice first");

        // 3. Void then delete by Owner
        saleService.voidSale(created.id(), "Cancelled before delivery", owner1.id());
        saleService.deleteVoidedSale(created.id());

        assertThat(saleService.getSaleDetail(created.id())).isEmpty();
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(productDao.getCurrentStock(conn, prod1.id())).isEqualTo(50);
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ZERO);
        }
    }
}

package com.smartkhata.service;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.PurchaseService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqlitePurchaseService;
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
 * Integration test suite for Purchases and Supplier Credit Transactions (ক্রয় ও মহাজন দেনা).
 * Verifies partial-paid purchase arithmetic (e.g. BDT 750 with BDT 250 paid -> BDT 500 payable),
 * all-credit and fully-paid purchases, single stock receipt increments, cost price catalog updates,
 * atomic rollback on failure, idempotency replay protection, auditable void/reversal restoring stock
 * and reversing payables, negative stock reversal guards, multi-tenant isolation, and session authentication.
 */
class PurchaseServiceIntegrationTest {

    private Path tempDbPath;
    private PurchaseService purchaseService;
    private PurchaseDao purchaseDao;
    private ProductDao productDao;
    private SupplierLedgerDao supplierLedgerDao;
    private StockMovementDao stockMovementDao;

    private Business biz1;
    private User owner1;
    private Supplier supp1;
    private Product prod1;
    private Product prod2;

    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("purchases_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        purchaseService = new SqlitePurchaseService();
        purchaseDao = new SqlitePurchaseDao();
        productDao = new SqliteProductDao();
        supplierLedgerDao = new SqliteSupplierLedgerDao();
        stockMovementDao = new SqliteStockMovementDao();

        // Seed businesses, users, suppliers, and initial products
        TransactionManager.runInTransaction(conn -> {
            // Business 1
            long b1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Bismillah Enterprise', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b1Id = rs.getLong(1);
                }
            }
            biz1 = new Business(b1Id, "Bismillah Enterprise", null, null, null, null, "BDT", null, null);

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

            // Supplier 1 in Business 1
            long s1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO suppliers (business_id, name, phone, address) VALUES (?, 'Meghna Trade Center', '01911223344', 'Khatungonj, Chittagong')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    s1Id = rs.getLong(1);
                }
            }
            supp1 = new Supplier(s1Id, b1Id, "Meghna Trade Center", "01911223344", "Khatungonj, Chittagong", null, Money.ZERO, false, null, null, null);

            // Product 1 in Business 1: initial stock = 10 @ cost BDT 120, selling BDT 150
            long p1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (business_id, name, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold) " +
                            "VALUES (?, 'ACI Pure Salt 1kg', 'SALT-01', 'pkt', 12000, 15000, 5)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    p1Id = rs.getLong(1);
                }
            }
            prod1 = new Product(p1Id, b1Id, null, "ACI Pure Salt 1kg", "SALT-01", null, "pkt",
                    Money.ofBdt(120), Money.ofBdt(150), 5, true, null, null);

            // Product 1 initial stock movement: +10
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                            "VALUES (?, ?, 'OPENING', 10, 12000, 'MANUAL', 'Initial opening stock', ?)")) {
                ps.setLong(1, b1Id);
                ps.setLong(2, p1Id);
                ps.setLong(3, u1Id);
                ps.executeUpdate();
            }

            // Product 2 in Business 1: initial stock = 0 @ cost BDT 400, selling BDT 500
            long p2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (business_id, name, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold) " +
                            "VALUES (?, 'Teer Soybean Oil 5L', 'SOIL-05', 'can', 40000, 50000, 3)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    p2Id = rs.getLong(1);
                }
            }
            prod2 = new Product(p2Id, b1Id, null, "Teer Soybean Oil 5L", "SOIL-05", null, "can",
                    Money.ofBdt(400), Money.ofBdt(500), 3, true, null, null);

            // Business 2 for Multi-Tenant Isolation Tests
            long b2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Sylhet Wholesale Ltd', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b2Id = rs.getLong(1);
                }
            }
            biz2 = new Business(b2Id, "Sylhet Wholesale Ltd", null, null, null, null, "BDT", null, null);

            // Owner 2
            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'owner_sylhet', 'Mahmudul Hasan', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "owner_sylhet", "Mahmudul Hasan", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreatePartialPaidPurchaseAndIncreaseSupplierPayableByUnpaidDifference() throws SQLException {
        // Arrange: BDT 750 purchase (5 units @ BDT 150) with BDT 250 paid upfront -> BDT 500 payable in ledger
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 5, Money.ofBdt(150));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(250),
                "CASH", "Partial payment bill", "IDEM-PUR-PARTIAL", owner1.id()
        );

        // Act
        Purchase purchase = purchaseService.createPurchase(dto);

        // Assert
        assertThat(purchase.id()).isNotNull();
        assertThat(purchase.purchaseNumber()).startsWith("PUR-");
        assertThat(purchase.totalAmount()).isEqualTo(Money.ofBdt(750));
        assertThat(purchase.netAmount()).isEqualTo(Money.ofBdt(750));
        assertThat(purchase.paidAmount()).isEqualTo(Money.ofBdt(250));
        assertThat(purchase.dueAmount()).isEqualTo(Money.ofBdt(500));
        assertThat(purchase.paymentStatus()).isEqualTo("PARTIAL");

        try (Connection conn = DatabaseManager.getConnection()) {
            // Verify Supplier Payable balance is BDT 500
            Money currentPayable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(currentPayable).isEqualTo(Money.ofBdt(500));

            // Verify Supplier Ledger entries: 1 bill credit 750, 1 payment debit 250
            List<SupplierLedgerEntry> entries = supplierLedgerDao.findBySupplierId(conn, supp1.id());
            assertThat(entries).hasSize(2);

            SupplierLedgerEntry billEntry = entries.get(0);
            assertThat(billEntry.entryType()).isEqualTo("PURCHASE_BILL");
            assertThat(billEntry.credit()).isEqualTo(Money.ofBdt(750));
            assertThat(billEntry.debit()).isEqualTo(Money.ZERO);
            assertThat(billEntry.runningBalance()).isEqualTo(Money.ofBdt(750));

            SupplierLedgerEntry payEntry = entries.get(1);
            assertThat(payEntry.entryType()).isEqualTo("PAYMENT_DISBURSEMENT");
            assertThat(payEntry.credit()).isEqualTo(Money.ZERO);
            assertThat(payEntry.debit()).isEqualTo(Money.ofBdt(250));
            assertThat(payEntry.runningBalance()).isEqualTo(Money.ofBdt(500));

            // Verify Payments table has supplier payment record for BDT 250
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM payments WHERE party_type = 'SUPPLIER' AND party_id = ?")) {
                ps.setLong(1, supp1.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("amount_paisha")).isEqualTo(25000);
                }
            }

            // Verify Stock increased from 10 to 15
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(15);

            List<StockMovement> movements = stockMovementDao.findByProductId(conn, prod1.id());
            assertThat(movements).hasSize(2);
            StockMovement purIn = movements.stream().filter(m -> "PURCHASE_IN".equals(m.movementType())).findFirst().orElseThrow();
            assertThat(purIn.quantity()).isEqualTo(5);
            assertThat(purIn.unitCost()).isEqualTo(Money.ofBdt(150));
        }
    }

    @Test
    void shouldCreateAllCreditPurchaseAndIncreaseSupplierPayableByFullAmount() throws SQLException {
        // Arrange: 10 units of prod2 @ BDT 400 = BDT 4,000 all credit (paid = 0)
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod2.id(), prod2.name(), 10, Money.ofBdt(400));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Full credit bill", null, owner1.id()
        );

        // Act
        Purchase purchase = purchaseService.createPurchase(dto);

        // Assert
        assertThat(purchase.paymentStatus()).isEqualTo("DUE");
        assertThat(purchase.netAmount()).isEqualTo(Money.ofBdt(4000));
        assertThat(purchase.paidAmount()).isEqualTo(Money.ZERO);
        assertThat(purchase.dueAmount()).isEqualTo(Money.ofBdt(4000));

        try (Connection conn = DatabaseManager.getConnection()) {
            Money payable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(payable).isEqualTo(Money.ofBdt(4000));

            List<SupplierLedgerEntry> entries = supplierLedgerDao.findBySupplierId(conn, supp1.id());
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).entryType()).isEqualTo("PURCHASE_BILL");
            assertThat(entries.get(0).credit()).isEqualTo(Money.ofBdt(4000));

            // Stock increased from 0 to 10
            int currentStock = productDao.getCurrentStock(conn, prod2.id());
            assertThat(currentStock).isEqualTo(10);
        }
    }

    @Test
    void shouldCreateFullyPaidPurchaseWithoutSupplierDebt() throws SQLException {
        // Arrange: 2 units @ BDT 500 = BDT 1,000 fully paid
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod2.id(), prod2.name(), 2, Money.ofBdt(500));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(1000),
                "BANK", "Fully paid cash/bank purchase", null, owner1.id()
        );

        // Act
        Purchase purchase = purchaseService.createPurchase(dto);

        // Assert
        assertThat(purchase.paymentStatus()).isEqualTo("PAID");
        assertThat(purchase.dueAmount()).isEqualTo(Money.ZERO);

        try (Connection conn = DatabaseManager.getConnection()) {
            Money payable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(payable).isEqualTo(Money.ZERO);

            // Stock increased by 2
            int currentStock = productDao.getCurrentStock(conn, prod2.id());
            assertThat(currentStock).isEqualTo(2);
        }
    }

    @Test
    void shouldIncreaseStockExactlyOncePerPurchase() throws SQLException {
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 7, Money.ofBdt(130));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(910),
                "CASH", "Exact stock increment test", null, owner1.id()
        );

        purchaseService.createPurchase(dto);

        try (Connection conn = DatabaseManager.getConnection()) {
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(17); // 10 initial + 7
        }
    }

    @Test
    void shouldUpdateProductCatalogCostPriceAndInventoryValuation() throws SQLException {
        // Arrange: Initial cost was BDT 120, stock 10. Purchase 10 at new cost BDT 140.
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 10, Money.ofBdt(140));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(1400),
                "CASH", "New acquisition cost test", null, owner1.id()
        );

        purchaseService.createPurchase(dto);

        try (Connection conn = DatabaseManager.getConnection()) {
            // Product catalog cost price updated to BDT 140
            Product updatedProd = productDao.findById(conn, prod1.id()).orElseThrow();
            assertThat(updatedProd.costPrice()).isEqualTo(Money.ofBdt(140));

            // Valuation: 20 units * BDT 140 = BDT 2,800
            Money valuation = productDao.getTotalInventoryValuation(conn, biz1.id());
            assertThat(valuation).isEqualTo(Money.ofBdt(2800));
        }
    }

    @Test
    void shouldRollbackEntireTransactionWhenLineItemHasInvalidData() throws SQLException {
        // Arrange: Attempt purchase with non-existent product ID
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = new PurchaseItemDto(999999L, "Non-existent", 5, Money.ofBdt(100), Money.ofBdt(500));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(500),
                "CASH", "Failing purchase", null, owner1.id()
        );

        // Act & Assert
        assertThatThrownBy(() -> purchaseService.createPurchase(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product not found");

        // Verify zero side effects
        try (Connection conn = DatabaseManager.getConnection()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM purchases")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payments")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }

            Money payable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(payable).isEqualTo(Money.ZERO);
        }
    }

    @Test
    void shouldReturnExistingPurchaseWhenRetryingWithSameIdempotencyKey() throws SQLException {
        SessionContext.setSession(biz1, owner1);
        String idemKey = "PUR-IDEM-001";
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 4, Money.ofBdt(130));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(520),
                "CASH", "Idempotent purchase test", idemKey, owner1.id()
        );

        // Act 1: Initial call
        Purchase first = purchaseService.createPurchase(dto);

        // Act 2: Retry with same key
        Purchase second = purchaseService.createPurchase(dto);

        // Assert: Same purchase returned, stock incremented only once
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.purchaseNumber()).isEqualTo(first.purchaseNumber());

        try (Connection conn = DatabaseManager.getConnection()) {
            int currentStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(currentStock).isEqualTo(14); // 10 + 4 (NOT 10 + 8)

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM purchases")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void shouldVoidPurchaseAndRestoreStockAndReverseSupplierPayables() throws SQLException {
        // Arrange: Purchase 5 units on credit (BDT 600 due)
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 5, Money.ofBdt(120));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Bill to be voided", null, owner1.id()
        );
        Purchase p = purchaseService.createPurchase(dto);

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(productDao.getCurrentStock(conn, prod1.id())).isEqualTo(15); // 10 + 5
            assertThat(supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id())).isEqualTo(Money.ofBdt(600));
        }

        // Act: Void purchase
        purchaseService.voidPurchase(p.id(), "Supplier delivered wrong brand variant", owner1.id());

        // Assert
        try (Connection conn = DatabaseManager.getConnection()) {
            // 1. Stock restored back to 10 via RETURN_OUT
            int restoredStock = productDao.getCurrentStock(conn, prod1.id());
            assertThat(restoredStock).isEqualTo(10);

            List<StockMovement> movements = stockMovementDao.findByProductId(conn, prod1.id());
            StockMovement returnOut = movements.stream().filter(m -> "RETURN_OUT".equals(m.movementType())).findFirst().orElseThrow();
            assertThat(returnOut.quantity()).isEqualTo(-5);

            // 2. Supplier payable balance restored to 0 via BILL_REVERSAL
            Money newPayable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(newPayable).isEqualTo(Money.ZERO);

            List<SupplierLedgerEntry> entries = supplierLedgerDao.findBySupplierId(conn, supp1.id());
            assertThat(entries).hasSize(2);
            SupplierLedgerEntry reversal = entries.get(1);
            assertThat(reversal.entryType()).isEqualTo("BILL_REVERSAL");
            assertThat(reversal.debit()).isEqualTo(Money.ofBdt(600)); // Debit reduces liability
            assertThat(reversal.runningBalance()).isEqualTo(Money.ZERO);

            // 3. Purchase is flagged as reversed
            Optional<PurchaseDetailDto> detailOpt = purchaseService.getPurchaseDetail(p.id());
            assertThat(detailOpt).isPresent();
            assertThat(detailOpt.get().purchase().isReversed()).isTrue();
            assertThat(detailOpt.get().purchase().reversalReason()).contains("wrong brand variant");
        }

        // 4. Repeated void attempts are rejected
        assertThatThrownBy(() -> purchaseService.voidPurchase(p.id(), "Repeated void", owner1.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already voided/reversed");
    }

    @Test
    void shouldRejectPurchaseVoidWhenStockHasAlreadyBeenSoldAndWouldBecomeNegative() throws SQLException {
        // Arrange: prod2 has 0 initial stock. Purchase 5 units.
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod2.id(), prod2.name(), 5, Money.ofBdt(400));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Purchase with subsequent sale", null, owner1.id()
        );
        Purchase p = purchaseService.createPurchase(dto);

        // Simulate subsequent sales that consumed 4 of the 5 units
        TransactionManager.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                            "VALUES (?, ?, 'SALE_OUT', -4, 40000, 'SALE', 'Sold units', ?)")) {
                ps.setLong(1, biz1.id());
                ps.setLong(2, prod2.id());
                ps.setLong(3, owner1.id());
                ps.executeUpdate();
            }
        });

        // Current stock of prod2 is now 1. Attempting to void the 5-unit purchase would make stock -4!
        // Act & Assert
        assertThatThrownBy(() -> purchaseService.voidPurchase(p.id(), "Attempt void after sale", owner1.id()))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");

        // Verify purchase was NOT voided and stock remains 1
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(productDao.getCurrentStock(conn, prod2.id())).isEqualTo(1);
            Purchase current = purchaseDao.findById(conn, p.id()).orElseThrow();
            assertThat(current.isReversed()).isFalse();
        }
    }

    @Test
    void shouldEnforceCrossBusinessIsolationForPurchases() {
        // Arrange: Purchase created in Business 1
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 2, Money.ofBdt(120));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(240),
                "CASH", "Biz 1 purchase", null, owner1.id()
        );
        Purchase p1 = purchaseService.createPurchase(dto);

        // Switch session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Attempt to create purchase for Business 1
        assertThatThrownBy(() -> purchaseService.createPurchase(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Attempt to view Business 1 purchase from Business 2
        assertThatThrownBy(() -> purchaseService.getPurchaseDetail(p1.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Attempt to void Business 1 purchase from Business 2
        assertThatThrownBy(() -> purchaseService.voidPurchase(p1.id(), "Unauthorized void", owner2.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Attempt to search Business 1 purchases from Business 2
        assertThatThrownBy(() -> purchaseService.searchPurchases(biz1.id(), null, null, null, null, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");
    }

    @Test
    void shouldRejectPurchaseOperationsWhenUnauthenticatedOrLocked() {
        SessionContext.clearSession();

        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 1, Money.ofBdt(120));
        PurchaseCreateDto dto = new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ofBdt(120),
                "CASH", "Unauthenticated purchase", null, 1L
        );

        // Unauthenticated
        assertThatThrownBy(() -> purchaseService.createPurchase(dto))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> purchaseService.getPurchaseDetail(1L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> purchaseService.voidPurchase(1L, "Reason", 1L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> purchaseService.searchPurchases(biz1.id(), null, null, null, null, null))
                .isInstanceOf(SecurityException.class);

        // Locked
        SessionContext.setSession(biz1, owner1);
        SessionContext.lock();

        assertThatThrownBy(() -> purchaseService.createPurchase(dto))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldUpdatePurchaseNotesAndAllowOwnerToDeleteOnlyVoidedPurchase() throws SQLException {
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 3, Money.ofBdt(120));
        Purchase created = purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO,
                "CASH", "Initial challan note", "IDEM-PUR-CRUD-01", owner1.id()
        ));

        // 1. Update notes
        purchaseService.updatePurchaseNotes(created.id(), "Challan #77 verified");
        PurchaseDetailDto updated = purchaseService.getPurchaseDetail(created.id()).orElseThrow();
        assertThat(updated.purchase().notes()).isEqualTo("Challan #77 verified");

        // 2. Active purchase cannot be deleted
        assertThatThrownBy(() -> purchaseService.deleteVoidedPurchase(created.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Void this bill first");

        // 3. Void then delete by Owner
        purchaseService.voidPurchase(created.id(), "Returned damaged cartons", owner1.id());
        purchaseService.deleteVoidedPurchase(created.id());

        assertThat(purchaseService.getPurchaseDetail(created.id())).isEmpty();
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(productDao.getCurrentStock(conn, prod1.id())).isEqualTo(10);
            assertThat(supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id())).isEqualTo(Money.ZERO);
        }
    }
}

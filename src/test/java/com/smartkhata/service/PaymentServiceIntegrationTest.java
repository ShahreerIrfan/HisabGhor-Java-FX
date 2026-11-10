package com.smartkhata.service;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.PaymentService;
import com.smartkhata.service.api.PurchaseService;
import com.smartkhata.service.api.SaleService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqlitePaymentService;
import com.smartkhata.service.impl.SqlitePurchaseService;
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
 * Integration test suite for Customer Collection and Supplier Payments (দেনা-পাওনা আদায় ও পরিশোধ).
 * Verifies BDT 1,000 due then BDT 400 collection -> BDT 600 due;
 * supplier BDT 500 due then BDT 200 payment -> BDT 300 payable;
 * multi-invoice FIFO settlement, overpayment rejection, idempotency retry protection,
 * auditable payment voiding restoring invoices and ledgers, and multi-tenant security guards.
 */
class PaymentServiceIntegrationTest {

    private Path tempDbPath;
    private PaymentService paymentService;
    private SaleService saleService;
    private PurchaseService purchaseService;
    private CustomerLedgerDao customerLedgerDao;
    private SupplierLedgerDao supplierLedgerDao;
    private SaleDao saleDao;
    private PurchaseDao purchaseDao;
    private PaymentDao paymentDao;

    private Business biz1;
    private User owner1;
    private Customer cust1;
    private Supplier supp1;
    private Product prod1;

    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("payments_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        paymentService = new SqlitePaymentService();
        saleService = new SqliteSaleService();
        purchaseService = new SqlitePurchaseService();
        customerLedgerDao = new SqliteCustomerLedgerDao();
        supplierLedgerDao = new SqliteSupplierLedgerDao();
        saleDao = new SqliteSaleDao();
        purchaseDao = new SqlitePurchaseDao();
        paymentDao = new SqlitePaymentDao();

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

            // Customer 1 in Business 1
            long c1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO customers (business_id, name, phone, address) VALUES (?, 'Karim Mia', '01711223344', 'Mirpur, Dhaka')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    c1Id = rs.getLong(1);
                }
            }
            cust1 = new Customer(c1Id, b1Id, "Karim Mia", "01711223344", "Mirpur, Dhaka", Money.ZERO, Money.ZERO, false, null, null, null);

            // Supplier 1 in Business 1
            long s1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO suppliers (business_id, name, phone, address) VALUES (?, 'Meghna Food Corp', '01911223344', 'Chittagong')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    s1Id = rs.getLong(1);
                }
            }
            supp1 = new Supplier(s1Id, b1Id, "Meghna Food Corp", "01911223344", "Chittagong", null, Money.ZERO, false, null, null, null);

            // Product 1: 100 units stock
            long p1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (business_id, name, sku, unit, cost_price_paisha, selling_price_paisha, low_stock_threshold) " +
                            "VALUES (?, 'Miniket Rice 50kg', 'RICE-50', 'bag', 10000, 15000, 5)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    p1Id = rs.getLong(1);
                }
            }
            prod1 = new Product(p1Id, b1Id, null, "Miniket Rice 50kg", "RICE-50", null, "bag",
                    Money.ofBdt(100), Money.ofBdt(150), 5, true, null, null);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, notes, created_by_user_id) " +
                            "VALUES (?, ?, 'OPENING', 100, 10000, 'MANUAL', 'Initial stock', ?)")) {
                ps.setLong(1, b1Id);
                ps.setLong(2, p1Id);
                ps.setLong(3, u1Id);
                ps.executeUpdate();
            }

            // Business 2
            long b2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Sylhet Store', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b2Id = rs.getLong(1);
                }
            }
            biz2 = new Business(b2Id, "Sylhet Store", null, null, null, null, "BDT", null, null);

            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'owner_sylhet', 'Hasan Ali', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "owner_sylhet", "Hasan Ali", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldReceiveCustomerPaymentAndReduceOutstandingDue() throws SQLException {
        // Arrange: Create credit sale of BDT 1,000 (10 units @ 100). Customer owes BDT 1,000.
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 10, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(1000));
        Sale sale = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Baki bill", null, owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ofBdt(1000));
        }

        // Act: Collect BDT 400 from customer
        PaymentCreateDto payDto = new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(400), "CASH", "TRX-001", "Due installment", "IDEM-COLL-400", owner1.id()
        );
        Payment payment = paymentService.receiveCustomerPayment(payDto);

        // Assert: BDT 1,000 due - BDT 400 collection = BDT 600 due
        assertThat(payment.id()).isNotNull();
        assertThat(payment.paymentNumber()).startsWith("PAY-C-");
        assertThat(payment.amount()).isEqualTo(Money.ofBdt(400));

        try (Connection conn = DatabaseManager.getConnection()) {
            // Customer ledger balance is BDT 600
            Money currentDue = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(currentDue).isEqualTo(Money.ofBdt(600));

            // Sale invoice is updated: paid 400, due 600, status PARTIAL
            Sale updatedSale = saleDao.findById(conn, sale.id()).orElseThrow();
            assertThat(updatedSale.paidAmount()).isEqualTo(Money.ofBdt(400));
            assertThat(updatedSale.dueAmount()).isEqualTo(Money.ofBdt(600));
            assertThat(updatedSale.paymentStatus()).isEqualTo("PARTIAL");

            // Allocation record links payment to sale
            List<PaymentAllocationDto> allocs = paymentDao.findAllocationsByPaymentId(conn, payment.id());
            assertThat(allocs).hasSize(1);
            assertThat(allocs.get(0).saleId()).isEqualTo(sale.id());
            assertThat(allocs.get(0).allocatedAmount()).isEqualTo(Money.ofBdt(400));
        }
    }

    @Test
    void shouldMakeSupplierPaymentAndReduceOutstandingPayable() throws SQLException {
        // Arrange: Create credit purchase of BDT 500 (5 units @ 100). Store owes supplier BDT 500.
        SessionContext.setSession(biz1, owner1);
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 5, Money.ofBdt(100));
        Purchase purchase = purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Credit supply", null, owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id())).isEqualTo(Money.ofBdt(500));
        }

        // Act: Make supplier payment of BDT 200
        PaymentCreateDto payDto = new PaymentCreateDto(
                biz1.id(), "SUPPLIER", supp1.id(), Money.ofBdt(200), "BANK", "CHK-9988", "Installment payment", "IDEM-DISB-200", owner1.id()
        );
        Payment payment = paymentService.makeSupplierPayment(payDto);

        // Assert: BDT 500 payable - BDT 200 payment = BDT 300 payable
        assertThat(payment.id()).isNotNull();
        assertThat(payment.paymentNumber()).startsWith("PAY-S-");
        assertThat(payment.amount()).isEqualTo(Money.ofBdt(200));

        try (Connection conn = DatabaseManager.getConnection()) {
            // Supplier ledger balance is BDT 300
            Money currentPayable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(currentPayable).isEqualTo(Money.ofBdt(300));

            // Purchase bill updated: paid 200, due 300, status PARTIAL
            Purchase updatedPur = purchaseDao.findById(conn, purchase.id()).orElseThrow();
            assertThat(updatedPur.paidAmount()).isEqualTo(Money.ofBdt(200));
            assertThat(updatedPur.dueAmount()).isEqualTo(Money.ofBdt(300));
            assertThat(updatedPur.paymentStatus()).isEqualTo("PARTIAL");

            // Allocation record links payment to purchase
            List<PaymentAllocationDto> allocs = paymentDao.findAllocationsByPaymentId(conn, payment.id());
            assertThat(allocs).hasSize(1);
            assertThat(allocs.get(0).purchaseId()).isEqualTo(purchase.id());
            assertThat(allocs.get(0).allocatedAmount()).isEqualTo(Money.ofBdt(200));
        }
    }

    @Test
    void shouldPerformFIFOAllocationAcrossMultipleCustomerInvoices() throws SQLException {
        SessionContext.setSession(biz1, owner1);

        // Sale 1 (older): 3 units @ 100 = BDT 300 due
        SaleItemDto item1 = new SaleItemDto(prod1.id(), prod1.name(), 3, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(300));
        Sale sale1 = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item1), Money.ZERO, Money.ZERO, "CASH", "Sale 1", null, owner1.id()
        ));

        // Sale 2 (newer): 7 units @ 100 = BDT 700 due
        SaleItemDto item2 = new SaleItemDto(prod1.id(), prod1.name(), 7, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(700));
        Sale sale2 = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item2), Money.ZERO, Money.ZERO, "CASH", "Sale 2", null, owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ofBdt(1000));
        }

        // Act: Collect BDT 400. FIFO allocation should completely close Sale 1 (300) and allocate remaining 100 to Sale 2.
        Payment payment = paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(400), "BKASH", "BK-4455", "FIFO test", null, owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            // Sale 1: paid 300, due 0 -> PAID
            Sale s1 = saleDao.findById(conn, sale1.id()).orElseThrow();
            assertThat(s1.paidAmount()).isEqualTo(Money.ofBdt(300));
            assertThat(s1.dueAmount()).isEqualTo(Money.ZERO);
            assertThat(s1.paymentStatus()).isEqualTo("PAID");

            // Sale 2: paid 100, due 600 -> PARTIAL
            Sale s2 = saleDao.findById(conn, sale2.id()).orElseThrow();
            assertThat(s2.paidAmount()).isEqualTo(Money.ofBdt(100));
            assertThat(s2.dueAmount()).isEqualTo(Money.ofBdt(600));
            assertThat(s2.paymentStatus()).isEqualTo("PARTIAL");

            // Remaining due in customer ledger = BDT 600
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ofBdt(600));

            // 2 allocations persisted
            List<PaymentAllocationDto> allocs = paymentDao.findAllocationsByPaymentId(conn, payment.id());
            assertThat(allocs).hasSize(2);
            assertThat(allocs.get(0).saleId()).isEqualTo(sale1.id());
            assertThat(allocs.get(0).allocatedAmount()).isEqualTo(Money.ofBdt(300));
            assertThat(allocs.get(1).saleId()).isEqualTo(sale2.id());
            assertThat(allocs.get(1).allocatedAmount()).isEqualTo(Money.ofBdt(100));
        }
    }

    @Test
    void shouldRejectCustomerPaymentExceedingDueBalance() {
        SessionContext.setSession(biz1, owner1);

        // Sale: BDT 500 due
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 5, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(500));
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Sale 500", null, owner1.id()
        ));

        // Act & Assert: Attempt to collect BDT 600 when due is only 500 -> Rejected
        PaymentCreateDto overpayDto = new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(600), "CASH", null, "Excess payment", null, owner1.id()
        );

        assertThatThrownBy(() -> paymentService.receiveCustomerPayment(overpayDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds total outstanding customer due");
    }

    @Test
    void shouldRejectSupplierPaymentExceedingPayableBalance() {
        SessionContext.setSession(biz1, owner1);

        // Purchase: BDT 300 due
        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 3, Money.ofBdt(100));
        purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Bill 300", null, owner1.id()
        ));

        // Act & Assert: Attempt to pay BDT 500 when payable is only 300 -> Rejected
        PaymentCreateDto overpayDto = new PaymentCreateDto(
                biz1.id(), "SUPPLIER", supp1.id(), Money.ofBdt(500), "CASH", null, "Excess payout", null, owner1.id()
        );

        assertThatThrownBy(() -> paymentService.makeSupplierPayment(overpayDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds total outstanding supplier payable");
    }

    @Test
    void shouldReturnExistingPaymentWhenRetryingWithSameIdempotencyKey() throws SQLException {
        SessionContext.setSession(biz1, owner1);

        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 5, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(500));
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Sale", null, owner1.id()
        ));

        String idemKey = "RETRY-PAY-KEY-123";
        PaymentCreateDto payDto = new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(200), "CASH", "TRX", "Idempotency test", idemKey, owner1.id()
        );

        // Call 1
        Payment first = paymentService.receiveCustomerPayment(payDto);

        // Call 2: retry with same idempotency key
        Payment second = paymentService.receiveCustomerPayment(payDto);

        // Assert: Identical payment returned, ledger debited only once
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.paymentNumber()).isEqualTo(first.paymentNumber());

        try (Connection conn = DatabaseManager.getConnection()) {
            Money due = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(due).isEqualTo(Money.ofBdt(300)); // 500 - 200 (NOT 500 - 400)

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payments")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void shouldVoidCustomerPaymentAndRestoreInvoiceDuesAndLedger() throws SQLException {
        SessionContext.setSession(biz1, owner1);

        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 10, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(1000));
        Sale sale = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Sale 1000", null, owner1.id()
        ));

        // Collect BDT 400 -> due becomes 600
        Payment payment = paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(400), "CASH", null, "Installment", null, owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ofBdt(600));
        }

        // Act: Void payment (e.g. check bounced)
        paymentService.voidPayment(payment.id(), "Bank check bounced", owner1.id());

        // Assert
        try (Connection conn = DatabaseManager.getConnection()) {
            // Customer due restored back to BDT 1,000 via ADJUSTMENT entry
            Money restoredDue = customerLedgerDao.getCurrentBalance(conn, cust1.id());
            assertThat(restoredDue).isEqualTo(Money.ofBdt(1000));

            List<CustomerLedgerEntry> entries = customerLedgerDao.findByCustomerId(conn, cust1.id());
            assertThat(entries).hasSize(3); // Invoice (1000) + Payment (-400) + Reversal (+400)
            CustomerLedgerEntry reversal = entries.get(2);
            assertThat(reversal.entryType()).isEqualTo("ADJUSTMENT");
            assertThat(reversal.debit()).isEqualTo(Money.ofBdt(400));
            assertThat(reversal.runningBalance()).isEqualTo(Money.ofBdt(1000));

            // Sale invoice is restored back to unpaid state (paid 0, due 1,000, BAKI)
            Sale restoredSale = saleDao.findById(conn, sale.id()).orElseThrow();
            assertThat(restoredSale.paidAmount()).isEqualTo(Money.ZERO);
            assertThat(restoredSale.dueAmount()).isEqualTo(Money.ofBdt(1000));
            assertThat(restoredSale.paymentStatus()).isEqualTo("BAKI");

            // Payment is flagged reversed
            Payment p = paymentDao.findById(conn, payment.id()).orElseThrow();
            assertThat(p.isReversed()).isTrue();
            assertThat(p.reversalReason()).contains("check bounced");
        }

        // Repeated void fails
        assertThatThrownBy(() -> paymentService.voidPayment(payment.id(), "Repeated void", owner1.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already voided/reversed");
    }

    @Test
    void shouldVoidSupplierPaymentAndRestorePurchaseDuesAndLedger() throws SQLException {
        SessionContext.setSession(biz1, owner1);

        PurchaseItemDto item = PurchaseItemDto.of(prod1.id(), prod1.name(), 5, Money.ofBdt(100));
        Purchase purchase = purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Purchase 500", null, owner1.id()
        ));

        // Disburse BDT 200 -> payable becomes 300
        Payment payment = paymentService.makeSupplierPayment(new PaymentCreateDto(
                biz1.id(), "SUPPLIER", supp1.id(), Money.ofBdt(200), "CASH", null, "Payout", null, owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id())).isEqualTo(Money.ofBdt(300));
        }

        // Act: Void payment
        paymentService.voidPayment(payment.id(), "Payment draft cancelled", owner1.id());

        // Assert
        try (Connection conn = DatabaseManager.getConnection()) {
            // Supplier payable restored back to BDT 500
            Money restoredPayable = supplierLedgerDao.getCurrentPayableBalance(conn, supp1.id());
            assertThat(restoredPayable).isEqualTo(Money.ofBdt(500));

            // Purchase bill restored back to paid 0, due 500, DUE
            Purchase restoredPur = purchaseDao.findById(conn, purchase.id()).orElseThrow();
            assertThat(restoredPur.paidAmount()).isEqualTo(Money.ZERO);
            assertThat(restoredPur.dueAmount()).isEqualTo(Money.ofBdt(500));
            assertThat(restoredPur.paymentStatus()).isEqualTo("DUE");
        }
    }

    @Test
    void shouldEnforceCrossBusinessIsolationForPayments() {
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 5, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(500));
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Sale", null, owner1.id()
        ));

        Payment payment = paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(100), "CASH", null, "Pay", null, owner1.id()
        ));

        // Switch to Business 2
        SessionContext.setSession(biz2, owner2);

        // Cannot create payment for Biz 1 customer
        PaymentCreateDto hackDto = new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(100), "CASH", null, "Hack", null, owner2.id()
        );
        assertThatThrownBy(() -> paymentService.receiveCustomerPayment(hackDto))
                .isInstanceOf(SecurityException.class);

        // Cannot view detail
        assertThatThrownBy(() -> paymentService.getPaymentDetail(payment.id()))
                .isInstanceOf(SecurityException.class);

        // Cannot void
        assertThatThrownBy(() -> paymentService.voidPayment(payment.id(), "Hack void", owner2.id()))
                .isInstanceOf(SecurityException.class);

        // Cannot search
        assertThatThrownBy(() -> paymentService.searchPayments(biz1.id(), null, null, null, null, null, null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectPaymentOperationsWhenUnauthenticatedOrLocked() {
        SessionContext.clearSession();

        PaymentCreateDto dto = new PaymentCreateDto(
                biz1.id(), "CUSTOMER", 1L, Money.ofBdt(100), "CASH", null, "Unauth", null, 1L
        );

        assertThatThrownBy(() -> paymentService.receiveCustomerPayment(dto))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymentService.makeSupplierPayment(dto))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymentService.getPaymentDetail(1L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymentService.voidPayment(1L, "Reason", 1L))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> paymentService.searchPayments(biz1.id(), null, null, null, null, null, null))
                .isInstanceOf(SecurityException.class);

        // Locked
        SessionContext.setSession(biz1, owner1);
        SessionContext.lock();

        assertThatThrownBy(() -> paymentService.receiveCustomerPayment(dto))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldUpdatePaymentMetadataAndAllowOwnerToDeleteOnlyVoidedPayment() throws SQLException {
        SessionContext.setSession(biz1, owner1);
        SaleItemDto item = new SaleItemDto(prod1.id(), prod1.name(), 5, Money.ofBdt(100), prod1.costPrice(), Money.ZERO, Money.ofBdt(500));
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust1.id(), List.of(item), Money.ZERO, Money.ZERO, "CASH", "Credit sale", null, owner1.id()
        ));

        Payment payment = paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(200), "CASH", null, "First installment", null, owner1.id()
        ));

        // 1. Update payment metadata without altering amount
        paymentService.updatePaymentDetails(payment.id(), "BKASH", "BK-998877", "Verified bKash transfer");
        PaymentDetailDto updated = paymentService.getPaymentDetail(payment.id()).orElseThrow();
        assertThat(updated.payment().paymentMethod()).isEqualTo("BKASH");
        assertThat(updated.payment().referenceTrxId()).isEqualTo("BK-998877");
        assertThat(updated.payment().notes()).isEqualTo("Verified bKash transfer");
        assertThat(updated.payment().amount()).isEqualTo(Money.ofBdt(200));

        // 2. Active payment cannot be deleted
        assertThatThrownBy(() -> paymentService.deleteVoidedPayment(payment.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Void this voucher first");

        // 3. Void then delete by Owner
        paymentService.voidPayment(payment.id(), "Reversed transfer", owner1.id());
        paymentService.deleteVoidedPayment(payment.id());

        assertThat(paymentService.getPaymentDetail(payment.id())).isEmpty();
        try (Connection conn = DatabaseManager.getConnection()) {
            assertThat(customerLedgerDao.getCurrentBalance(conn, cust1.id())).isEqualTo(Money.ofBdt(500));
        }
    }
}


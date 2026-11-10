package com.smartkhata.qa;

import com.smartkhata.app.AppConfig;
import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.*;
import com.smartkhata.service.impl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Master QA Audit and Resilience Test Suite for SmartKhata.
 * Strictly verifies deterministic accounting invariants, stock movements, idempotency,
 * transaction atomicity, multi-tenant isolation, SQL injection resistance, and error recovery.
 */
class AccountingAuditAndResilienceTest {

    @TempDir
    Path tempDir;

    private SqliteCustomerService customerService;
    private SqliteSupplierService supplierService;
    private SqliteInventoryService inventoryService;
    private SqliteSaleService saleService;
    private SqlitePurchaseService purchaseService;
    private SqlitePaymentService paymentService;
    private SqliteExpenseService expenseService;
    private SqliteDashboardService dashboardService;
    private SqliteReportService reportService;

    private Business biz1;
    private Business biz2;
    private User owner1;
    private User owner2;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("smartkhata.home", tempDir.resolve("home").toString());
        AppConfig.initializeDirectories();

        Path dbPath = tempDir.resolve("qa_audit.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();

        customerService = new SqliteCustomerService();
        supplierService = new SqliteSupplierService();
        inventoryService = new SqliteInventoryService();
        saleService = new SqliteSaleService();
        purchaseService = new SqlitePurchaseService();
        paymentService = new SqlitePaymentService();
        expenseService = new SqliteExpenseService();
        dashboardService = new SqliteDashboardService();
        reportService = new SqliteReportService();

        try (Connection conn = DatabaseManager.getConnection()) {
            BusinessDao bDao = new SqliteBusinessDao();
            UserDao uDao = new SqliteUserDao();

            biz1 = bDao.save(conn, new Business(null, "Audit Primary Store", "Audit Co Ltd", "01711223344", "Dhaka", "TRAD-1", "BDT", null, null));
            owner1 = uDao.save(conn, new User(null, biz1.id(), "audit_owner1", "Audit Owner 1", "hash", "salt", UserRole.OWNER, true, null, null, null));

            biz2 = bDao.save(conn, new Business(null, "Audit Competitor Store", "Competitor Ltd", "01811223344", "Chittagong", "TRAD-2", "BDT", null, null));
            owner2 = uDao.save(conn, new User(null, biz2.id(), "audit_owner2", "Audit Owner 2", "hash", "salt", UserRole.OWNER, true, null, null, null));
        }

        SessionContext.setSession(biz1, owner1);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    /**
     * TEST PLAN ITEM 2:
     * Deterministic test dataset:
     * - Credit sale BDT 1,000 -> due BDT 1,000
     * - Receive payment BDT 400 -> due BDT 600
     * - Supplier credit purchase BDT 750 with payment 250 -> payable BDT 500
     * - Expense BDT 120 displayed exactly once
     */
    @Test
    void shouldVerifyDeterministicAccountingDataset() throws Exception {
        LocalDate today = LocalDate.now();

        // 1. Customer & Product for Sale
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Deterministic Customer", "01711220011", "Dhanmondi", Money.ZERO, Money.ZERO, "Regular"
        ));
        Product prod1 = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Audit Goods A", "8901111111111", "AUD-A", "pcs",
                Money.ofBdt(600.00), Money.ofBdt(1000.00), 5, 20, null
        ));

        // Act 1: Credit sale of BDT 1,000 (0 paid)
        Sale sale = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod1.id(), prod1.name(), 1, Money.ofBdt(1000.00), Money.ofBdt(600.00), Money.ZERO, Money.ofBdt(1000.00))),
                Money.ZERO, Money.ZERO, "CASH", "IDEM-SALE-1000", "Credit sale", owner1.id()
        ));

        // Assert 1: Sale due = 1000, Customer ledger due = 1000
        assertThat(sale.netAmount()).isEqualTo(Money.ofBdt(1000.00));
        assertThat(sale.dueAmount()).isEqualTo(Money.ofBdt(1000.00));
        try (Connection conn = DatabaseManager.getConnection()) {
            CustomerLedgerDao ledgerDao = new SqliteCustomerLedgerDao();
            assertThat(ledgerDao.getCurrentBalance(conn, cust.id())).isEqualTo(Money.ofBdt(1000.00));
        }

        // Act 2: Receive customer payment BDT 400
        Payment payment = paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust.id(), Money.ofBdt(400.00),
                "CASH", "TRX-COLL-400", "Partial collection", "IDEM-PAY-400", owner1.id()
        ));

        // Assert 2: Customer due is now exactly BDT 600 (1000 - 400)
        assertThat(payment.amount()).isEqualTo(Money.ofBdt(400.00));
        try (Connection conn = DatabaseManager.getConnection()) {
            CustomerLedgerDao ledgerDao = new SqliteCustomerLedgerDao();
            assertThat(ledgerDao.getCurrentBalance(conn, cust.id())).isEqualTo(Money.ofBdt(600.00));
        }

        // Act 3: Supplier credit purchase BDT 750 with initial payment 250
        Supplier supp = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Deterministic Supplier", "01811220022", "Tejgaon", "Mr. Asad", Money.ZERO, null
        ));
        Product prod2 = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Audit Goods B", "8902222222222", "AUD-B", "pcs",
                Money.ofBdt(750.00), Money.ofBdt(1200.00), 5, 0, null
        ));

        Purchase purchase = purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp.id(),
                List.of(new PurchaseItemDto(prod2.id(), prod2.name(), 1, Money.ofBdt(750.00), Money.ofBdt(750.00))),
                Money.ZERO, Money.ofBdt(250.00), "CASH", "Partial supply bill", "IDEM-PUR-750", owner1.id()
        ));

        // Assert 3: Purchase due = 500, Supplier payable = 500
        assertThat(purchase.netAmount()).isEqualTo(Money.ofBdt(750.00));
        assertThat(purchase.paidAmount()).isEqualTo(Money.ofBdt(250.00));
        assertThat(purchase.dueAmount()).isEqualTo(Money.ofBdt(500.00));
        try (Connection conn = DatabaseManager.getConnection()) {
            SupplierLedgerDao suppLedgerDao = new SqliteSupplierLedgerDao();
            assertThat(suppLedgerDao.getCurrentPayableBalance(conn, supp.id())).isEqualTo(Money.ofBdt(500.00));
        }

        // Act 4: Record operating expense BDT 120
        Expense expense = expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(120.00), today.atTime(14, 0),
                "CASH", "DESCO", "Electric bill", owner1.id()
        ));

        // Assert 4: Expense BDT 120 recorded and displayed exactly once across reports and lists
        assertThat(expense.amount()).isEqualTo(Money.ofBdt(120.00));
        List<Expense> expList = expenseService.searchExpenses(biz1.id(), null, null, null, today, today);
        assertThat(expList).hasSize(1);
        assertThat(expList.get(0).amount()).isEqualTo(Money.ofBdt(120.00));

        // Verify expense report summary shows exactly BDT 120
        ReportData expReport = reportService.generateReport(new ReportCriteria(
                biz1.id(), com.smartkhata.model.enums.ReportType.EXPENSE_SUMMARY, today, today, null, null, null, false
        ));
        assertThat(expReport.rowCount()).isEqualTo(1);
        assertThat(expReport.summaryCards().get("Total Operating Expenses")).isEqualTo("BDT 120");
    }

    /**
     * TEST PLAN ITEM 3:
     * Stock movements:
     * - Stock increases on purchase once
     * - Stock decreases on sale once
     * - Rejects unavailable/negative stock by default
     * - Reversals preserve historical audit
     */
    @Test
    void shouldVerifyStockMovementsAndNoNegativeStockEnforcement() throws Exception {
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Stock Audit Customer", "01799887766", "Mirpur", Money.ZERO, Money.ZERO, null
        ));
        Supplier supp = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Stock Audit Supplier", "01899887766", "Gazipur", "Officer", Money.ZERO, null
        ));

        // 1. Initial product with 10 units opening stock
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Storage Box", "8903333333333", "BOX-01", "pcs",
                Money.ofBdt(50.00), Money.ofBdt(80.00), 5, 10, null
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(10);
        }

        // 2. Purchase 5 units -> Stock increases to 15 exactly once
        purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp.id(),
                List.of(new PurchaseItemDto(prod.id(), prod.name(), 5, Money.ofBdt(50.00), Money.ofBdt(250.00))),
                Money.ZERO, Money.ofBdt(250.00), "CASH", "Restock", "PUR-STK-1", owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(15);
        }

        // 3. Sale 4 units -> Stock decreases to 11 exactly once
        Sale sale = saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 4, Money.ofBdt(80.00), Money.ofBdt(50.00), Money.ZERO, Money.ofBdt(320.00))),
                Money.ZERO, Money.ofBdt(320.00), "CASH", "SALE-STK-1", "Cash sale", owner1.id()
        ));

        try (Connection conn = DatabaseManager.getConnection()) {
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(11);
        }

        // 4. Attempting to sell 12 units (more than 11 in stock) -> MUST FAIL (no negative stock allowed)
        assertThatThrownBy(() -> saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 12, Money.ofBdt(80.00), Money.ofBdt(50.00), Money.ZERO, Money.ofBdt(960.00))),
                Money.ZERO, Money.ZERO, "CASH", "SALE-FAIL-OOS", "Excess sale", owner1.id()
        ))).isInstanceOf(Exception.class);

        // Verify stock is still 11
        try (Connection conn = DatabaseManager.getConnection()) {
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(11);
        }

        // 5. Reversal of the 4-unit sale -> restores stock to 15 and preserves audit log
        saleService.voidSale(sale.id(), "Customer returned items", owner1.id());

        try (Connection conn = DatabaseManager.getConnection()) {
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(15);

            // Audit verification: verify stock_movements contains all 4 audit records
            StockMovementDao smDao = new SqliteStockMovementDao();
            List<StockMovement> movements = smDao.findByProductId(conn, prod.id());
            assertThat(movements).hasSize(4); // Opening (+10), Purchase (+5), Sale (-4), Reversal (+4)
        }
    }

    /**
     * TEST PLAN ITEM 4:
     * Idempotency, retries, and duplicate suppression:
     * - Retrying same transaction with same idempotency key returns existing record without double posting
     */
    @Test
    void shouldPreventDuplicatePostingOnRetriesViaIdempotency() throws Exception {
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Idempotent Customer", "01744556677", "Baridhara", Money.ZERO, Money.ZERO, null
        ));
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Tablet Cover", "8904444444444", "COV-01", "pcs",
                Money.ofBdt(100.00), Money.ofBdt(150.00), 5, 50, null
        ));

        SaleCreateDto saleDto = new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 2, Money.ofBdt(150.00), Money.ofBdt(100.00), Money.ZERO, Money.ofBdt(300.00))),
                Money.ZERO, Money.ofBdt(100.00), "CASH", "UNIQUE-IDEM-TOKEN-S1", "Idempotent test", owner1.id()
        );

        // First attempt: creates invoice
        Sale s1 = saleService.createSale(saleDto);
        assertThat(s1).isNotNull();

        // Second attempt with same key: must return existing invoice without duplicate debiting
        Sale s2 = saleService.createSale(saleDto);
        assertThat(s2.id()).isEqualTo(s1.id());
        assertThat(s2.invoiceNumber()).isEqualTo(s1.invoiceNumber());

        // Verify customer due is exactly 200 (300 - 100), not double debited
        try (Connection conn = DatabaseManager.getConnection()) {
            CustomerLedgerDao ledgerDao = new SqliteCustomerLedgerDao();
            assertThat(ledgerDao.getCurrentBalance(conn, cust.id())).isEqualTo(Money.ofBdt(200.00));
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(48); // 50 - 2, not 50 - 4
        }
    }

    /**
     * TEST PLAN ITEM 4 (continued):
     * Archived parties policy:
     * - Cannot execute new transactions with archived customer or supplier
     */
    @Test
    void shouldEnforceArchivedPartiesPolicy() {
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Archived Customer", "01755667788", "Mohammadpur", Money.ZERO, Money.ZERO, null
        ));
        customerService.archiveCustomer(cust.id());

        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Pen Drive 32GB", "8905555555555", "USB-32", "pcs",
                Money.ofBdt(300.00), Money.ofBdt(450.00), 5, 20, null
        ));

        // Attempting sale to archived customer must be rejected
        assertThatThrownBy(() -> saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 1, Money.ofBdt(450.00), Money.ofBdt(300.00), Money.ZERO, Money.ofBdt(450.00))),
                Money.ZERO, Money.ofBdt(450.00), "CASH", "SALE-ARCH-1", null, owner1.id()
        ))).isInstanceOf(Exception.class);
    }

    /**
     * TEST PLAN ITEM 5:
     * Rollback on simulated failure:
     * - Atomic transaction boundary guarantees zero partial or orphaned rows
     */
    @Test
    void shouldRollbackAtomicallyWhenTransactionFailsMidway() throws Exception {
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Rollback Customer", "01766778899", "Badda", Money.ZERO, Money.ZERO, null
        ));
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Mouse Wireless", "8906666666666", "MOU-01", "pcs",
                Money.ofBdt(250.00), Money.ofBdt(400.00), 5, 5, null
        ));

        long initialSaleCount;
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sales")) {
            initialSaleCount = rs.getLong(1);
        }

        // Attempting a sale with 1 valid item (quantity 1) and 1 invalid item (quantity 99 > stock 5)
        assertThatThrownBy(() -> saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(
                        new SaleItemDto(prod.id(), prod.name(), 1, Money.ofBdt(400.00), Money.ofBdt(250.00), Money.ZERO, Money.ofBdt(400.00)),
                        new SaleItemDto(prod.id(), prod.name(), 99, Money.ofBdt(400.00), Money.ofBdt(250.00), Money.ZERO, Money.ofBdt(39600.00))
                ),
                Money.ZERO, Money.ZERO, "CASH", "FAIL-MIDWAY-1", "Rollback test", owner1.id()
        ))).isInstanceOf(Exception.class);

        // Verify zero sales recorded, customer ledger untouched, stock untouched
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sales")) {
                assertThat(rs.getLong(1)).isEqualTo(initialSaleCount);
            }
            ProductDao pDao = new SqliteProductDao();
            assertThat(pDao.getCurrentStock(conn, prod.id())).isEqualTo(5);
        }
    }

    /**
     * TEST PLAN ITEM 6:
     * Multi-tenant data isolation and SQL injection resistance:
     * - Parameterized queries safely neutralize injection payloads
     * - Business 1 cannot access Business 2 records
     */
    @Test
    void shouldEnforceMultiTenantIsolationAndSqlInjectionResistance() throws Exception {
        // Create customer in Business 1
        Customer cust1 = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Tenant 1 VIP", "01700000001", "Area 1", Money.ZERO, Money.ofBdt(500.00), null
        ));

        // Switch session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Customer in Business 2
        customerService.createCustomer(new CustomerCreateDto(
                biz2.id(), "Tenant 2 Client", "01800000002", "Area 2", Money.ZERO, Money.ofBdt(300.00), null
        ));

        // 1. Cross-tenant access denied: Business 2 attempting to view or query Business 1 customer
        assertThatThrownBy(() -> customerService.getCustomerById(cust1.id()))
                .isInstanceOf(SecurityException.class);

        // 2. SQL Injection resistance test: searching with malicious payload
        String sqlInjectionPayload = "' OR '1'='1' -- ";
        List<CustomerListItemDto> searchResults = customerService.searchCustomers(biz2.id(), sqlInjectionPayload, false);
        // Parameterized query treats it as literal string: returns 0 matches without breaking or leaking!
        assertThat(searchResults).isEmpty();
    }
}

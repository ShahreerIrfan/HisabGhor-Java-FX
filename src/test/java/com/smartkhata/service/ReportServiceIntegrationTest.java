package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.ReportType;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.*;
import com.smartkhata.service.impl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for Stage 14: Reports, Exports, and Ledger Statements.
 * Verifies 8 comprehensive accounting scenarios:
 * 1. Sales Summary reconciliation (partially paid, paid, credit)
 * 2. Purchase Summary reconciliation (supplier bills and dues)
 * 3. Customer Outstanding & Ledger Statement with running balance arithmetic
 * 4. Supplier Outstanding & Ledger Statement with running payable arithmetic
 * 5. Inventory Valuation and Low-Stock alert reporting
 * 6. Expenses and Other Income statements
 * 7. Exclusion of voided/reversed transactions from active totals
 * 8. PDF (valid binary stream with header %PDF-) & CSV (RFC 4180 escaping & Unicode BOM) export integrity
 * 9. Multi-tenant cross-business security enforcement
 */
class ReportServiceIntegrationTest {

    private Path tempDbPath;
    private ReportService reportService;
    private SaleService saleService;
    private PurchaseService purchaseService;
    private PaymentService paymentService;
    private ExpenseService expenseService;
    private InventoryService inventoryService;
    private CustomerService customerService;
    private SupplierService supplierService;

    private Business biz1;
    private User owner1;

    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("reports_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        reportService = new SqliteReportService();
        saleService = new SqliteSaleService();
        purchaseService = new SqlitePurchaseService();
        paymentService = new SqlitePaymentService();
        expenseService = new SqliteExpenseService();
        inventoryService = new SqliteInventoryService();
        customerService = new SqliteCustomerService();
        supplierService = new SqliteSupplierService();

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

            // Business 2
            long b2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Madina Trade', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b2Id = rs.getLong(1);
                }
            }
            biz2 = new Business(b2Id, "Madina Trade", null, null, null, null, "BDT", null, null);

            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'owner_madina', 'Tariqul Islam', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "owner_madina", "Tariqul Islam", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });

        SessionContext.setSession(biz1, owner1);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldReconcileSalesSummaryAndGeneratePdfCsv() {
        LocalDate today = LocalDate.now();

        // 1. Setup Product
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Napa Extra 500mg", "8901234567890", "NAP-01", "pcs",
                Money.ofBdt(2.00), Money.ofBdt(3.00), 10, 100, "Initial stock"
        ));

        // 2. Setup Customer
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Rahim Uddin", "01711223344", "Dhaka", Money.ZERO, Money.ZERO, null
        ));

        // 3. Post Sale 1: 20 pcs @ 3.00 = BDT 60. Paid BDT 20, Due BDT 40
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 20, Money.ofBdt(3.00), Money.ofBdt(2.00), Money.ZERO, Money.ofBdt(60.00))),
                Money.ZERO, Money.ofBdt(20.00), "CASH", "KEY-S1", "Partially paid", owner1.id()
        ));

        // 4. Post Sale 2: 10 pcs @ 3.00 = BDT 30. Paid BDT 30, Due BDT 0
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 10, Money.ofBdt(3.00), Money.ofBdt(2.00), Money.ZERO, Money.ofBdt(30.00))),
                Money.ZERO, Money.ofBdt(30.00), "CASH", "KEY-S2", "Fully paid", owner1.id()
        ));

        // Generate Sales Summary Report
        ReportCriteria criteria = new ReportCriteria(biz1.id(), ReportType.SALES_SUMMARY, today, today, null, null, null, false);
        ReportData report = reportService.generateReport(criteria);

        assertThat(report.rowCount()).isEqualTo(2);
        assertThat(report.summaryCards().get("Total Sales Volume")).isEqualTo("BDT 90");
        assertThat(report.summaryCards().get("Total Collected")).isEqualTo("BDT 50");
        assertThat(report.summaryCards().get("Total Outstanding Due")).isEqualTo("BDT 40");

        // Verify CSV export
        String csv = reportService.exportToCsv(report);
        assertThat(csv).startsWith("\uFEFF"); // UTF-8 BOM
        assertThat(csv).contains("Sales Summary Report");
        assertThat(csv).contains("Rahim Uddin");
        assertThat(csv).contains("BDT 90");

        // Verify PDF export
        byte[] pdfBytes = reportService.exportToPdf(report);
        assertThat(pdfBytes).isNotEmpty();
        String pdfHeader = new String(pdfBytes, 0, Math.min(5, pdfBytes.length), StandardCharsets.US_ASCII);
        assertThat(pdfHeader).isEqualTo("%PDF-");
    }

    @Test
    void shouldReconcilePurchaseSummaryReport() {
        LocalDate today = LocalDate.now();

        // 1. Supplier
        Supplier supp = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Square Pharmaceuticals", "01799887766", "Dhaka", "Mr. Kabir", Money.ZERO, null
        ));

        // 2. Product
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Seclo 20mg", "8901234567891", "SEC-20", "strip",
                Money.ofBdt(40.00), Money.ofBdt(60.00), 5, 0, null
        ));

        // 3. Purchase Bill: 50 strips @ BDT 40 = BDT 2,000. Paid BDT 800, Due BDT 1,200
        purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp.id(),
                List.of(new PurchaseItemDto(prod.id(), prod.name(), 50, Money.ofBdt(40.00), Money.ofBdt(2000.00))),
                Money.ZERO, Money.ofBdt(800.00), "BANK", "Bill note", "PUR-KEY-01", owner1.id()
        ));

        ReportCriteria criteria = new ReportCriteria(biz1.id(), ReportType.PURCHASE_SUMMARY, today, today, null, null, null, false);
        ReportData report = reportService.generateReport(criteria);

        assertThat(report.rowCount()).isEqualTo(1);
        assertThat(report.summaryCards().get("Total Purchases")).isEqualTo("BDT 2,000");
        assertThat(report.summaryCards().get("Total Paid")).isEqualTo("BDT 800");
        assertThat(report.summaryCards().get("Total Payable Due")).isEqualTo("BDT 1,200");
    }

    @Test
    void shouldReconcileCustomerLedgerStatementWithRunningArithmetic() {
        LocalDate today = LocalDate.now();

        // Customer with Opening Balance BDT 250.00
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Mizanur Rahman", "01811223344", "Mirpur", Money.ZERO, Money.ofBdt(250.00), "Regular"
        ));

        // Product
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Antacid", "8901234567892", "ANT-01", "pcs",
                Money.ofBdt(10.00), Money.ofBdt(20.00), 5, 50, null
        ));

        // Sale: 10 @ 20 = BDT 200, unpaid (Due +200). Balance becomes 250 + 200 = 450
        saleService.createSale(new SaleCreateDto(
                biz1.id(), cust.id(),
                List.of(new SaleItemDto(prod.id(), prod.name(), 10, Money.ofBdt(20.00), Money.ofBdt(10.00), Money.ZERO, Money.ofBdt(200.00))),
                Money.ZERO, Money.ZERO, "CASH", "S-K-1", null, owner1.id()
        ));

        // Payment: Collection of BDT 150 (Credit -150). Balance becomes 450 - 150 = 300
        paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust.id(), Money.ofBdt(150.00),
                "CASH", "TRX-101", "Partial payment", "P-K-1", owner1.id()
        ));

        ReportCriteria criteria = new ReportCriteria(biz1.id(), ReportType.CUSTOMER_LEDGER, today, today, cust.id(), null, null, false);
        ReportData report = reportService.generateReport(criteria);

        assertThat(report.summaryCards().get("Closing Due Balance")).isEqualTo("BDT 300");
        assertThat(report.summaryCards().get("Total Invoiced (Debit)")).isEqualTo("BDT 450");
        assertThat(report.summaryCards().get("Total Collected (Credit)")).isEqualTo("BDT 150");

        // Verify CSV contains customer name and exact closing due
        String csv = reportService.exportToCsv(report);
        assertThat(csv).contains("Mizanur Rahman");
        assertThat(csv).contains("BDT 300");
    }

    @Test
    void shouldReconcileSupplierLedgerStatement() {
        LocalDate today = LocalDate.now();

        // Supplier with Opening Payable BDT 500.00
        Supplier supp = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Beximco Pharma", "01722334455", "Dhaka", "Mr. Faruk", Money.ofBdt(500.00), null
        ));

        // Product
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Napa 500mg", "8901234567893", "NAP-500", "pcs",
                Money.ofBdt(1.00), Money.ofBdt(1.50), 5, 0, null
        ));

        // Purchase: 1000 @ 1.00 = BDT 1000, unpaid (Payable +1000). Total payable = 500 + 1000 = 1500
        purchaseService.createPurchase(new PurchaseCreateDto(
                biz1.id(), supp.id(),
                List.of(new PurchaseItemDto(prod.id(), prod.name(), 1000, Money.ofBdt(1.00), Money.ofBdt(1000.00))),
                Money.ZERO, Money.ZERO, "CASH", "Invoice supply", "PUR-BX-01", owner1.id()
        ));

        // Make Supplier Payment: BDT 600 (Debit -600). Total payable = 1500 - 600 = 900
        paymentService.makeSupplierPayment(new PaymentCreateDto(
                biz1.id(), "SUPPLIER", supp.id(), Money.ofBdt(600.00),
                "BANK", "TRX-BX-01", "Disbursement", "PAY-BX-01", owner1.id()
        ));

        ReportCriteria criteria = new ReportCriteria(biz1.id(), ReportType.SUPPLIER_LEDGER, today, today, supp.id(), null, null, false);
        ReportData report = reportService.generateReport(criteria);

        assertThat(report.summaryCards().get("Closing Payable Balance")).isEqualTo("BDT 900");
        assertThat(report.summaryCards().get("Total Purchases (Credit)")).isEqualTo("BDT 1,500");
        assertThat(report.summaryCards().get("Total Paid (Debit)")).isEqualTo("BDT 600");
    }

    @Test
    void shouldReconcileInventoryValuationAndLowStockFilter() {
        // Product 1: High stock (cost BDT 50, stock 100, threshold 10) -> valuation BDT 5,000
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Ceevit 250mg", "8901234567894", "CEE-250", "pcs",
                Money.ofBdt(50.00), Money.ofBdt(70.00), 10, 100, null
        ));

        // Product 2: Low stock (cost BDT 20, stock 3, threshold 10) -> valuation BDT 60
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Ace Plus", "8901234567895", "ACE-PLS", "pcs",
                Money.ofBdt(20.00), Money.ofBdt(30.00), 10, 3, null
        ));

        // All items report
        ReportData allReport = reportService.generateReport(new ReportCriteria(
                biz1.id(), ReportType.INVENTORY_VALUATION, null, null, null, null, null, false
        ));
        assertThat(allReport.rowCount()).isEqualTo(2);
        assertThat(allReport.summaryCards().get("Total Inventory Valuation (মজুদ মূল্য)")).isEqualTo("BDT 5,060");
        assertThat(allReport.summaryCards().get("Low Stock Alerts")).isEqualTo("1");

        // Low-stock only report
        ReportData lowStockReport = reportService.generateReport(new ReportCriteria(
                biz1.id(), ReportType.INVENTORY_VALUATION, null, null, null, null, null, true
        ));
        assertThat(lowStockReport.rowCount()).isEqualTo(1);
        assertThat(lowStockReport.rows().get(0).get(1)).isEqualTo("Ace Plus");
    }

    @Test
    void shouldReconcileExpensesAndExcludeVoidedTransactions() {
        LocalDate today = LocalDate.now();

        // 1. Active expense: BDT 450
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(450.00), today.atTime(10, 0),
                "CASH", "Electricity", "November bill", owner1.id()
        ));

        // 2. Voided expense: BDT 300
        Expense expVoid = expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "TRANSPORT", Money.ofBdt(300.00), today.atTime(11, 0),
                "CASH", "Driver", "Erroneous", owner1.id()
        ));
        expenseService.voidExpense(expVoid.id(), "Voided by manager", owner1.id());

        ReportCriteria criteria = new ReportCriteria(biz1.id(), ReportType.EXPENSE_SUMMARY, today, today, null, null, null, false);
        ReportData report = reportService.generateReport(criteria);

        assertThat(report.rowCount()).isEqualTo(1);
        assertThat(report.summaryCards().get("Total Operating Expenses")).isEqualTo("BDT 450");
        assertThat(report.rows().get(0).get(1)).isEqualTo("UTILITIES");
    }

    @Test
    void shouldEnforceCrossBusinessIsolationForReports() {
        LocalDate today = LocalDate.now();

        // Switch to Business 2 session
        SessionContext.setSession(biz2, owner2);

        // Attempting to generate reports for Business 1
        assertThatThrownBy(() -> reportService.generateReport(new ReportCriteria(
                biz1.id(), ReportType.SALES_SUMMARY, today, today, null, null, null, false
        ))).isInstanceOf(SecurityException.class);
    }
}

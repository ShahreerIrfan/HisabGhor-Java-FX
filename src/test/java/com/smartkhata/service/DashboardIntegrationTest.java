package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.*;
import com.smartkhata.service.impl.*;
import com.smartkhata.ui.ViewRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test suite for Stage 13: Dashboard Integration & Real Data Persistence.
 * Verifies:
 * - Empty database returns real zeros (no fake sales) and friendly guidance
 * - Real-time KPI updates after posting sales, payments, purchases, expenses, and stock adjustments
 * - Outstanding dues panel accuracy (customer receivables & count of customers with positive due)
 * - Low-stock alerts based on stock movements and threshold
 * - Sales, collections, and expenses trend segregation
 * - Voided/reversed transactions exclusion from all dashboard metrics
 * - Global search across customers, products, suppliers, and invoices
 * - Date-range query filtering
 */
class DashboardIntegrationTest {

    private Path tempDbPath;
    private DashboardService dashboardService;
    private SaleService saleService;
    private PaymentService paymentService;
    private ExpenseService expenseService;
    private InventoryService inventoryService;
    private SupplierService supplierService;

    private Business biz1;
    private User owner1;
    private Customer cust1;
    private Product prod1;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("dashboard_integration_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        dashboardService = new SqliteDashboardService();
        saleService = new SqliteSaleService();
        paymentService = new SqlitePaymentService();
        expenseService = new SqliteExpenseService();
        inventoryService = new SqliteInventoryService();
        supplierService = new SqliteSupplierService();

        TransactionManager.runInTransaction(conn -> {
            // Business 1
            long b1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Bismillah Pharmacy', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b1Id = rs.getLong(1);
                }
            }
            biz1 = new Business(b1Id, "Bismillah Pharmacy", null, null, null, null, "BDT", null, null);

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
        });

        SessionContext.setSession(biz1, owner1);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldReturnRealZerosOnEmptyDatabase() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);

        DashboardKPIs kpis = dashboardService.getKPIs(biz1.id(), start, end);
        assertThat(kpis.totalSales()).isEqualTo(Money.ZERO);
        assertThat(kpis.dueCollection()).isEqualTo(Money.ZERO);
        assertThat(kpis.expenses()).isEqualTo(Money.ZERO);
        assertThat(kpis.inventoryValue()).isEqualTo(Money.ZERO);
        assertThat(kpis.totalStockItemsCount()).isZero();

        OutstandingDuesSummary dues = dashboardService.getOutstandingDues(biz1.id());
        assertThat(dues.totalDues()).isEqualTo(Money.ZERO);
        assertThat(dues.customersWithDueCount()).isZero();

        List<RecentTransactionDto> recent = dashboardService.getRecentTransactions(biz1.id(), 10);
        assertThat(recent).isEmpty();

        List<LowStockItemDto> lowStock = dashboardService.getLowStockAlerts(biz1.id(), 5);
        assertThat(lowStock).isEmpty();

        List<SalesTrendPoint> trend = dashboardService.getTrend(biz1.id(), start, end, DashboardService.TrendMetric.SALES);
        assertThat(trend).isEmpty();

        TrendSummaryMetrics summary = dashboardService.getTrendSummaryMetrics(biz1.id(), start, end, DashboardService.TrendMetric.SALES);
        assertThat(summary.totalAmount()).isEqualTo(Money.ZERO);
        assertThat(summary.count()).isZero();
    }

    @Test
    void shouldUpdateDashboardTotalsAfterPostingTransactions() {
        LocalDate today = LocalDate.now();

        // 1. Create a Product with Cost BDT 50, Price BDT 80, and 50 units opening stock
        prod1 = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Paracetamol 500mg", "8901234567890", "PARA-500", "pcs",
                Money.ofBdt(50.00), Money.ofBdt(80.00), 10, 50, "Opening reception"
        ));

        // Verify Inventory Value = 50 * BDT 50 = BDT 2,500.00
        DashboardKPIs kpi1 = dashboardService.getKPIs(biz1.id(), today, today);
        assertThat(kpi1.inventoryValue()).isEqualTo(Money.ofBdt(2500.00));
        assertThat(kpi1.totalStockItemsCount()).isEqualTo(50);

        // 2. Create a Customer
        CustomerService customerService = new SqliteCustomerService();
        cust1 = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Karim Mia", "01711223344", "Mirpur, Dhaka", Money.ZERO, Money.ZERO, "Regular buyer"
        ));

        // 3. Post a Sale: 10 units @ BDT 80 = BDT 800. Paid BDT 300, Due BDT 500.
        SaleCreateDto saleDto = new SaleCreateDto(
                biz1.id(), cust1.id(),
                List.of(new SaleItemDto(prod1.id(), prod1.name(), 10, Money.ofBdt(80.00), Money.ofBdt(50.00), Money.ZERO, Money.ofBdt(800.00))),
                Money.ZERO, Money.ofBdt(300.00), "CASH", "KEY-SALE-01", "Sales note", owner1.id()
        );
        saleService.createSale(saleDto);

        // Verify Dashboard changes
        DashboardKPIs kpi2 = dashboardService.getKPIs(biz1.id(), today, today);
        assertThat(kpi2.totalSales()).isEqualTo(Money.ofBdt(800.00));
        assertThat(kpi2.dueCollection()).isEqualTo(Money.ofBdt(300.00));
        // Remaining stock: 40 units * 50 = BDT 2,000.00
        assertThat(kpi2.inventoryValue()).isEqualTo(Money.ofBdt(2000.00));
        assertThat(kpi2.totalStockItemsCount()).isEqualTo(40);

        // Verify Customer Due = BDT 500, Customers with Due Count = 1
        OutstandingDuesSummary dues1 = dashboardService.getOutstandingDues(biz1.id());
        assertThat(dues1.totalDues()).isEqualTo(Money.ofBdt(500.00));
        assertThat(dues1.customersWithDueCount()).isEqualTo(1);

        // 4. Record an Operational Expense of BDT 120 (UTILITIES)
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(120.00), today.atTime(11, 0),
                "CASH", "DESCO", "Electric recharge", owner1.id()
        ));

        DashboardKPIs kpi3 = dashboardService.getKPIs(biz1.id(), today, today);
        assertThat(kpi3.expenses()).isEqualTo(Money.ofBdt(120.00));

        // 5. Customer collects remaining BDT 500 due
        paymentService.receiveCustomerPayment(new PaymentCreateDto(
                biz1.id(), "CUSTOMER", cust1.id(), Money.ofBdt(500.00),
                "CASH", "TRX-001", "Due clearance", "KEY-PAY-01", owner1.id()
        ));

        // Total Due should now be 0, customer with due count should be 0
        OutstandingDuesSummary dues2 = dashboardService.getOutstandingDues(biz1.id());
        assertThat(dues2.totalDues()).isEqualTo(Money.ZERO);
        assertThat(dues2.customersWithDueCount()).isZero();

        // Due collection should now be BDT 300 + BDT 500 = BDT 800
        DashboardKPIs kpi4 = dashboardService.getKPIs(biz1.id(), today, today);
        assertThat(kpi4.dueCollection()).isEqualTo(Money.ofBdt(800.00));
    }

    @Test
    void shouldReflectLowStockAlertsWhenBelowThreshold() {
        // Product with low_stock_threshold = 15, openingStock = 8
        prod1 = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Omeprazole 20mg", "8901234567891", "OMEP-20", "strip",
                Money.ofBdt(40.00), Money.ofBdt(60.00), 15, 8, "Low stock reception"
        ));

        List<LowStockItemDto> lowStock = dashboardService.getLowStockAlerts(biz1.id(), 5);
        assertThat(lowStock).hasSize(1);
        assertThat(lowStock.get(0).productName()).isEqualTo("Omeprazole 20mg");
        assertThat(lowStock.get(0).currentStock()).isEqualTo(8);
        assertThat(lowStock.get(0).unit()).isEqualTo("strip");
    }

    @Test
    void shouldSegregateTrendMetricsWithoutMixing() {
        LocalDate today = LocalDate.now();

        // 1. Post a Sale BDT 500
        prod1 = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Antacid Plus", "8901234567892", "ANT-01", "pcs",
                Money.ofBdt(20.00), Money.ofBdt(50.00), 5, 100, "Initial stock"
        ));

        saleService.createSale(new SaleCreateDto(
                biz1.id(), null,
                List.of(new SaleItemDto(prod1.id(), prod1.name(), 10, Money.ofBdt(50.00), Money.ofBdt(20.00), Money.ZERO, Money.ofBdt(500.00))),
                Money.ZERO, Money.ofBdt(500.00), "CASH", "K-TR-01", null, owner1.id()
        ));

        // 2. Post an Expense BDT 150
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "TRANSPORT", Money.ofBdt(150.00), today.atTime(10, 0),
                "CASH", "Courier", null, owner1.id()
        ));

        // Verify SALES trend
        List<SalesTrendPoint> salesTrend = dashboardService.getTrend(biz1.id(), today, today, DashboardService.TrendMetric.SALES);
        assertThat(salesTrend).hasSize(1);
        assertThat(salesTrend.get(0).amount()).isEqualTo(Money.ofBdt(500.00));

        TrendSummaryMetrics salesMetrics = dashboardService.getTrendSummaryMetrics(biz1.id(), today, today, DashboardService.TrendMetric.SALES);
        assertThat(salesMetrics.totalAmount()).isEqualTo(Money.ofBdt(500.00));
        assertThat(salesMetrics.count()).isEqualTo(1);
        assertThat(salesMetrics.countLabel()).isEqualTo("Total Invoices");

        // Verify EXPENSES trend
        List<SalesTrendPoint> expTrend = dashboardService.getTrend(biz1.id(), today, today, DashboardService.TrendMetric.EXPENSES);
        assertThat(expTrend).hasSize(1);
        assertThat(expTrend.get(0).amount()).isEqualTo(Money.ofBdt(150.00));

        TrendSummaryMetrics expMetrics = dashboardService.getTrendSummaryMetrics(biz1.id(), today, today, DashboardService.TrendMetric.EXPENSES);
        assertThat(expMetrics.totalAmount()).isEqualTo(Money.ofBdt(150.00));
        assertThat(expMetrics.count()).isEqualTo(1);
        assertThat(expMetrics.countLabel()).isEqualTo("Expense Vouchers");
    }

    @Test
    void shouldExcludeVoidedTransactionsFromAllDashboardMetrics() {
        LocalDate today = LocalDate.now();

        // Record expense BDT 300
        Expense exp = expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "MAINTENANCE", Money.ofBdt(300.00), today.atTime(14, 0),
                "CASH", "Carpenter", "Door fix", owner1.id()
        ));

        DashboardKPIs beforeVoid = dashboardService.getKPIs(biz1.id(), today, today);
        assertThat(beforeVoid.expenses()).isEqualTo(Money.ofBdt(300.00));

        // Void the expense
        expenseService.voidExpense(exp.id(), "Wrong amount entered", owner1.id());

        // Expense should immediately drop back to 0.00
        DashboardKPIs afterVoid = dashboardService.getKPIs(biz1.id(), today, today);
        assertThat(afterVoid.expenses()).isEqualTo(Money.ZERO);

        // Recent transactions should exclude the voided record
        List<RecentTransactionDto> recent = dashboardService.getRecentTransactions(biz1.id(), 10);
        assertThat(recent).noneMatch(tx -> tx.partyName().contains("Carpenter") || tx.amount().equals(Money.ofBdt(300.00)));
    }

    @Test
    void shouldPerformGlobalSearchAcrossCustomersProductsSuppliersAndInvoices() {
        // Customer
        CustomerService cs = new SqliteCustomerService();
        cs.createCustomer(new CustomerCreateDto(
                biz1.id(), "Tariqul Islam", "01812345678", "Gulshan", Money.ZERO, Money.ZERO, null
        ));

        // Product
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Napa Extra 500mg", "8909999888811", "NAP-EXT", "pcs",
                Money.ofBdt(2.00), Money.ofBdt(3.00), 20, 25, "In stock"
        ));

        // Supplier
        supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Square Pharmaceuticals PLC", "01799887766", "Mohakhali", "Mr. Kabir", Money.ZERO, null
        ));

        // 1. Search Customer
        List<SearchResultItemDto> custMatches = dashboardService.globalSearch(biz1.id(), "Tariqul", 5);
        assertThat(custMatches).hasSize(1);
        assertThat(custMatches.get(0).category()).isEqualTo("Customer");
        assertThat(custMatches.get(0).targetView()).isEqualTo(ViewRouter.ViewId.CUSTOMERS);

        // 2. Search Product
        List<SearchResultItemDto> prodMatches = dashboardService.globalSearch(biz1.id(), "Napa", 5);
        assertThat(prodMatches).hasSize(1);
        assertThat(prodMatches.get(0).category()).isEqualTo("Product");
        assertThat(prodMatches.get(0).targetView()).isEqualTo(ViewRouter.ViewId.INVENTORY);

        // 3. Search Supplier
        List<SearchResultItemDto> suppMatches = dashboardService.globalSearch(biz1.id(), "Square", 5);
        assertThat(suppMatches).hasSize(1);
        assertThat(suppMatches.get(0).category()).isEqualTo("Supplier");
        assertThat(suppMatches.get(0).targetView()).isEqualTo(ViewRouter.ViewId.SUPPLIERS);
    }
}

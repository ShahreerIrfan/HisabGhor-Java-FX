package com.smartkhata.service;

import com.smartkhata.dao.SqliteSaleDao;
import com.smartkhata.dao.SqliteSettingsDao;
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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 Acceptance Tests:
 * - Test 8: POS Sales & COGS using current weighted average cost (5 units @ BDT 110 -> COGS 550),
 *   actual selling price vs profit calculation, and historical COGS immutability when later GRNs change cost.
 * - MRP BLOCK policy enforcement.
 * - Partial Sales Return restoring stock & adjusting period revenue/COGS without mutating past invoice snapshots.
 * - Business P&L, Product Profitability, Category Profitability & Supplier Purchase Analysis reports (zero double-counting!).
 */
class SupplyChainPhase5ProfitLossIntegrationTest {

    private InventoryService inventoryService;
    private SupplierService supplierService;
    private GrnService grnService;
    private SaleService saleService;
    private ExpenseService expenseService;
    private ReportService reportService;
    private Business biz;
    private User owner;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("scm_phase5_pl_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        inventoryService = new SqliteInventoryService();
        supplierService = new SqliteSupplierService();
        grnService = new SqliteGrnService();
        saleService = new SqliteSaleService();
        expenseService = new SqliteExpenseService();
        reportService = new SqliteReportService();

        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Profit & Loss Mart', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    bizId = rs.getLong(1);
                }
            }
            biz = new Business(bizId, "Profit & Loss Mart", null, null, null, null, "BDT", null, null);

            long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) VALUES (?, 'owner', 'Owner', 'x', 'y', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, bizId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }
            owner = new User(userId, bizId, "owner", "Owner", "x", "y", UserRole.OWNER, true, null, null, null);
        });
        SessionContext.setSession(biz, owner);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void test8_posCogsHistoricalImmutabilityAndProfitLossReportsWithoutDoubleCount() throws Exception {
        Category teaCat = inventoryService.createCategory(biz.id(), "Beverages", "Tea & Drinks");
        Supplier sup = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "Ispahani Distributors", "Kamal", "01711334455", "Dhaka", Money.ZERO, null));

        // 1. Seed 10 units @ BDT 100 + GRN 10 units @ BDT 120 -> Weighted Avg Cost = BDT 110.00
        Product tea = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), teaCat.id(), "Ispahani Mirzapore Tea", "894999000", "TEA-1",
                "pkt", Money.ofBdt(100), Money.ofBdt(140), 2, 10, "Open", null,
                Money.ofBdt(100), Money.ofBdt(150), "pkt", 1));
        grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), null, sup.id(), Money.ZERO, Money.ZERO, "CH-TEA-1", "Batch @ 120", "grn-tea-1", owner.id(),
                List.of(new GrnItemCreateDto(null, tea.id(), 10, 10, 0, 0, "pkt", 1, Money.ofBdt(120), Money.ZERO, null))
        ));
        assertThat(inventoryService.getCurrentStock(tea.id())).isEqualTo(20);
        assertThat(inventoryService.getProductById(tea.id()).orElseThrow().costPrice()).isEqualTo(Money.ofBdt(110));

        // 2. Test 8: Sell 5 units @ BDT 140 -> Revenue = 700, COGS = 550 (5 * 110), Gross Profit = 150
        Sale sale1 = saleService.createSale(new SaleCreateDto(
                biz.id(), null,
                List.of(new SaleItemDto(tea.id(), "Ispahani Mirzapore Tea", 5, Money.ofBdt(140), Money.ZERO, Money.ZERO, Money.ofBdt(700))),
                Money.ZERO, Money.ofBdt(700), "CASH", "sale-cogs-1", "Walk-in sale", owner.id()
        ));
        assertThat(sale1.totalCogs()).isEqualTo(Money.ofBdt(550));
        assertThat(sale1.grossProfit()).isEqualTo(Money.ofBdt(150));

        // 3. Receive a new GRN later at BDT 200 -> changes current avg cost, MUST NEVER rewrite sale1 COGS!
        grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), null, sup.id(), Money.ZERO, Money.ZERO, "CH-TEA-2", "Higher batch", "grn-tea-2", owner.id(),
                List.of(new GrnItemCreateDto(null, tea.id(), 5, 5, 0, 0, "pkt", 1, Money.ofBdt(200), Money.ZERO, null))
        ));
        Sale reloadedSale1 = saleService.getSaleDetail(sale1.id()).orElseThrow().sale();
        assertThat(reloadedSale1.totalCogs()).isEqualTo(Money.ofBdt(550));
        assertThat(reloadedSale1.grossProfit()).isEqualTo(Money.ofBdt(150));

        // 4. MRP BLOCK setting prevents selling above MRP (150) when configured
        TransactionManager.runInTransaction(conn -> new SqliteSettingsDao().set(conn, biz.id(), "scm.mrp_policy", "BLOCK"));
        assertThatThrownBy(() -> saleService.createSale(new SaleCreateDto(
                biz.id(), null,
                List.of(new SaleItemDto(tea.id(), "Ispahani Mirzapore Tea", 1, Money.ofBdt(160), Money.ZERO, Money.ZERO, Money.ofBdt(160))),
                Money.ZERO, Money.ofBdt(160), "CASH", "sale-mrp-block", "Above MRP", owner.id()
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds configured MRP");

        // 5. Partial Sales Return of 1 unit from sale1 -> Stock increases +1, P&L Net Revenue = 560, Net COGS = 440, Gross Profit = 120
        List<SaleItem> sItems;
        try (Connection conn = DatabaseManager.getConnection()) {
            sItems = new SqliteSaleDao().findItemsBySaleId(conn, sale1.id());
        }
        int stockBeforeRet = inventoryService.getCurrentStock(tea.id());
        saleService.createSaleReturn(new SaleReturnCreateDto(
                biz.id(), sale1.id(), "1 packet returned by customer", owner.id(),
                List.of(new SaleReturnItemCreateDto(sItems.get(0).id(), 1))
        ));
        assertThat(inventoryService.getCurrentStock(tea.id())).isEqualTo(stockBeforeRet + 1);

        // 6. Operating expense (Rent BDT 50) reduces Net Profit, while GRN purchases (BDT 2,200) are NOT double-counted as expenses!
        expenseService.recordExpense(new ExpenseCreateDto(
                biz.id(), "RENT", Money.ofBdt(50), java.time.LocalDateTime.now(),
                "CASH", "Landlord", "Shop rent", owner.id()
        ));

        LocalDate today = LocalDate.now();
        ReportData pl = reportService.generateReport(new ReportCriteria(
                biz.id(), ReportType.BUSINESS_PROFIT_LOSS, today, today, null, null, null, false));
        assertThat(pl.summaryCards().get("Net Sales Revenue")).contains("560");
        assertThat(pl.summaryCards().get("Net COGS")).contains("440");
        assertThat(pl.summaryCards().get("Operating Expenses")).contains("50");
        assertThat(pl.summaryCards().get("Net Profit (নিট মুনাফা)")).contains("70"); // (560 - 440) - 50 = 70 BDT

        ReportData prodProfit = reportService.generateReport(new ReportCriteria(
                biz.id(), ReportType.PRODUCT_PROFITABILITY, today, today, null, "Beverages", null, false));
        assertThat(prodProfit.rowCount()).isEqualTo(1);
        assertThat(prodProfit.summaryCards().get("Total Gross Profit")).contains("120");

        ReportData catProfit = reportService.generateReport(new ReportCriteria(
                biz.id(), ReportType.CATEGORY_PROFITABILITY, today, today, null, null, null, false));
        assertThat(catProfit.rowCount()).isEqualTo(1);

        ReportData supAnalysis = reportService.generateReport(new ReportCriteria(
                biz.id(), ReportType.SUPPLIER_PURCHASE_ANALYSIS, today, today, null, null, null, false));
        assertThat(supAnalysis.rowCount()).isEqualTo(1);
        assertThat(supAnalysis.summaryCards().get("Total Purchases (GRN + Bills)")).contains("2,200");
    }
}

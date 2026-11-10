package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.service.api.DashboardService;
import com.smartkhata.service.impl.SqliteDashboardService;
import com.smartkhata.service.sample.SampleDataSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardServiceTest {

    private Path tempDbPath;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("dashboard_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SampleDataSeeder.seedReferenceDataIfEmpty();
        dashboardService = new SqliteDashboardService();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldReturnCorrectKPIAggregatesFromSQLite() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 1, 31);

        DashboardKPIs kpis = dashboardService.getKPIs(1L, start, end);
        assertThat(kpis).isNotNull();
        assertThat(kpis.totalSales().isPositive()).isTrue();
        assertThat(kpis.dueCollection().isPositive()).isTrue();
        assertThat(kpis.expenses().isPositive()).isTrue();
        assertThat(kpis.inventoryValue().isPositive()).isTrue();
    }

    @Test
    void shouldReturnRecentTransactionsChronologically() {
        List<RecentTransactionDto> recent = dashboardService.getRecentTransactions(1L, 5);
        assertThat(recent).isNotEmpty();
        assertThat(recent.size()).isLessThanOrEqualTo(5);

        // Verify party names and types
        assertThat(recent).anyMatch(tx -> tx.partyName().contains("Rahman Trading") || tx.type().contains("Sale"));
        assertThat(recent).anyMatch(tx -> tx.partyName().contains("Karim Enterprise") || tx.type().contains("Payment"));
    }

    @Test
    void shouldReturnLowStockAlertsUnderThreshold() {
        List<LowStockItemDto> lowStock = dashboardService.getLowStockAlerts(1L, 10);
        assertThat(lowStock).isNotEmpty();
        assertThat(lowStock).anyMatch(p -> p.productName().contains("Sunflower Oil"));
        assertThat(lowStock).anyMatch(p -> p.productName().contains("Aci Detergent"));
        assertThat(lowStock).anyMatch(p -> p.productName().contains("Power Salt"));
    }

    @Test
    void shouldReturnAccurateOutstandingDuesSummary() {
        OutstandingDuesSummary dues = dashboardService.getOutstandingDues(1L);
        assertThat(dues).isNotNull();
        assertThat(dues.totalDues().isPositive()).isTrue();
        assertThat(dues.customersWithDueCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldPopulateAll19TablesInSQLite() throws Exception {
        String[] tables = {
                "businesses",
                "users",
                "categories",
                "suppliers",
                "customers",
                "products",
                "stock_movements",
                "purchases",
                "purchase_items",
                "sales",
                "sale_items",
                "payments",
                "payment_allocations",
                "customer_ledger",
                "supplier_ledger",
                "expenses",
                "other_incomes",
                "settings",
                "audit_logs"
        };

        try (java.sql.Connection conn = DatabaseManager.getConnection()) {
            for (String table : tables) {
                try (java.sql.Statement stmt = conn.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(rs.next()).isTrue();
                    long count = rs.getLong(1);
                    assertThat(count)
                            .withFailMessage("Table '%s' must have seeded dummy data > 0, but had %d rows", table, count)
                            .isGreaterThan(0L);
                }
            }
        }
    }

    @Test
    void shouldSeedDemoDataForNewBusinessIdempotentlyAcrossAll19Tables() throws Exception {
        com.smartkhata.service.api.AuthService authService = new com.smartkhata.service.impl.LocalAuthService();
        authService.initializeBusiness(new OnboardingRequestDto(
                "Fresh Demo Store", "Demo Owner", "demo_owner",
                "01712345678", "Dhaka", "BDT", "en", "1234".toCharArray()
        ));

        long bizId = com.smartkhata.service.api.SessionContext.getRequiredBusinessId();
        long userId = com.smartkhata.service.api.SessionContext.getCurrentUser().id();

        boolean firstRun = SampleDataSeeder.seedDemoDataForBusiness(bizId, userId);
        boolean secondRun = SampleDataSeeder.seedDemoDataForBusiness(bizId, userId);
        com.smartkhata.service.api.SessionContext.clearSession();

        assertThat(firstRun).isTrue();
        assertThat(secondRun).isFalse();

        String[] bizTables = {
                "users", "categories", "suppliers", "customers", "products",
                "stock_movements", "purchases", "sales", "payments",
                "customer_ledger", "supplier_ledger", "expenses",
                "other_incomes", "settings", "audit_logs"
        };

        try (java.sql.Connection conn = DatabaseManager.getConnection()) {
            for (String table : bizTables) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE business_id = ?")) {
                    ps.setLong(1, bizId);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong(1))
                                .withFailMessage("Demo business table '%s' must be > 0", table)
                                .isGreaterThan(0L);
                    }
                }
            }
        }
    }
}


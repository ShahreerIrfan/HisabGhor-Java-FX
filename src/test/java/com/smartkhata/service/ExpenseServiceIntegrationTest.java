package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.ExpenseService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteExpenseService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for Cash Tracking: Expenses and Other Business Income (খরচ ও অন্যান্য আয় খাতা).
 * Verifies BDT 120 utility expense reflected once in total and breakdown,
 * BDT 500 service receipt reflected once in other income,
 * auditable non-destructive voids, date filtering, SQLite restart persistence,
 * multi-tenant cross-business isolation, and non-pollution of customer/supplier ledgers.
 */
class ExpenseServiceIntegrationTest {

    private Path tempDbPath;
    private ExpenseService expenseService;

    private Business biz1;
    private User owner1;

    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("expenses_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        expenseService = new SqliteExpenseService();

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
                            "VALUES (?, 'owner1', 'Al-Amin Hossain', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u1Id = rs.getLong(1);
                }
            }
            owner1 = new User(u1Id, b1Id, "owner1", "Al-Amin Hossain", "dummy", "dummy", UserRole.OWNER, true, null, null, null);

            // Business 2
            long b2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Madina Pharmacy', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b2Id = rs.getLong(1);
                }
            }
            biz2 = new Business(b2Id, "Madina Pharmacy", null, null, null, null, "BDT", null, null);

            // Owner 2
            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'owner2', 'Tariqul Islam', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "owner2", "Tariqul Islam", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });

        SessionContext.setSession(biz1, owner1);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
    }

    @Test
    void shouldRecordUtilityExpenseAndReflectInSummaryOnce() {
        // Given BDT 120 Utility expense
        ExpenseCreateDto dto = new ExpenseCreateDto(
                biz1.id(),
                "UTILITIES",
                Money.ofBdt(120.00),
                LocalDateTime.of(2026, 11, 8, 10, 30),
                "CASH",
                "DESCO Electricity Bill",
                "November meter recharge",
                owner1.id()
        );

        // When
        Expense recorded = expenseService.recordExpense(dto);

        // Then
        assertThat(recorded.id()).isNotNull();
        assertThat(recorded.category()).isEqualTo("UTILITIES");
        assertThat(recorded.amount()).isEqualTo(Money.ofBdt(120.00));
        assertThat(recorded.isReversed()).isFalse();

        Money total = expenseService.getTotalExpenses(biz1.id(), null, null);
        assertThat(total).isEqualTo(Money.ofBdt(120.00));

        List<ExpenseCategorySummaryDto> breakdown = expenseService.getExpenseBreakdownByCategory(biz1.id(), null, null);
        assertThat(breakdown).hasSize(1);
        assertThat(breakdown.get(0).category()).isEqualTo("UTILITIES");
        assertThat(breakdown.get(0).totalAmount()).isEqualTo(Money.ofBdt(120.00));
        assertThat(breakdown.get(0).count()).isEqualTo(1);
    }

    @Test
    void shouldRecordOtherIncomeAndReflectInSummaryOnce() {
        // Given BDT 500 service receipt
        OtherIncomeCreateDto dto = new OtherIncomeCreateDto(
                biz1.id(),
                "Consultation & BP Measurement Service",
                Money.ofBdt(500.00),
                LocalDateTime.of(2026, 11, 8, 11, 0),
                "BKASH",
                "Direct patient service fee",
                owner1.id()
        );

        // When
        OtherIncome recorded = expenseService.recordOtherIncome(dto);

        // Then
        assertThat(recorded.id()).isNotNull();
        assertThat(recorded.sourceName()).isEqualTo("Consultation & BP Measurement Service");
        assertThat(recorded.amount()).isEqualTo(Money.ofBdt(500.00));
        assertThat(recorded.isReversed()).isFalse();

        Money totalIncome = expenseService.getTotalOtherIncome(biz1.id(), null, null);
        assertThat(totalIncome).isEqualTo(Money.ofBdt(500.00));

        CashFlowSummaryDto summary = expenseService.getCashFlowSummary(biz1.id(), null, null);
        assertThat(summary.totalOtherIncome()).isEqualTo(Money.ofBdt(500.00));
        assertThat(summary.totalExpenses()).isEqualTo(Money.ZERO);
        assertThat(summary.netAuxiliaryFlow()).isEqualTo(Money.ofBdt(500.00));
        assertThat(summary.activeIncomeCount()).isEqualTo(1);
        assertThat(summary.activeExpenseCount()).isEqualTo(0);
    }

    @Test
    void shouldCalculateCategoryBreakdownAccurately() {
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "RENT", Money.ofBdt(5000.00), LocalDateTime.now(), "BANK", "Shop Landlord", "Nov Rent", owner1.id()
        ));
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(800.00), LocalDateTime.now(), "CASH", "DESCO", "Electric", owner1.id()
        ));
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(400.00), LocalDateTime.now(), "CASH", "WASA", "Water bill", owner1.id()
        ));
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "TRANSPORT", Money.ofBdt(350.00), LocalDateTime.now(), "CASH", "Rickshaw/CNG", "Medicine pickup", owner1.id()
        ));

        List<ExpenseCategorySummaryDto> breakdown = expenseService.getExpenseBreakdownByCategory(biz1.id(), null, null);
        assertThat(breakdown).hasSize(3);

        // RENT: 5000 (1 count)
        ExpenseCategorySummaryDto rentSummary = breakdown.stream()
                .filter(b -> "RENT".equals(b.category())).findFirst().orElseThrow();
        assertThat(rentSummary.totalAmount()).isEqualTo(Money.ofBdt(5000.00));
        assertThat(rentSummary.count()).isEqualTo(1);

        // UTILITIES: 800 + 400 = 1200 (2 count)
        ExpenseCategorySummaryDto utilSummary = breakdown.stream()
                .filter(b -> "UTILITIES".equals(b.category())).findFirst().orElseThrow();
        assertThat(utilSummary.totalAmount()).isEqualTo(Money.ofBdt(1200.00));
        assertThat(utilSummary.count()).isEqualTo(2);

        // TRANSPORT: 350 (1 count)
        ExpenseCategorySummaryDto transSummary = breakdown.stream()
                .filter(b -> "TRANSPORT".equals(b.category())).findFirst().orElseThrow();
        assertThat(transSummary.totalAmount()).isEqualTo(Money.ofBdt(350.00));
        assertThat(transSummary.count()).isEqualTo(1);

        Money total = expenseService.getTotalExpenses(biz1.id(), null, null);
        assertThat(total).isEqualTo(Money.ofBdt(6550.00));
    }

    @Test
    void shouldFilterExpensesByDateRange() {
        LocalDate today = LocalDate.of(2026, 11, 8);
        LocalDate yesterday = today.minusDays(1);
        LocalDate lastWeek = today.minusDays(7);

        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(100.00), lastWeek.atTime(10, 0), "CASH", null, null, owner1.id()
        ));
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "SALARY", Money.ofBdt(2000.00), yesterday.atTime(15, 0), "BANK", null, null, owner1.id()
        ));
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "MAINTENANCE", Money.ofBdt(300.00), today.atTime(12, 0), "CASH", null, null, owner1.id()
        ));

        // Filter yesterday to today
        List<Expense> filtered = expenseService.searchExpenses(biz1.id(), null, null, null, yesterday, today);
        assertThat(filtered).hasSize(2);
        assertThat(filtered).extracting(Expense::category).containsExactlyInAnyOrder("SALARY", "MAINTENANCE");

        // Total in range
        Money rangeTotal = expenseService.getTotalExpenses(biz1.id(), yesterday, today);
        assertThat(rangeTotal).isEqualTo(Money.ofBdt(2300.00));
    }

    @Test
    void shouldVoidExpenseWithAuditReasonAndExcludeFromTotals() {
        Expense exp = expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "TRANSPORT", Money.ofBdt(450.00), LocalDateTime.now(), "CASH", "CNG Driver", "Wrong entry", owner1.id()
        ));

        assertThat(expenseService.getTotalExpenses(biz1.id(), null, null)).isEqualTo(Money.ofBdt(450.00));

        // Void the expense
        expenseService.voidExpense(exp.id(), "Wrong amount entered by cashier", owner1.id());

        // Total should now be 0
        assertThat(expenseService.getTotalExpenses(biz1.id(), null, null)).isEqualTo(Money.ZERO);

        // Verify status in DB
        Optional<Expense> reloaded = expenseService.getExpenseById(exp.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isReversed()).isTrue();
        assertThat(reloaded.get().reversalReason()).isEqualTo("Wrong amount entered by cashier");
        assertThat(reloaded.get().reversedAt()).isNotNull();

        // Second void attempt should fail
        assertThatThrownBy(() -> expenseService.voidExpense(exp.id(), "Duplicate attempt", owner1.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been voided");
    }

    @Test
    void shouldVoidOtherIncomeWithAuditReason() {
        OtherIncome inc = expenseService.recordOtherIncome(new OtherIncomeCreateDto(
                biz1.id(), "Old Carton Sale", Money.ofBdt(250.00), LocalDateTime.now(), "CASH", "Recycling shop", owner1.id()
        ));

        assertThat(expenseService.getTotalOtherIncome(biz1.id(), null, null)).isEqualTo(Money.ofBdt(250.00));

        // Void income
        expenseService.voidOtherIncome(inc.id(), "Carton buyer cancelled and returned money", owner1.id());

        // Total should now be 0
        assertThat(expenseService.getTotalOtherIncome(biz1.id(), null, null)).isEqualTo(Money.ZERO);

        Optional<OtherIncome> reloaded = expenseService.getOtherIncomeById(inc.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isReversed()).isTrue();
        assertThat(reloaded.get().reversalReason()).isEqualTo("Carton buyer cancelled and returned money");

        // Second void attempt should fail
        assertThatThrownBy(() -> expenseService.voidOtherIncome(inc.id(), "Retry", owner1.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been voided");
    }

    @Test
    void shouldPersistAcrossDatabaseRestart() {
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "RENT", Money.ofBdt(15000.00), LocalDateTime.now(), "BANK", "Landlord", null, owner1.id()
        ));
        expenseService.recordOtherIncome(new OtherIncomeCreateDto(
                biz1.id(), "Scrap Furniture", Money.ofBdt(3000.00), LocalDateTime.now(), "CASH", null, owner1.id()
        ));

        // Simulate application restart
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.setSession(biz1, owner1);

        ExpenseService restartedService = new SqliteExpenseService();
        Money expTotal = restartedService.getTotalExpenses(biz1.id(), null, null);
        Money incTotal = restartedService.getTotalOtherIncome(biz1.id(), null, null);

        assertThat(expTotal).isEqualTo(Money.ofBdt(15000.00));
        assertThat(incTotal).isEqualTo(Money.ofBdt(3000.00));

        CashFlowSummaryDto summary = restartedService.getCashFlowSummary(biz1.id(), null, null);
        assertThat(summary.netAuxiliaryFlow()).isEqualTo(Money.ofBdt(-12000.00));
    }

    @Test
    void shouldEnforceCrossBusinessIsolationForExpenses() {
        Expense exp1 = expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "UTILITIES", Money.ofBdt(600.00), LocalDateTime.now(), "CASH", null, null, owner1.id()
        ));

        // Switch session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Business 2 attempting to record with Business 1 id
        assertThatThrownBy(() -> expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "RENT", Money.ofBdt(2000.00), LocalDateTime.now(), "CASH", null, null, owner2.id()
        ))).isInstanceOf(SecurityException.class);

        // Business 2 attempting to search Business 1 expenses
        assertThatThrownBy(() -> expenseService.searchExpenses(biz1.id(), null, null, null, null, null))
                .isInstanceOf(SecurityException.class);

        // Business 2 attempting to void Business 1 expense
        assertThatThrownBy(() -> expenseService.voidExpense(exp1.id(), "Unauthorized void", owner2.id()))
                .isInstanceOf(SecurityException.class);

        // Business 2 totals should be completely isolated (0.00)
        assertThat(expenseService.getTotalExpenses(biz2.id(), null, null)).isEqualTo(Money.ZERO);
    }

    @Test
    void shouldRejectExpenseOperationsWhenUnauthenticated() {
        SessionContext.clearSession();

        assertThatThrownBy(() -> expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "RENT", Money.ofBdt(500.00), LocalDateTime.now(), "CASH", null, null, 1L
        ))).isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> expenseService.searchExpenses(biz1.id(), null, null, null, null, null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldEnsureExpensesDoNotAffectCustomerOrSupplierLedgers() {
        // Record both expense and other income
        expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "MAINTENANCE", Money.ofBdt(1200.00), LocalDateTime.now(), "CASH", "AC Technician", null, owner1.id()
        ));
        expenseService.recordOtherIncome(new OtherIncomeCreateDto(
                biz1.id(), "Packaging Box Sale", Money.ofBdt(800.00), LocalDateTime.now(), "CASH", null, owner1.id()
        ));

        // Verify customer_ledger and supplier_ledger remain empty
        TransactionManager.runInTransaction(conn -> {
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customer_ledger")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM supplier_ledger")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM stock_movements")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        });
    }

    @Test
    void shouldUpdateActiveExpenseAndIncomeAndAllowDeletingOnlyVoidedEntries() {
        Expense exp = expenseService.recordExpense(new ExpenseCreateDto(
                biz1.id(), "RENT", Money.ofBdt(1000.00), LocalDateTime.now(), "CASH", "Old Payee", "Initial", owner1.id()
        ));
        Expense updatedExp = expenseService.updateExpense(new ExpenseUpdateDto(
                exp.id(), "UTILITIES", Money.ofBdt(1250.00), exp.expenseDate(), "BKASH", "DESCO", "Updated bill"
        ));
        assertThat(updatedExp.category()).isEqualTo("UTILITIES");
        assertThat(updatedExp.amount()).isEqualTo(Money.ofBdt(1250.00));
        assertThat(updatedExp.payeeName()).isEqualTo("DESCO");
        assertThat(expenseService.getTotalExpenses(biz1.id(), null, null)).isEqualTo(Money.ofBdt(1250.00));

        assertThatThrownBy(() -> expenseService.deleteVoidedExpense(exp.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only VOIDED");

        expenseService.voidExpense(exp.id(), "Wrong entry", owner1.id());
        assertThatThrownBy(() -> expenseService.updateExpense(new ExpenseUpdateDto(
                exp.id(), "RENT", Money.ofBdt(500.00), exp.expenseDate(), "CASH", null, null
        ))).isInstanceOf(IllegalStateException.class);

        expenseService.deleteVoidedExpense(exp.id());
        assertThat(expenseService.getExpenseById(exp.id())).isEmpty();

        OtherIncome inc = expenseService.recordOtherIncome(new OtherIncomeCreateDto(
                biz1.id(), "Scrap Paper", Money.ofBdt(300.00), LocalDateTime.now(), "CASH", "Note 1", owner1.id()
        ));
        OtherIncome updatedInc = expenseService.updateOtherIncome(new OtherIncomeUpdateDto(
                inc.id(), "Scrap Carton", Money.ofBdt(450.00), inc.incomeDate(), "NAGAD", "Updated note"
        ));
        assertThat(updatedInc.sourceName()).isEqualTo("Scrap Carton");
        assertThat(updatedInc.amount()).isEqualTo(Money.ofBdt(450.00));

        assertThatThrownBy(() -> expenseService.deleteVoidedOtherIncome(inc.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only VOIDED");

        expenseService.voidOtherIncome(inc.id(), "Cancelled", owner1.id());
        expenseService.deleteVoidedOtherIncome(inc.id());
        assertThat(expenseService.getOtherIncomeById(inc.id())).isEmpty();
    }
}

package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.entity.SupplierLedgerEntry;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteSupplierService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for Supplier Management & Supplier Ledger (সরবরাহকারী পাওনা খাতা).
 * Verifies supplier CRUD, traceable opening payables in supplier_ledger, search filtering,
 * archival preservation, multi-tenant isolation, role authorization, and accounting sign conventions.
 */
class SupplierServiceIntegrationTest {

    private Path tempDbPath;
    private SupplierService supplierService;
    private Business biz1;
    private User owner1;
    private User staff1;
    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("supplier_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        supplierService = new SqliteSupplierService();

        // Seed 2 distinct businesses for multi-tenant isolation testing
        TransactionManager.runInTransaction(conn -> {
            // Business 1
            long b1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Meghna Trade Center', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b1Id = rs.getLong(1);
                }
            }
            biz1 = new Business(b1Id, "Meghna Trade Center", null, null, null, null, "BDT", null, null);

            // Owner for Biz 1
            long u1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'meghna_owner', 'Mustafa Kamal', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u1Id = rs.getLong(1);
                }
            }
            owner1 = new User(u1Id, b1Id, "meghna_owner", "Mustafa Kamal", "dummy", "dummy", UserRole.OWNER, true, null, null, null);

            // Staff for Biz 1
            long staffId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'meghna_staff', 'Jamil Ahmed', 'dummy', 'dummy', 'CASHIER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    staffId = rs.getLong(1);
                }
            }
            staff1 = new User(staffId, b1Id, "meghna_staff", "Jamil Ahmed", "dummy", "dummy", UserRole.CASHIER, true, null, null, null);

            // Business 2
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

            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'sylhet_owner', 'Enamul Haque', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "sylhet_owner", "Enamul Haque", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreateSupplierWithTraceableOpeningPayableInLedger() {
        SessionContext.setSession(biz1, owner1);

        Money openingPayable = Money.ofBdt(45000.75);

        SupplierCreateDto dto = new SupplierCreateDto(
                biz1.id(),
                "Square Consumer Products Ltd",
                "Golam Sarwar",
                "01711223344",
                "Tejgaon I/A, Dhaka",
                openingPayable,
                "Authorized distributor"
        );

        Supplier created = supplierService.createSupplier(dto);

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Square Consumer Products Ltd");
        assertThat(created.contactPerson()).isEqualTo("Golam Sarwar");
        assertThat(created.phone()).isEqualTo("01711223344");
        assertThat(created.address()).isEqualTo("Tejgaon I/A, Dhaka");
        assertThat(created.openingBalance()).isEqualTo(openingPayable);
        assertThat(created.isArchived()).isFalse();

        // Check supplier ledger has the opening balance entry
        // Payables sign convention: credit increases payable balance
        List<SupplierLedgerEntry> ledger = supplierService.getSupplierLedger(created.id());
        assertThat(ledger).hasSize(1);

        SupplierLedgerEntry openingEntry = ledger.get(0);
        assertThat(openingEntry.entryType()).isEqualTo("OPENING_BALANCE");
        assertThat(openingEntry.credit()).isEqualTo(openingPayable); // Credit: business owes supplier
        assertThat(openingEntry.debit()).isEqualTo(Money.ZERO);
        assertThat(openingEntry.runningBalance()).isEqualTo(openingPayable);
        assertThat(openingEntry.notes()).contains("Opening Payable");

        // Verify search returns current payable matching opening balance
        List<SupplierListItemDto> searchResults = supplierService.searchSuppliers(biz1.id(), "Square", false);
        assertThat(searchResults).hasSize(1);
        assertThat(searchResults.get(0).currentPayable()).isEqualTo(openingPayable);
    }

    @Test
    void shouldCreateSupplierWithZeroOpeningBalanceWithoutLedgerEntry() {
        SessionContext.setSession(biz1, owner1);

        SupplierCreateDto dto = new SupplierCreateDto(
                biz1.id(),
                "Fresh Paper Mills",
                "Rashed Khan",
                "01800000000",
                "Narayanganj",
                Money.ZERO,
                null
        );

        Supplier created = supplierService.createSupplier(dto);
        assertThat(created.openingBalance()).isEqualTo(Money.ZERO);

        // Zero opening balance keeps ledger clean until real purchase invoices appear
        List<SupplierLedgerEntry> ledger = supplierService.getSupplierLedger(created.id());
        assertThat(ledger).isEmpty();

        List<SupplierListItemDto> results = supplierService.searchSuppliers(biz1.id(), "Fresh Paper", false);
        assertThat(results.get(0).currentPayable()).isEqualTo(Money.ZERO);
    }

    @Test
    void shouldRejectDuplicateSupplierNameInSameBusiness() {
        SessionContext.setSession(biz1, owner1);

        SupplierCreateDto first = new SupplierCreateDto(
                biz1.id(), "Akij Food & Beverage", "Salam", "01711111111", null, Money.ZERO, null
        );
        supplierService.createSupplier(first);

        SupplierCreateDto duplicate = new SupplierCreateDto(
                biz1.id(), "Akij Food & Beverage", "Different Person", "01722222222", null, Money.ZERO, null
        );

        assertThatThrownBy(() -> supplierService.createSupplier(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void shouldUpdateSupplierDetailsWithoutMutatingOpeningBalance() {
        SessionContext.setSession(biz1, owner1);

        Supplier created = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Original Vendor Ltd", "Mr. A", "01911111111", "Address 1",
                Money.ofBdt(18000.00), "Initial vendor note"
        ));

        SupplierUpdateDto updateDto = new SupplierUpdateDto(
                created.id(),
                "Updated Vendor Group",
                "Mr. B (General Manager)",
                "01999999999",
                "Updated Factory Road 4",
                "Updated credit terms 30 days"
        );

        Supplier updated = supplierService.updateSupplier(updateDto);

        assertThat(updated.name()).isEqualTo("Updated Vendor Group");
        assertThat(updated.contactPerson()).isEqualTo("Mr. B (General Manager)");
        assertThat(updated.phone()).isEqualTo("01999999999");
        assertThat(updated.address()).isEqualTo("Updated Factory Road 4");
        assertThat(updated.notes()).isEqualTo("Updated credit terms 30 days");
        // Opening balance must remain locked to preserve audit trail
        assertThat(updated.openingBalance()).isEqualTo(Money.ofBdt(18000.00));

        // Ledger must still retain the immutable opening payable
        List<SupplierLedgerEntry> ledger = supplierService.getSupplierLedger(created.id());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).runningBalance()).isEqualTo(Money.ofBdt(18000.00));
    }

    @Test
    void shouldSearchSuppliersByNameContactPersonAndPhone() {
        SessionContext.setSession(biz1, owner1);

        supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Pran Foods Ltd", "Faruk Hossain", "01755554444", "Ghorashal", Money.ZERO, null
        ));
        supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "ACI Logistics", "Mizan Chowdhury", "01877778888", "Tejgaon", Money.ZERO, null
        ));

        // Search by company name
        List<SupplierListItemDto> nameResults = supplierService.searchSuppliers(biz1.id(), "Pran", false);
        assertThat(nameResults).hasSize(1);
        assertThat(nameResults.get(0).name()).isEqualTo("Pran Foods Ltd");

        // Search by contact person
        List<SupplierListItemDto> contactResults = supplierService.searchSuppliers(biz1.id(), "Mizan", false);
        assertThat(contactResults).hasSize(1);
        assertThat(contactResults.get(0).name()).isEqualTo("ACI Logistics");

        // Search by phone digits
        List<SupplierListItemDto> phoneResults = supplierService.searchSuppliers(biz1.id(), "5555", false);
        assertThat(phoneResults).hasSize(1);
        assertThat(phoneResults.get(0).name()).isEqualTo("Pran Foods Ltd");
    }

    @Test
    void shouldArchiveAndRestoreSupplierPreservingLedgerIntegrity() {
        SessionContext.setSession(biz1, owner1);

        Supplier supplier = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Discontinued Supplier", "Contact", "01511111111", null,
                Money.ofBdt(22000.00), null
        ));

        // 1. Archive supplier (Owner permission required)
        supplierService.archiveSupplier(supplier.id());

        // Default search hides archived vendors
        List<SupplierListItemDto> activeList = supplierService.searchSuppliers(biz1.id(), "Discontinued", false);
        assertThat(activeList).isEmpty();

        // Search with includeArchived = true displays it
        List<SupplierListItemDto> allList = supplierService.searchSuppliers(biz1.id(), "Discontinued", true);
        assertThat(allList).hasSize(1);
        assertThat(allList.get(0).isArchived()).isTrue();
        assertThat(allList.get(0).currentPayable()).isEqualTo(Money.ofBdt(22000.00));

        // Historical ledger entries must remain completely preserved
        List<SupplierLedgerEntry> ledger = supplierService.getSupplierLedger(supplier.id());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).runningBalance()).isEqualTo(Money.ofBdt(22000.00));

        // 2. Restore supplier
        supplierService.restoreSupplier(supplier.id());
        List<SupplierListItemDto> restoredList = supplierService.searchSuppliers(biz1.id(), "Discontinued", false);
        assertThat(restoredList).hasSize(1);
        assertThat(restoredList.get(0).isArchived()).isFalse();
    }

    @Test
    void shouldCalculateTotalOutstandingPayablesAcrossAllSuppliers() {
        SessionContext.setSession(biz1, owner1);

        supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Supplier A", null, null, null, Money.ofBdt(30000.00), null
        ));
        supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Supplier B", null, null, null, Money.ofBdt(45500.25), null
        ));
        supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Supplier C (Fully Settled)", null, null, null, Money.ZERO, null
        ));

        Money totalPayables = supplierService.getTotalOutstandingPayables(biz1.id());
        assertThat(totalPayables).isEqualTo(Money.ofBdt(75500.25));
    }

    @Test
    void shouldEnforceCrossBusinessIsolation() {
        // Create supplier in Business 1
        SessionContext.setSession(biz1, owner1);
        Supplier b1Supplier = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Biz 1 Exclusive Vendor", "Person", "01711111111", null, Money.ofBdt(10000.00), null
        ));

        // Switch active session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Business 2 cannot see Business 1's supplier in search
        List<SupplierListItemDto> b2List = supplierService.searchSuppliers(biz2.id(), "Exclusive", false);
        assertThat(b2List).isEmpty();

        // Business 2 cannot query using Business 1's ID
        assertThatThrownBy(() -> supplierService.searchSuppliers(biz1.id(), null, false))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot fetch Business 1's supplier by ID
        assertThatThrownBy(() -> supplierService.getSupplierById(b1Supplier.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot fetch Business 1's supplier ledger
        assertThatThrownBy(() -> supplierService.getSupplierLedger(b1Supplier.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot modify Business 1's supplier
        assertThatThrownBy(() -> supplierService.updateSupplier(new SupplierUpdateDto(
                b1Supplier.id(), "Hijacked Name", null, null, null, null
        ))).isInstanceOf(SecurityException.class);

        // Business 2 cannot archive Business 1's supplier
        assertThatThrownBy(() -> supplierService.archiveSupplier(b1Supplier.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectSupplierOperationsWhenUnauthenticatedOrLocked() {
        // 1. Unauthenticated access
        SessionContext.clearSession();

        SupplierCreateDto dto = new SupplierCreateDto(
                biz1.id(), "Anonymous Supplier", null, null, null, Money.ZERO, null
        );
        assertThatThrownBy(() -> supplierService.createSupplier(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("User is not authenticated");

        // 2. Locked session access
        SessionContext.setSession(biz1, owner1);
        SessionContext.lock();

        assertThatThrownBy(() -> supplierService.createSupplier(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("session is locked");
    }

    @Test
    void shouldAllowStaffToCreateAndSearchSuppliersButRestrictArchiveToOwner() {
        // Staff cashier user logs in
        SessionContext.setSession(biz1, staff1);

        Supplier staffCreated = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Staff Added Vendor", "Agent", "01733333333", null, Money.ZERO, null
        ));
        assertThat(staffCreated).isNotNull();

        List<SupplierListItemDto> results = supplierService.searchSuppliers(biz1.id(), "Staff Added", false);
        assertThat(results).hasSize(1);

        // Non-owner staff attempting to archive supplier must be rejected
        assertThatThrownBy(() -> supplierService.archiveSupplier(staffCreated.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Action requires OWNER role permissions");
    }

    @Test
    void shouldCorrectlyHandlePayableSignConventionInLedger() {
        SessionContext.setSession(biz1, owner1);

        // Opening balance creates a CREDIT entry of BDT 10,000 (increases payable)
        Supplier supplier = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Sign Test Vendor", null, null, null, Money.ofBdt(10000.00), null
        ));

        List<SupplierLedgerEntry> entries = supplierService.getSupplierLedger(supplier.id());
        assertThat(entries).hasSize(1);
        SupplierLedgerEntry entry = entries.get(0);

        // In supplier ledger: credit increases payable balance, debit decreases payable balance
        assertThat(entry.credit()).isEqualTo(Money.ofBdt(10000.00));
        assertThat(entry.debit()).isEqualTo(Money.ZERO);
        assertThat(entry.runningBalance()).isEqualTo(Money.ofBdt(10000.00));

        Money currentPayable = supplierService.getTotalOutstandingPayables(biz1.id());
        assertThat(currentPayable).isEqualTo(Money.ofBdt(10000.00));
    }

    @Test
    void shouldDeleteCleanSupplierAndRejectWhenFinancialHistoryExists() {
        SessionContext.setSession(biz1, owner1);

        Supplier cleanSupplier = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Removable Vendor", "Agent", "01811112222", "Mirpur",
                Money.ofBdt(1200.00), "Only opening balance"
        ));

        supplierService.deleteSupplier(cleanSupplier.id());
        assertThat(supplierService.getSupplierById(cleanSupplier.id())).isEmpty();
        assertThat(supplierService.getTotalOutstandingPayables(biz1.id())).isEqualTo(Money.ZERO);

        Supplier linkedSupplier = supplierService.createSupplier(new SupplierCreateDto(
                biz1.id(), "Linked Vendor", null, null, null, Money.ZERO, null
        ));
        TransactionManager.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO supplier_ledger (business_id, supplier_id, entry_date, entry_type, credit_paisha, debit_paisha, running_balance_paisha, notes) " +
                            "VALUES (?, ?, datetime('now', 'localtime'), 'PURCHASE_BILL', 20000, 0, 20000, 'Linked purchase')")) {
                ps.setLong(1, biz1.id());
                ps.setLong(2, linkedSupplier.id());
                ps.executeUpdate();
            }
        });

        assertThatThrownBy(() -> supplierService.deleteSupplier(linkedSupplier.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Please Archive it instead");
    }
}


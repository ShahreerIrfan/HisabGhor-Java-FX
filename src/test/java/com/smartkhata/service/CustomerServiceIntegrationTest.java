package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.CustomerLedgerEntry;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteCustomerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for Customer Management & Customer Ledger (গ্রাহক বাকি খাতা).
 * Verifies customer CRUD, traceable opening balances in customer_ledger, search filtering,
 * archival preservation, cross-business isolation, and role authorization guards.
 */
class CustomerServiceIntegrationTest {

    private Path tempDbPath;
    private CustomerService customerService;
    private Business biz1;
    private User owner1;
    private User staff1;
    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("customer_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        customerService = new SqliteCustomerService();

        // Seed 2 distinct businesses for multi-tenant isolation testing
        TransactionManager.runInTransaction(conn -> {
            // Business 1
            long b1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Rahim Grocery', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b1Id = rs.getLong(1);
                }
            }
            biz1 = new Business(b1Id, "Rahim Grocery", null, null, null, null, "BDT", null, null);

            // Owner for Biz 1
            long u1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'rahim_owner', 'Rahim Uddin', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u1Id = rs.getLong(1);
                }
            }
            owner1 = new User(u1Id, b1Id, "rahim_owner", "Rahim Uddin", "dummy", "dummy", UserRole.OWNER, true, null, null, null);

            // Cashier (staff) for Biz 1
            long staffId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'karim_cashier', 'Karim Cashier', 'dummy', 'dummy', 'CASHIER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    staffId = rs.getLong(1);
                }
            }
            staff1 = new User(staffId, b1Id, "karim_cashier", "Karim Cashier", "dummy", "dummy", UserRole.CASHIER, true, null, null, null);

            // Business 2
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

            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'chittagong_owner', 'Alamgir Kabir', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "chittagong_owner", "Alamgir Kabir", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreateCustomerWithTraceableOpeningBalanceInLedger() {
        SessionContext.setSession(biz1, owner1);

        Money creditLimit = Money.ofBdt(50000.00);
        Money openingBalance = Money.ofBdt(12500.50);

        CustomerCreateDto dto = new CustomerCreateDto(
                biz1.id(),
                "Hasan Enterprise",
                "01811223344",
                "Kawran Bazar, Dhaka",
                creditLimit,
                openingBalance,
                "VIP wholesale customer"
        );

        Customer created = customerService.createCustomer(dto);

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Hasan Enterprise");
        assertThat(created.phone()).isEqualTo("01811223344");
        assertThat(created.address()).isEqualTo("Kawran Bazar, Dhaka");
        assertThat(created.creditLimit()).isEqualTo(creditLimit);
        assertThat(created.openingBalance()).isEqualTo(openingBalance);
        assertThat(created.isArchived()).isFalse();

        // Check customer ledger has the opening balance entry
        List<CustomerLedgerEntry> ledger = customerService.getCustomerLedger(created.id());
        assertThat(ledger).hasSize(1);

        CustomerLedgerEntry openingEntry = ledger.get(0);
        assertThat(openingEntry.entryType()).isEqualTo("OPENING_BALANCE");
        assertThat(openingEntry.debit()).isEqualTo(openingBalance);
        assertThat(openingEntry.credit()).isEqualTo(Money.ZERO);
        assertThat(openingEntry.runningBalance()).isEqualTo(openingBalance);
        assertThat(openingEntry.notes()).contains("Opening");

        // Verify search returns current due matching opening balance
        List<CustomerListItemDto> searchResults = customerService.searchCustomers(biz1.id(), "Hasan", false);
        assertThat(searchResults).hasSize(1);
        assertThat(searchResults.get(0).currentDue()).isEqualTo(openingBalance);
    }

    @Test
    void shouldCreateCustomerWithZeroOpeningBalanceWithoutLedgerEntry() {
        SessionContext.setSession(biz1, owner1);

        CustomerCreateDto dto = new CustomerCreateDto(
                biz1.id(),
                "Zero Due Customer",
                "01700000000",
                "Mirpur 10",
                Money.ofBdt(10000.00),
                Money.ZERO,
                null
        );

        Customer created = customerService.createCustomer(dto);
        assertThat(created.openingBalance()).isEqualTo(Money.ZERO);

        // Zero opening balance should show clean zero/empty ledger until real sales occur
        List<CustomerLedgerEntry> ledger = customerService.getCustomerLedger(created.id());
        assertThat(ledger).isEmpty();

        List<CustomerListItemDto> results = customerService.searchCustomers(biz1.id(), "Zero Due", false);
        assertThat(results.get(0).currentDue()).isEqualTo(Money.ZERO);
    }

    @Test
    void shouldRejectDuplicateCustomerNameInSameBusiness() {
        SessionContext.setSession(biz1, owner1);

        CustomerCreateDto first = new CustomerCreateDto(
                biz1.id(), "Rafiq Store", "01711111111", null, Money.ZERO, Money.ZERO, null
        );
        customerService.createCustomer(first);

        CustomerCreateDto duplicate = new CustomerCreateDto(
                biz1.id(), "Rafiq Store", "01722222222", null, Money.ZERO, Money.ZERO, null
        );

        assertThatThrownBy(() -> customerService.createCustomer(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void shouldUpdateCustomerDetailsWithoutMutatingOpeningBalance() {
        SessionContext.setSession(biz1, owner1);

        Customer created = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Original Name", "01911111111", "Address 1",
                Money.ofBdt(20000.00), Money.ofBdt(5000.00), "Initial note"
        ));

        CustomerUpdateDto updateDto = new CustomerUpdateDto(
                created.id(),
                "Updated Name Ltd",
                "01999999999",
                "Updated Address 2",
                Money.ofBdt(35000.00),
                "Updated note"
        );

        Customer updated = customerService.updateCustomer(updateDto);

        assertThat(updated.name()).isEqualTo("Updated Name Ltd");
        assertThat(updated.phone()).isEqualTo("01999999999");
        assertThat(updated.address()).isEqualTo("Updated Address 2");
        assertThat(updated.creditLimit()).isEqualTo(Money.ofBdt(35000.00));
        assertThat(updated.notes()).isEqualTo("Updated note");
        // Opening balance must remain locked to preserve ledger auditability
        assertThat(updated.openingBalance()).isEqualTo(Money.ofBdt(5000.00));

        // Ledger must still have the initial opening balance
        List<CustomerLedgerEntry> ledger = customerService.getCustomerLedger(created.id());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).runningBalance()).isEqualTo(Money.ofBdt(5000.00));
    }

    @Test
    void shouldSearchCustomersByNameAndPhone() {
        SessionContext.setSession(biz1, owner1);

        customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Mahmudul Hasan", "01712345678", "Dhaka", Money.ZERO, Money.ZERO, null
        ));
        customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Kamrul Islam", "01887654321", "Chittagong", Money.ZERO, Money.ZERO, null
        ));

        // Search by partial name
        List<CustomerListItemDto> nameResults = customerService.searchCustomers(biz1.id(), "Mahmud", false);
        assertThat(nameResults).hasSize(1);
        assertThat(nameResults.get(0).name()).isEqualTo("Mahmudul Hasan");

        // Search by phone digits
        List<CustomerListItemDto> phoneResults = customerService.searchCustomers(biz1.id(), "8765", false);
        assertThat(phoneResults).hasSize(1);
        assertThat(phoneResults.get(0).name()).isEqualTo("Kamrul Islam");
    }

    @Test
    void shouldArchiveAndRestoreCustomerPreservingLedgerIntegrity() {
        SessionContext.setSession(biz1, owner1);

        Customer customer = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Archive Target", "01511111111", null,
                Money.ZERO, Money.ofBdt(7500.00), null
        ));

        // 1. Archive customer (Owner operation)
        customerService.archiveCustomer(customer.id());

        // Normal search should hide archived customer
        List<CustomerListItemDto> activeList = customerService.searchCustomers(biz1.id(), "Archive Target", false);
        assertThat(activeList).isEmpty();

        // Search with includeArchived = true should display it
        List<CustomerListItemDto> allList = customerService.searchCustomers(biz1.id(), "Archive Target", true);
        assertThat(allList).hasSize(1);
        assertThat(allList.get(0).isArchived()).isTrue();
        assertThat(allList.get(0).currentDue()).isEqualTo(Money.ofBdt(7500.00));

        // Historical ledger entries must be fully intact
        List<CustomerLedgerEntry> ledger = customerService.getCustomerLedger(customer.id());
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).runningBalance()).isEqualTo(Money.ofBdt(7500.00));

        // 2. Restore customer
        customerService.restoreCustomer(customer.id());
        List<CustomerListItemDto> restoredList = customerService.searchCustomers(biz1.id(), "Archive Target", false);
        assertThat(restoredList).hasSize(1);
        assertThat(restoredList.get(0).isArchived()).isFalse();
    }

    @Test
    void shouldCalculateTotalOutstandingDuesAcrossAllCustomers() {
        SessionContext.setSession(biz1, owner1);

        customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Customer A", null, null, Money.ZERO, Money.ofBdt(10000.00), null
        ));
        customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Customer B", null, null, Money.ZERO, Money.ofBdt(25500.50), null
        ));
        customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Customer C (Zero Due)", null, null, Money.ZERO, Money.ZERO, null
        ));

        Money totalDues = customerService.getTotalOutstandingDues(biz1.id());
        assertThat(totalDues).isEqualTo(Money.ofBdt(35500.50));
    }

    @Test
    void shouldEnforceCrossBusinessIsolation() {
        // Create customer in Business 1
        SessionContext.setSession(biz1, owner1);
        Customer b1Customer = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Biz 1 Client", "01711111111", null, Money.ZERO, Money.ofBdt(3000.00), null
        ));

        // Switch active session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Business 2 cannot query Business 1's customers via search
        List<CustomerListItemDto> b2List = customerService.searchCustomers(biz2.id(), "Biz 1", false);
        assertThat(b2List).isEmpty();

        // Business 2 cannot search with Business 1's ID (cross-tenant denial)
        assertThatThrownBy(() -> customerService.searchCustomers(biz1.id(), null, false))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot access Business 1's customer directly by ID
        assertThatThrownBy(() -> customerService.getCustomerById(b1Customer.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot access Business 1's customer ledger
        assertThatThrownBy(() -> customerService.getCustomerLedger(b1Customer.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot modify Business 1's customer
        assertThatThrownBy(() -> customerService.updateCustomer(new CustomerUpdateDto(
                b1Customer.id(), "Hacked Name", null, null, Money.ZERO, null
        ))).isInstanceOf(SecurityException.class);

        // Business 2 cannot archive Business 1's customer
        assertThatThrownBy(() -> customerService.archiveCustomer(b1Customer.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectCustomerOperationsWhenUnauthenticatedOrLocked() {
        // 1. Unauthenticated access
        SessionContext.clearSession();

        CustomerCreateDto dto = new CustomerCreateDto(
                biz1.id(), "Anonymous Attempt", null, null, Money.ZERO, Money.ZERO, null
        );
        assertThatThrownBy(() -> customerService.createCustomer(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("User is not authenticated");

        // 2. Locked session access
        SessionContext.setSession(biz1, owner1);
        SessionContext.lock();

        assertThatThrownBy(() -> customerService.createCustomer(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("session is locked");
    }

    @Test
    void shouldAllowStaffToCreateAndSearchCustomersButRestrictArchiveToOwner() {
        // Staff user logs in
        SessionContext.setSession(biz1, staff1);

        Customer staffCreated = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Staff Client", "01733333333", null, Money.ZERO, Money.ZERO, null
        ));
        assertThat(staffCreated).isNotNull();

        List<CustomerListItemDto> results = customerService.searchCustomers(biz1.id(), "Staff Client", false);
        assertThat(results).hasSize(1);

        // Non-owner staff attempting to archive customer must be rejected
        assertThatThrownBy(() -> customerService.archiveCustomer(staffCreated.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Action requires OWNER role permissions");

        // Non-owner staff attempting to delete customer must also be rejected
        assertThatThrownBy(() -> customerService.deleteCustomer(staffCreated.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Action requires OWNER role permissions");
    }

    @Test
    void shouldDeleteCleanCustomerAndRejectWhenFinancialHistoryExists() {
        SessionContext.setSession(biz1, owner1);

        Customer cleanCustomer = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Removable Customer", "01744445555", "Dhanmondi",
                Money.ZERO, Money.ofBdt(500.00), "Only opening balance"
        ));

        customerService.deleteCustomer(cleanCustomer.id());
        assertThat(customerService.getCustomerById(cleanCustomer.id())).isEmpty();
        assertThat(customerService.getTotalOutstandingDues(biz1.id())).isEqualTo(Money.ZERO);

        Customer linkedCustomer = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Linked History Customer", "01755556666", null,
                Money.ZERO, Money.ZERO, null
        ));
        TransactionManager.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO customer_ledger (business_id, customer_id, entry_date, entry_type, debit_paisha, credit_paisha, running_balance_paisha, notes) " +
                            "VALUES (?, ?, datetime('now', 'localtime'), 'SALE_INVOICE', 10000, 0, 10000, 'Linked sale')")) {
                ps.setLong(1, biz1.id());
                ps.setLong(2, linkedCustomer.id());
                ps.executeUpdate();
            }
        });

        assertThatThrownBy(() -> customerService.deleteCustomer(linkedCustomer.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Please Archive it instead");
    }
}

package com.smartkhata.service;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.PreferencesService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteCustomerService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqlitePreferencesService;
import com.smartkhata.util.I18nSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferencesServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private PreferencesService preferencesService;
    private SqliteCustomerService customerService;
    private SqliteInventoryService inventoryService;

    private Business biz1;
    private Business biz2;
    private User owner1;
    private User cashier1;
    private User owner2;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("prefs_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();

        preferencesService = new SqlitePreferencesService();
        customerService = new SqliteCustomerService();
        inventoryService = new SqliteInventoryService();

        try (Connection conn = DatabaseManager.getConnection()) {
            BusinessDao businessDao = new SqliteBusinessDao();
            UserDao userDao = new SqliteUserDao();

            // Business 1 & Users
            biz1 = businessDao.save(conn, new Business(
                    null, "Al-Amin Traders", "Al-Amin Traders Ltd", "01711223344", "Dhaka", "TRAD-1234", "BDT", null, null
            ));
            owner1 = userDao.save(conn, new User(
                    null, biz1.id(), "alamin_owner", "Al-Amin Owner", "hash", "salt", UserRole.OWNER, true, null, null, null
            ));
            cashier1 = userDao.save(conn, new User(
                    null, biz1.id(), "alamin_cashier", "Al-Amin Cashier", "hash", "salt", UserRole.CASHIER, true, null, null, null
            ));

            // Business 2 & Owner
            biz2 = businessDao.save(conn, new Business(
                    null, "Padma General Store", "Padma Trade", "01811223344", "Rajshahi", "TRAD-5678", "BDT", null, null
            ));
            owner2 = userDao.save(conn, new User(
                    null, biz2.id(), "padma_owner", "Padma Owner", "hash", "salt", UserRole.OWNER, true, null, null, null
            ));
        }

        SessionContext.setSession(biz1, owner1);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
        I18nSupport.setLanguage("en");
    }

    @Test
    void shouldPersistAndRetrieveUserPreferences() {
        AppPreferencesDto update = new AppPreferencesDto(
                biz1.id(),
                "DARK",
                "bn",
                "DD/MM/YYYY",
                "STANDARD",
                "COMPACT",
                15,
                "ALL_TIME",
                "Registered Importer & Wholesaler",
                "Computer-generated invoice. No signature required.",
                "C:/images/logo.png",
                "TK",
                true
        );

        AppPreferencesDto saved = preferencesService.updatePreferences(update);

        assertThat(saved.businessId()).isEqualTo(biz1.id());
        assertThat(saved.theme()).isEqualTo("DARK");
        assertThat(saved.language()).isEqualTo("bn");
        assertThat(saved.dateFormat()).isEqualTo("DD/MM/YYYY");
        assertThat(saved.numberFormat()).isEqualTo("STANDARD");
        assertThat(saved.tableRowSize()).isEqualTo("COMPACT");
        assertThat(saved.defaultLowStockThreshold()).isEqualTo(15);
        assertThat(saved.defaultReportPeriod()).isEqualTo("ALL_TIME");
        assertThat(saved.invoiceHeader()).isEqualTo("Registered Importer & Wholesaler");
        assertThat(saved.invoiceFooter()).isEqualTo("Computer-generated invoice. No signature required.");
        assertThat(saved.logoPath()).isEqualTo("C:/images/logo.png");
        assertThat(saved.bdtDisplayFormat()).isEqualTo("TK");
        assertThat(saved.inAppAlertsEnabled()).isTrue();

        // Verify retrieval matches saved record
        AppPreferencesDto reloaded = preferencesService.getPreferences();
        assertThat(reloaded).isEqualTo(saved);
    }

    @Test
    void shouldValidateAndRejectInvalidPreferences() {
        // 1. Negative threshold
        assertThatThrownBy(() -> preferencesService.updatePreferences(new AppPreferencesDto(
                biz1.id(), "LIGHT", "en", "YYYY-MM-DD", "SOUTH_ASIAN", "STANDARD", -5, "THIS_MONTH", "", "", "", "BDT", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold cannot be negative");

        // 2. Unsupported language
        assertThatThrownBy(() -> preferencesService.updatePreferences(new AppPreferencesDto(
                biz1.id(), "LIGHT", "fr", "YYYY-MM-DD", "SOUTH_ASIAN", "STANDARD", 10, "THIS_MONTH", "", "", "", "BDT", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid language code");

        // 3. Unsupported theme
        assertThatThrownBy(() -> preferencesService.updatePreferences(new AppPreferencesDto(
                biz1.id(), "HIGH_CONTRAST", "en", "YYYY-MM-DD", "SOUTH_ASIAN", "STANDARD", 10, "THIS_MONTH", "", "", "", "BDT", true
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid theme option");

        // 4. Cross-business modification attempt
        assertThatThrownBy(() -> preferencesService.updatePreferences(new AppPreferencesDto(
                biz2.id(), "LIGHT", "en", "YYYY-MM-DD", "SOUTH_ASIAN", "STANDARD", 10, "THIS_MONTH", "", "", "", "BDT", true
        ))).isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business preference modification denied");
    }

    @Test
    void shouldUpdateBusinessProfileAndInvoiceBranding() {
        BusinessProfileUpdateDto dto = new BusinessProfileUpdateDto(
                "Al-Amin Mega Superstore",
                "Al-Amin Commercial Ltd",
                "01799887766",
                "Kawran Bazar Commercial Area, Dhaka",
                "TRAD/DNCC/998877/2026"
        );

        Business updated = preferencesService.updateBusinessProfile(
                dto,
                "Leading FMCG Wholesaler & Retailer",
                "Thank you for choosing us! Visit again.",
                "D:/logos/alamin_logo.png"
        );

        assertThat(updated.name()).isEqualTo("Al-Amin Mega Superstore");
        assertThat(updated.legalName()).isEqualTo("Al-Amin Commercial Ltd");
        assertThat(updated.phone()).isEqualTo("01799887766");
        assertThat(updated.address()).isEqualTo("Kawran Bazar Commercial Area, Dhaka");
        assertThat(updated.tradeLicense()).isEqualTo("TRAD/DNCC/998877/2026");

        // Verify active session updated
        assertThat(SessionContext.getCurrentBusiness().name()).isEqualTo("Al-Amin Mega Superstore");

        // Verify branding persisted in preferences
        AppPreferencesDto prefs = preferencesService.getPreferences();
        assertThat(prefs.invoiceHeader()).isEqualTo("Leading FMCG Wholesaler & Retailer");
        assertThat(prefs.invoiceFooter()).isEqualTo("Thank you for choosing us! Visit again.");
        assertThat(prefs.logoPath()).isEqualTo("D:/logos/alamin_logo.png");
    }

    @Test
    void shouldEnforceOwnerOnlyForBusinessProfileUpdate() {
        // Switch to cashier
        SessionContext.setSession(biz1, cashier1);

        BusinessProfileUpdateDto dto = new BusinessProfileUpdateDto("Hacked Store", null, null, null, null);
        assertThatThrownBy(() -> preferencesService.updateBusinessProfile(dto, null, null, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("requires OWNER role");
    }

    @Test
    void shouldGenerateInAppNotificationsForLowStockAndDues() {
        // 1. Product with stock below threshold (stock 2 <= threshold 10)
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Paracetamol 500mg", "8901112223334", "PARA-500", "box",
                Money.ofBdt(20.00), Money.ofBdt(35.00), 10, 2, null
        ));

        // 2. Customer with opening balance due BDT 750
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Hafizur Rahman", "01911223344", "Mirpur-10", Money.ZERO, Money.ofBdt(750.00), "Dues"
        ));

        List<InAppNotificationDto> notifications = preferencesService.getActiveNotifications();

        assertThat(notifications).isNotEmpty();

        // Verify low stock notification
        InAppNotificationDto stockAlert = notifications.stream()
                .filter(n -> "LOW_STOCK".equals(n.type()))
                .findFirst()
                .orElseThrow();
        assertThat(stockAlert.title()).contains("Paracetamol 500mg");
        assertThat(stockAlert.severity()).isEqualTo("WARNING");
        assertThat(stockAlert.targetEntityId()).isEqualTo(prod.id());

        // Verify customer due reminder notification
        InAppNotificationDto dueAlert = notifications.stream()
                .filter(n -> "DUE_REMINDER".equals(n.type()))
                .findFirst()
                .orElseThrow();
        assertThat(dueAlert.title()).contains("Hafizur Rahman");
        assertThat(dueAlert.message()).contains("750");
        assertThat(dueAlert.targetEntityId()).isEqualTo(cust.id());
    }

    @Test
    void shouldDisableInAppNotificationsWhenPreferenceIsFalse() {
        // Product with low stock
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Soap 100g", "8901112223335", "SOAP-01", "pcs",
                Money.ofBdt(40.00), Money.ofBdt(50.00), 10, 1, null
        ));

        // Disable in-app alerts
        AppPreferencesDto current = preferencesService.getPreferences();
        preferencesService.updatePreferences(new AppPreferencesDto(
                biz1.id(), current.theme(), current.language(), current.dateFormat(), current.numberFormat(),
                current.tableRowSize(), current.defaultLowStockThreshold(), current.defaultReportPeriod(),
                current.invoiceHeader(), current.invoiceFooter(), current.logoPath(), current.bdtDisplayFormat(), false
        ));

        List<InAppNotificationDto> notifications = preferencesService.getActiveNotifications();
        assertThat(notifications).isEmpty();
    }

    @Test
    void shouldGuaranteeFinancialLedgerNonCorruption() throws Exception {
        // Create a customer with ledger entry
        Customer cust = customerService.createCustomer(new CustomerCreateDto(
                biz1.id(), "Kashem Mia", "01700112233", "Mohakhali", Money.ZERO, Money.ofBdt(300.00), "Regular"
        ));

        long initialLedgerCount;
        long initialDebitPaisha;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*), SUM(debit_paisha) FROM customer_ledger WHERE customer_id = ?")) {
            ps.setLong(1, cust.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                initialLedgerCount = rs.getLong(1);
                initialDebitPaisha = rs.getLong(2);
            }
        }

        // Perform multiple preference & profile updates
        for (int i = 0; i < 5; i++) {
            preferencesService.updatePreferences(new AppPreferencesDto(
                    biz1.id(), (i % 2 == 0) ? "DARK" : "LIGHT", (i % 2 == 0) ? "bn" : "en",
                    "YYYY-MM-DD", "SOUTH_ASIAN", "STANDARD", 10 + i, "THIS_MONTH",
                    "Header " + i, "Footer " + i, "", "BDT", true
            ));
            preferencesService.updateBusinessProfile(
                    new BusinessProfileUpdateDto("Store " + i, "Legal " + i, "0170000000" + i, "Address " + i, "TRAD-" + i),
                    "H" + i, "F" + i, ""
            );
        }

        // Direct DB verification: financial ledger is completely intact and untouched!
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*), SUM(debit_paisha) FROM customer_ledger WHERE customer_id = ?")) {
            ps.setLong(1, cust.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(initialLedgerCount);
                assertThat(rs.getLong(2)).isEqualTo(initialDebitPaisha);
            }
        }
    }

    @Test
    void shouldResolveI18nLabelsInEnglishAndBengali() {
        // English check
        I18nSupport.setLanguage("en");
        assertThat(I18nSupport.get("nav.dashboard")).isEqualTo("Dashboard");
        assertThat(I18nSupport.get("nav.customers")).isEqualTo("Customers");
        assertThat(I18nSupport.get("action.save")).isEqualTo("Save Changes");

        // Bengali check
        I18nSupport.setLanguage("bn");
        assertThat(I18nSupport.get("nav.dashboard")).isEqualTo("ড্যাশবোর্ড");
        assertThat(I18nSupport.get("nav.customers")).isEqualTo("গ্রাহক খাতা");
        assertThat(I18nSupport.get("action.save")).isEqualTo("পরিবর্তন সংরক্ষণ করুন");

        // Missing key fallback
        assertThat(I18nSupport.get("non_existent_key")).isEqualTo("non_existent_key");
    }

    @Test
    void shouldSupportCustomSettingsCrud() {
        // Create / Update
        preferencesService.upsertSetting("printer.receipt_width", "80mm");
        preferencesService.upsertSetting("printer.auto_cut", "true");

        List<SettingEntryDto> list = preferencesService.listCustomSettings();
        assertThat(list).anyMatch(s -> "printer.receipt_width".equals(s.key()) && "80mm".equals(s.value()));

        // Delete
        preferencesService.deleteSetting("printer.auto_cut");
        List<SettingEntryDto> updatedList = preferencesService.listCustomSettings();
        assertThat(updatedList).noneMatch(s -> "printer.auto_cut".equals(s.key()));

        // Cannot touch recovery keys
        assertThatThrownBy(() -> preferencesService.upsertSetting("owner_recovery_test", "123"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldSupportAuditLogsCrudAndSearch() {
        // Record log
        preferencesService.recordAuditLog("MANUAL_NOTE", "SYSTEM", null, "Verified barcode scanner hardware connected");

        List<AuditLogListItemDto> logs = preferencesService.searchAuditLogs("barcode", 10);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).changeSummary()).contains("barcode scanner hardware");

        // Delete single log
        long logId = logs.get(0).id();
        preferencesService.deleteAuditLog(logId);
        List<AuditLogListItemDto> afterDelete = preferencesService.searchAuditLogs("barcode", 10);
        assertThat(afterDelete).isEmpty();

        // Clear all logs
        preferencesService.recordAuditLog("TEST", "MISC", null, "Log 1");
        preferencesService.recordAuditLog("TEST", "MISC", null, "Log 2");
        assertThat(preferencesService.searchAuditLogs(null, 10)).hasSizeGreaterThanOrEqualTo(2);

        preferencesService.clearAuditLogs();
        assertThat(preferencesService.searchAuditLogs(null, 10)).isEmpty();
    }
}

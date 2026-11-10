package com.smartkhata.service.impl;

import com.smartkhata.dao.BusinessDao;
import com.smartkhata.dao.SettingsDao;
import com.smartkhata.dao.SqliteBusinessDao;
import com.smartkhata.dao.SqliteSettingsDao;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.AppPreferencesDto;
import com.smartkhata.model.dto.AuditLogListItemDto;
import com.smartkhata.model.dto.BusinessProfileUpdateDto;
import com.smartkhata.model.dto.InAppNotificationDto;
import com.smartkhata.model.dto.SettingEntryDto;
import com.smartkhata.model.entity.AuditLog;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.PreferencesService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.util.BengaliMoneyFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQLite-backed implementation of PreferencesService.
 * Enforces validation, tenant isolation, and guarantees that financial ledger
 * tables are never mutated by preference updates.
 */
public class SqlitePreferencesService implements PreferencesService {

    private static final Logger log = LoggerFactory.getLogger(SqlitePreferencesService.class);

    private static final String KEY_THEME = "pref.theme";
    private static final String KEY_LANGUAGE = "pref.language";
    private static final String KEY_DATE_FORMAT = "pref.date_format";
    private static final String KEY_NUMBER_FORMAT = "pref.number_format";
    private static final String KEY_TABLE_ROW_SIZE = "pref.table_row_size";
    private static final String KEY_LOW_STOCK = "pref.default_low_stock";
    private static final String KEY_REPORT_PERIOD = "pref.default_report_period";
    private static final String KEY_INVOICE_HEADER = "pref.invoice_header";
    private static final String KEY_INVOICE_FOOTER = "pref.invoice_footer";
    private static final String KEY_LOGO_PATH = "pref.logo_path";
    private static final String KEY_BDT_FORMAT = "pref.bdt_display_format";
    private static final String KEY_IN_APP_ALERTS = "pref.in_app_alerts_enabled";

    private static final Set<String> VALID_THEMES = Set.of("LIGHT", "DARK");
    private static final Set<String> VALID_LANGUAGES = Set.of("en", "bn");
    private static final Set<String> VALID_DATE_FORMATS = Set.of("YYYY-MM-DD", "DD/MM/YYYY", "DD-MM-YYYY");
    private static final Set<String> VALID_NUMBER_FORMATS = Set.of("SOUTH_ASIAN", "STANDARD");
    private static final Set<String> VALID_ROW_SIZES = Set.of("COMPACT", "STANDARD", "RELAXED");
    private static final Set<String> VALID_REPORT_PERIODS = Set.of("THIS_MONTH", "TODAY", "LAST_30_DAYS", "ALL_TIME");
    private static final Set<String> VALID_BDT_FORMATS = Set.of("BDT", "TK");

    private final SettingsDao settingsDao;
    private final BusinessDao businessDao;
    private final com.smartkhata.dao.AuditLogDao auditLogDao;

    public SqlitePreferencesService() {
        this(new SqliteSettingsDao(), new SqliteBusinessDao(), new com.smartkhata.dao.SqliteAuditLogDao());
    }

    public SqlitePreferencesService(SettingsDao settingsDao, BusinessDao businessDao) {
        this(settingsDao, businessDao, new com.smartkhata.dao.SqliteAuditLogDao());
    }

    public SqlitePreferencesService(SettingsDao settingsDao, BusinessDao businessDao, com.smartkhata.dao.AuditLogDao auditLogDao) {
        this.settingsDao = settingsDao;
        this.businessDao = businessDao;
        this.auditLogDao = auditLogDao;
    }

    @Override
    public AppPreferencesDto getPreferences() {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();

        try (Connection conn = DatabaseManager.getConnection()) {
            String theme = settingsDao.get(conn, businessId, KEY_THEME).orElse("LIGHT");
            String language = settingsDao.get(conn, businessId, KEY_LANGUAGE).orElse("en");
            String dateFormat = settingsDao.get(conn, businessId, KEY_DATE_FORMAT).orElse("YYYY-MM-DD");
            String numberFormat = settingsDao.get(conn, businessId, KEY_NUMBER_FORMAT).orElse("SOUTH_ASIAN");
            String tableRowSize = settingsDao.get(conn, businessId, KEY_TABLE_ROW_SIZE).orElse("STANDARD");
            int defaultLowStock = Integer.parseInt(settingsDao.get(conn, businessId, KEY_LOW_STOCK).orElse("10"));
            String defaultReportPeriod = settingsDao.get(conn, businessId, KEY_REPORT_PERIOD).orElse("THIS_MONTH");
            String invoiceHeader = settingsDao.get(conn, businessId, KEY_INVOICE_HEADER).orElse("");
            String invoiceFooter = settingsDao.get(conn, businessId, KEY_INVOICE_FOOTER).orElse("Thank you for your business!");
            String logoPath = settingsDao.get(conn, businessId, KEY_LOGO_PATH).orElse("");
            String bdtDisplayFormat = settingsDao.get(conn, businessId, KEY_BDT_FORMAT).orElse("BDT");
            boolean inAppAlerts = Boolean.parseBoolean(settingsDao.get(conn, businessId, KEY_IN_APP_ALERTS).orElse("true"));

            return new AppPreferencesDto(
                    businessId,
                    theme,
                    language,
                    dateFormat,
                    numberFormat,
                    tableRowSize,
                    defaultLowStock,
                    defaultReportPeriod,
                    invoiceHeader,
                    invoiceFooter,
                    logoPath,
                    bdtDisplayFormat,
                    inAppAlerts
            );
        } catch (SQLException e) {
            log.error("Failed to load preferences for business {}: {}", businessId, e.getMessage(), e);
            return AppPreferencesDto.defaults(businessId);
        }
    }

    @Override
    public AppPreferencesDto updatePreferences(AppPreferencesDto prefs) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();

        if (prefs == null) {
            throw new IllegalArgumentException("Preferences object cannot be null");
        }
        if (prefs.businessId() != businessId) {
            throw new SecurityException("Cross-business preference modification denied");
        }

        // Validate options
        String theme = prefs.theme() != null ? prefs.theme().toUpperCase() : "LIGHT";
        if (!VALID_THEMES.contains(theme)) {
            throw new IllegalArgumentException("Invalid theme option: " + prefs.theme() + ". Expected LIGHT or DARK.");
        }

        String language = prefs.language() != null ? prefs.language().toLowerCase() : "en";
        if (!VALID_LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("Invalid language code: " + prefs.language() + ". Expected 'en' or 'bn'.");
        }

        String dateFormat = prefs.dateFormat() != null ? prefs.dateFormat() : "YYYY-MM-DD";
        if (!VALID_DATE_FORMATS.contains(dateFormat)) {
            throw new IllegalArgumentException("Invalid date format: " + prefs.dateFormat());
        }

        String numberFormat = prefs.numberFormat() != null ? prefs.numberFormat().toUpperCase() : "SOUTH_ASIAN";
        if (!VALID_NUMBER_FORMATS.contains(numberFormat)) {
            throw new IllegalArgumentException("Invalid number format: " + prefs.numberFormat());
        }

        String tableRowSize = prefs.tableRowSize() != null ? prefs.tableRowSize().toUpperCase() : "STANDARD";
        if (!VALID_ROW_SIZES.contains(tableRowSize)) {
            throw new IllegalArgumentException("Invalid table density: " + prefs.tableRowSize());
        }

        if (prefs.defaultLowStockThreshold() < 0) {
            throw new IllegalArgumentException("Default low-stock threshold cannot be negative: " + prefs.defaultLowStockThreshold());
        }

        String reportPeriod = prefs.defaultReportPeriod() != null ? prefs.defaultReportPeriod().toUpperCase() : "THIS_MONTH";
        if (!VALID_REPORT_PERIODS.contains(reportPeriod)) {
            throw new IllegalArgumentException("Invalid report period: " + prefs.defaultReportPeriod());
        }

        String bdtFormat = prefs.bdtDisplayFormat() != null ? prefs.bdtDisplayFormat().toUpperCase() : "BDT";
        if (!VALID_BDT_FORMATS.contains(bdtFormat)) {
            throw new IllegalArgumentException("Invalid currency display format: " + prefs.bdtDisplayFormat());
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            settingsDao.set(conn, businessId, KEY_THEME, theme);
            settingsDao.set(conn, businessId, KEY_LANGUAGE, language);
            settingsDao.set(conn, businessId, KEY_DATE_FORMAT, dateFormat);
            settingsDao.set(conn, businessId, KEY_NUMBER_FORMAT, numberFormat);
            settingsDao.set(conn, businessId, KEY_TABLE_ROW_SIZE, tableRowSize);
            settingsDao.set(conn, businessId, KEY_LOW_STOCK, String.valueOf(prefs.defaultLowStockThreshold()));
            settingsDao.set(conn, businessId, KEY_REPORT_PERIOD, reportPeriod);
            settingsDao.set(conn, businessId, KEY_INVOICE_HEADER, prefs.invoiceHeader() != null ? prefs.invoiceHeader().trim() : "");
            settingsDao.set(conn, businessId, KEY_INVOICE_FOOTER, prefs.invoiceFooter() != null ? prefs.invoiceFooter().trim() : "");
            settingsDao.set(conn, businessId, KEY_LOGO_PATH, prefs.logoPath() != null ? prefs.logoPath().trim() : "");
            settingsDao.set(conn, businessId, KEY_BDT_FORMAT, bdtFormat);
            settingsDao.set(conn, businessId, KEY_IN_APP_ALERTS, String.valueOf(prefs.inAppAlertsEnabled()));

            log.info("Updated preferences for business [{}]", businessId);
            return getPreferences();
        } catch (SQLException e) {
            log.error("Failed to persist preferences for business {}: {}", businessId, e.getMessage(), e);
            throw new RuntimeException("Could not update preferences in database", e);
        }
    }

    @Override
    public Business updateBusinessProfile(BusinessProfileUpdateDto dto, String invoiceHeader, String invoiceFooter, String logoPath) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();

        if (dto == null || dto.name() == null || dto.name().isBlank()) {
            throw new IllegalArgumentException("Business name is required and cannot be blank");
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            Business current = businessDao.findById(conn, businessId)
                    .orElseThrow(() -> new IllegalStateException("Business record not found for ID: " + businessId));

            Business updated = new Business(
                    current.id(),
                    dto.name().trim(),
                    dto.legalName() != null ? dto.legalName().trim() : null,
                    dto.phone() != null ? dto.phone().trim() : null,
                    dto.address() != null ? dto.address().trim() : null,
                    dto.tradeLicense() != null ? dto.tradeLicense().trim() : null,
                    current.currencyCode(),
                    current.createdAt(),
                    LocalDateTime.now()
            );

            businessDao.save(conn, updated);

            // Save brand metadata in settings
            if (invoiceHeader != null) settingsDao.set(conn, businessId, KEY_INVOICE_HEADER, invoiceHeader.trim());
            if (invoiceFooter != null) settingsDao.set(conn, businessId, KEY_INVOICE_FOOTER, invoiceFooter.trim());
            if (logoPath != null) settingsDao.set(conn, businessId, KEY_LOGO_PATH, logoPath.trim());

            // Synchronize active session
            SessionContext.updateCurrentBusiness(updated);
            log.info("Business profile updated for [{}] (ID: {})", updated.name(), businessId);
            return updated;
        } catch (SQLException e) {
            log.error("Failed to update business profile for business {}: {}", businessId, e.getMessage(), e);
            throw new RuntimeException("Failed to update business profile", e);
        }
    }

    @Override
    public List<InAppNotificationDto> getActiveNotifications() {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();

        AppPreferencesDto prefs = getPreferences();
        if (!prefs.inAppAlertsEnabled()) {
            return List.of();
        }

        List<InAppNotificationDto> notifications = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection()) {
            // 1. Low-stock notifications
            String lowStockSql = "SELECT p.id, p.name, p.unit, p.low_stock_threshold, " +
                    "COALESCE((SELECT SUM(sm.quantity) FROM stock_movements sm WHERE sm.product_id = p.id), 0) as current_qty " +
                    "FROM products p WHERE p.business_id = ? AND p.is_archived = 0 " +
                    "AND current_qty <= p.low_stock_threshold " +
                    "ORDER BY current_qty ASC, p.id ASC LIMIT 20";
            try (PreparedStatement ps = conn.prepareStatement(lowStockSql)) {
                ps.setLong(1, businessId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long prodId = rs.getLong("id");
                        String name = rs.getString("name");
                        int stock = rs.getInt("current_qty");
                        int threshold = rs.getInt("low_stock_threshold");
                        String unit = rs.getString("unit");

                        String severity = stock <= 0 ? "DANGER" : "WARNING";
                        String msg = stock <= 0
                                ? String.format("Stock exhausted: 0 %s remaining (threshold: %d).", unit, threshold)
                                : String.format("Low stock warning: Only %d %s left (threshold: %d).", stock, unit, threshold);

                        notifications.add(new InAppNotificationDto(
                                "STOCK-" + prodId,
                                "LOW_STOCK",
                                "Low Stock: " + name,
                                msg,
                                severity,
                                LocalDateTime.now(),
                                prodId
                        ));
                    }
                }
            }

            // 2. Customer Due Reminders
            String duesSql = "SELECT c.id, c.name, c.phone, (SUM(cl.debit_paisha) - SUM(cl.credit_paisha)) as balance " +
                    "FROM customer_ledger cl JOIN customers c ON cl.customer_id = c.id " +
                    "WHERE cl.business_id = ? AND c.is_archived = 0 " +
                    "GROUP BY c.id " +
                    "HAVING balance > 0 " +
                    "ORDER BY balance DESC LIMIT 20";
            try (PreparedStatement ps = conn.prepareStatement(duesSql)) {
                ps.setLong(1, businessId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long custId = rs.getLong("id");
                        String custName = rs.getString("name");
                        long balancePaisha = rs.getLong("balance");

                        String bdtFormatted = BengaliMoneyFormatter.format(Money.ofPaisha(balancePaisha));
                        notifications.add(new InAppNotificationDto(
                                "DUE-" + custId,
                                "DUE_REMINDER",
                                "Customer Due: " + custName,
                                "Outstanding balance of " + bdtFormatted + " pending collection.",
                                "INFO",
                                LocalDateTime.now(),
                                custId
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query active notifications for business {}: {}", businessId, e.getMessage(), e);
        }

        return notifications;
    }

    @Override
    public List<SettingEntryDto> listCustomSettings() {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            return settingsDao.findAllByBusiness(conn, businessId);
        } catch (SQLException e) {
            log.error("Failed to list settings for business {}: {}", businessId, e.getMessage(), e);
            throw new RuntimeException("Failed to load settings list", e);
        }
    }

    @Override
    public void upsertSetting(String key, String value) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Setting key cannot be empty");
        }
        if (key.startsWith("owner_recovery_")) {
            throw new SecurityException("Cannot modify security recovery keys directly");
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            settingsDao.set(conn, businessId, key.trim(), value != null ? value.trim() : "");
            log.info("Setting [{}] updated for business {}", key, businessId);
        } catch (SQLException e) {
            log.error("Failed to update setting {}: {}", key, e.getMessage(), e);
            throw new RuntimeException("Failed to save setting", e);
        }
    }

    @Override
    public void deleteSetting(String key) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Setting key cannot be empty");
        }
        if (key.startsWith("owner_recovery_")) {
            throw new SecurityException("Cannot delete master recovery keys");
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            settingsDao.remove(conn, businessId, key.trim());
            log.info("Setting [{}] removed for business {}", key, businessId);
        } catch (SQLException e) {
            log.error("Failed to remove setting {}: {}", key, e.getMessage(), e);
            throw new RuntimeException("Failed to delete setting", e);
        }
    }

    @Override
    public List<AuditLogListItemDto> searchAuditLogs(String query, int limit) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            return auditLogDao.search(conn, businessId, query, limit);
        } catch (SQLException e) {
            log.error("Failed to search audit logs for business {}: {}", businessId, e.getMessage(), e);
            throw new RuntimeException("Failed to query audit logs", e);
        }
    }

    @Override
    public void recordAuditLog(String actionType, String entityName, Long entityId, String changeSummary) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        User currentUser = SessionContext.getCurrentUser();

        try (Connection conn = DatabaseManager.getConnection()) {
            auditLogDao.save(conn, new AuditLog(
                    null,
                    businessId,
                    currentUser != null ? currentUser.id() : null,
                    actionType != null ? actionType.toUpperCase() : "INFO",
                    entityName != null ? entityName.toUpperCase() : "GENERAL",
                    entityId,
                    changeSummary,
                    LocalDateTime.now()
            ));
        } catch (SQLException e) {
            log.error("Failed to record audit log: {}", e.getMessage(), e);
        }
    }

    @Override
    public void deleteAuditLog(long id) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();

        try (Connection conn = DatabaseManager.getConnection()) {
            auditLogDao.delete(conn, id, businessId);
            log.info("Audit log [{}] deleted for business {}", id, businessId);
        } catch (SQLException e) {
            log.error("Failed to delete audit log {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to delete audit log entry", e);
        }
    }

    @Override
    public void clearAuditLogs() {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();

        try (Connection conn = DatabaseManager.getConnection()) {
            auditLogDao.clear(conn, businessId);
            log.info("All audit logs cleared for business {}", businessId);
        } catch (SQLException e) {
            log.error("Failed to clear audit logs for business {}: {}", businessId, e.getMessage(), e);
            throw new RuntimeException("Failed to clear audit logs", e);
        }
    }
}

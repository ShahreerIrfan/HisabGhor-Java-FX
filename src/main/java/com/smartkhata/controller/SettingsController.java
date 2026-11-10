package com.smartkhata.controller;

import com.smartkhata.app.AppConfig;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.api.BackupService;
import com.smartkhata.service.api.PreferencesService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.LocalAuthService;
import com.smartkhata.service.impl.SqliteBackupService;
import com.smartkhata.service.impl.SqlitePreferencesService;
import com.smartkhata.service.sample.SampleDataSeeder;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.ThemeManager;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.I18nSupport;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Controller for Settings, Preferences, Language, and In-App Notifications view.
 */
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private Label lblHeaderTitle;
    @FXML private Label lblHeaderSubtitle;
    @FXML private HBox statusBanner;
    @FXML private Label lblStatusMessage;

    @FXML private TabPane settingsTabPane;
    @FXML private Tab tabBusinessProfile;
    @FXML private Tab tabPreferences;
    @FXML private Tab tabNotifications;
    @FXML private Tab tabAbout;
    @FXML private Tab tabUsers;
    @FXML private Tab tabAuditAndSettings;

    // Tab 1: Business Profile
    @FXML private Label lblBizName;
    @FXML private TextField txtBizName;
    @FXML private Label lblBizLegalName;
    @FXML private TextField txtBizLegalName;
    @FXML private Label lblBizPhone;
    @FXML private TextField txtBizPhone;
    @FXML private Label lblBizLicense;
    @FXML private TextField txtBizLicense;
    @FXML private Label lblBizAddress;
    @FXML private TextField txtBizAddress;
    @FXML private Label lblInvoiceHeader;
    @FXML private TextField txtInvoiceHeader;
    @FXML private Label lblInvoiceFooter;
    @FXML private TextField txtInvoiceFooter;
    @FXML private Label lblLogoPath;
    @FXML private TextField txtLogoPath;
    @FXML private Button btnBrowseLogo;
    @FXML private Button btnSaveBusinessProfile;

    // Tab 2: Preferences
    @FXML private Label lblTheme;
    @FXML private ComboBox<String> cmbTheme;
    @FXML private Label lblLanguage;
    @FXML private ComboBox<String> cmbLanguage;
    @FXML private Label lblDateFormat;
    @FXML private ComboBox<String> cmbDateFormat;
    @FXML private Label lblNumberFormat;
    @FXML private ComboBox<String> cmbNumberFormat;
    @FXML private Label lblTableRowSize;
    @FXML private ComboBox<String> cmbTableRowSize;
    @FXML private Label lblBdtFormat;
    @FXML private ComboBox<String> cmbBdtFormat;
    @FXML private Label lblDefaultLowStock;
    @FXML private TextField txtDefaultLowStock;
    @FXML private Label lblDefaultReportPeriod;
    @FXML private ComboBox<String> cmbDefaultReportPeriod;
    @FXML private CheckBox chkInAppAlerts;
    @FXML private CheckBox chkAllowDirectGrn;
    @FXML private CheckBox chkAllowOverReceipt;
    @FXML private ComboBox<String> cmbMrpPolicy;
    @FXML private CheckBox chkAllowNegativeStock;
    @FXML private Button btnSavePreferences;

    // Tab 3: Notifications
    @FXML private Button btnRefreshAlerts;
    @FXML private TableView<InAppNotificationDto> tblNotifications;
    @FXML private TableColumn<InAppNotificationDto, String> colAlertType;
    @FXML private TableColumn<InAppNotificationDto, String> colAlertSeverity;
    @FXML private TableColumn<InAppNotificationDto, String> colAlertTitle;
    @FXML private TableColumn<InAppNotificationDto, String> colAlertMessage;
    @FXML private TableColumn<InAppNotificationDto, String> colAlertTime;

    // Tab 4: About
    @FXML private Label lblAppVersion;
    @FXML private TextField txtAppHome;
    @FXML private TextField txtDataDir;
    @FXML private TextField txtDatabaseFile;
    @FXML private TextField txtBackupDir;
    @FXML private Button btnCreateBackup;
    @FXML private Button btnRestoreBackup;
    @FXML private Button btnLoadSampleData;

    // Tab 5: Users & Staff
    @FXML private Button btnAddUser;
    @FXML private Button btnEditUser;
    @FXML private Button btnDeleteUser;
    @FXML private Button btnRefreshUsers;
    @FXML private TableView<User> tblUsers;
    @FXML private TableColumn<User, String> colUserUsername;
    @FXML private TableColumn<User, String> colUserFullName;
    @FXML private TableColumn<User, String> colUserRole;
    @FXML private TableColumn<User, String> colUserStatus;
    @FXML private TableColumn<User, String> colUserLastLogin;

    // Tab 6: Audit Trail & Custom Settings
    @FXML private TextField txtAuditSearch;
    @FXML private Button btnAddAuditNote;
    @FXML private Button btnDeleteAuditLog;
    @FXML private Button btnClearAuditLogs;
    @FXML private Button btnRefreshAuditLogs;
    @FXML private TableView<AuditLogListItemDto> tblAuditLogs;
    @FXML private TableColumn<AuditLogListItemDto, String> colAuditTime;
    @FXML private TableColumn<AuditLogListItemDto, String> colAuditUser;
    @FXML private TableColumn<AuditLogListItemDto, String> colAuditAction;
    @FXML private TableColumn<AuditLogListItemDto, String> colAuditEntity;
    @FXML private TableColumn<AuditLogListItemDto, String> colAuditSummary;

    @FXML private Button btnAddSettingKey;
    @FXML private Button btnDeleteSettingKey;
    @FXML private Button btnRefreshSettingsTable;
    @FXML private TableView<SettingEntryDto> tblCustomSettings;
    @FXML private TableColumn<SettingEntryDto, String> colSettingKey;
    @FXML private TableColumn<SettingEntryDto, String> colSettingValue;
    @FXML private TableColumn<SettingEntryDto, String> colSettingUpdated;

    private final PreferencesService preferencesService;
    private final BackupService backupService;
    private final AuthService authService;

    public SettingsController() {
        this(new SqlitePreferencesService(), new SqliteBackupService(), new LocalAuthService());
    }

    public SettingsController(PreferencesService preferencesService) {
        this(preferencesService, new SqliteBackupService(), new LocalAuthService());
    }

    public SettingsController(PreferencesService preferencesService, BackupService backupService) {
        this(preferencesService, backupService, new LocalAuthService());
    }

    public SettingsController(PreferencesService preferencesService, BackupService backupService, AuthService authService) {
        this.preferencesService = preferencesService;
        this.backupService = backupService;
        this.authService = authService;
    }

    @FXML
    public void initialize() {
        if (!SessionContext.isAuthenticated()) {
            return;
        }

        setupNotificationTable();
        setupUserTable();
        setupAuditTable();
        setupCustomSettingsTable();

        populateOptionDropdowns();
        loadBusinessProfile();
        loadPreferences();
        loadSystemInfo();
        loadNotifications();
        loadUsers();
        loadAuditLogs();
        loadCustomSettings();

        if (txtAuditSearch != null) {
            txtAuditSearch.textProperty().addListener((obs, oldVal, newVal) -> loadAuditLogs());
        }

        // Listen for language changes to update UI labels dynamically
        I18nSupport.addLocaleChangeListener(this::applyLocalizedLabels);
        applyLocalizedLabels(I18nSupport.getCurrentLocale());
    }

    private void setupNotificationTable() {
        colAlertType.setCellValueFactory(new PropertyValueFactory<>("type"));
        colAlertSeverity.setCellValueFactory(new PropertyValueFactory<>("severity"));
        colAlertTitle.setCellValueFactory(new PropertyValueFactory<>("title"));
        colAlertMessage.setCellValueFactory(new PropertyValueFactory<>("message"));
        colAlertTime.setCellValueFactory(cellData -> {
            if (cellData.getValue().timestamp() != null) {
                return new javafx.beans.property.SimpleStringProperty(cellData.getValue().timestamp().format(TIME_FMT));
            }
            return new javafx.beans.property.SimpleStringProperty("—");
        });

        // Custom severity cell factory with color pills
        colAlertSeverity.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    if ("DANGER".equalsIgnoreCase(item)) {
                        setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold; -fx-alignment: CENTER;");
                    } else if ("WARNING".equalsIgnoreCase(item)) {
                        setStyle("-fx-text-fill: #D97706; -fx-font-weight: bold; -fx-alignment: CENTER;");
                    } else {
                        setStyle("-fx-text-fill: #2563EB; -fx-font-weight: bold; -fx-alignment: CENTER;");
                    }
                }
            }
        });
    }

    private void setupUserTable() {
        if (tblUsers == null) return;
        colUserUsername.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().username()));
        colUserFullName.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().fullName()));
        colUserRole.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().role().name()));
        colUserStatus.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().isActive() ? "Active" : "Inactive"));
        colUserLastLogin.setCellValueFactory(cellData -> {
            if (cellData.getValue().lastLoginAt() != null) {
                return new SimpleStringProperty(cellData.getValue().lastLoginAt().format(TIME_FMT));
            }
            return new SimpleStringProperty("Never");
        });

        colUserStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if ("Active".equals(item)) {
                        setStyle("-fx-text-fill: #16A34A; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold;");
                    }
                }
            }
        });
    }

    private void setupAuditTable() {
        if (tblAuditLogs == null) return;
        colAuditTime.setCellValueFactory(cellData -> {
            if (cellData.getValue().createdAt() != null) {
                return new SimpleStringProperty(cellData.getValue().createdAt().format(TIME_FMT));
            }
            return new SimpleStringProperty("—");
        });
        colAuditUser.setCellValueFactory(cellData -> new SimpleStringProperty(
                cellData.getValue().username() != null ? "@" + cellData.getValue().username() : "System"
        ));
        colAuditAction.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().actionType()));
        colAuditEntity.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().entityName()));
        colAuditSummary.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().changeSummary()));
    }

    private void setupCustomSettingsTable() {
        if (tblCustomSettings == null) return;
        colSettingKey.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().key()));
        colSettingValue.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().value()));
        colSettingUpdated.setCellValueFactory(cellData -> {
            if (cellData.getValue().updatedAt() != null) {
                return new SimpleStringProperty(cellData.getValue().updatedAt().format(TIME_FMT));
            }
            return new SimpleStringProperty("—");
        });
    }

    private void populateOptionDropdowns() {
        cmbTheme.setItems(FXCollections.observableArrayList(
                "Primer Light (Default)",
                "Primer Dark"
        ));

        cmbLanguage.setItems(FXCollections.observableArrayList(
                "English (English)",
                "বাংলা (Bengali)"
        ));

        cmbDateFormat.setItems(FXCollections.observableArrayList(
                "YYYY-MM-DD",
                "DD/MM/YYYY",
                "DD-MM-YYYY"
        ));

        cmbNumberFormat.setItems(FXCollections.observableArrayList(
                "South Asian (লাখ/কোটি - 4,86,530)",
                "Standard Western (Millions - 486,530)"
        ));

        cmbTableRowSize.setItems(FXCollections.observableArrayList(
                "Standard",
                "Compact",
                "Relaxed"
        ));

        cmbBdtFormat.setItems(FXCollections.observableArrayList(
                "BDT (e.g. BDT 500)",
                "TK (e.g. ৳ 500)"
        ));

        cmbDefaultReportPeriod.setItems(FXCollections.observableArrayList(
                "THIS_MONTH",
                "TODAY",
                "LAST_30_DAYS",
                "ALL_TIME"
        ));

        if (cmbMrpPolicy != null) {
            cmbMrpPolicy.setItems(FXCollections.observableArrayList("WARN", "BLOCK", "ALLOW"));
            cmbMrpPolicy.setValue("WARN");
        }
    }

    private void loadBusinessProfile() {
        Business biz = SessionContext.getCurrentBusiness();
        if (biz != null) {
            txtBizName.setText(biz.name() != null ? biz.name() : "");
            txtBizLegalName.setText(biz.legalName() != null ? biz.legalName() : "");
            txtBizPhone.setText(biz.phone() != null ? biz.phone() : "");
            txtBizLicense.setText(biz.tradeLicense() != null ? biz.tradeLicense() : "");
            txtBizAddress.setText(biz.address() != null ? biz.address() : "");
        }

        AppPreferencesDto prefs = preferencesService.getPreferences();
        txtInvoiceHeader.setText(prefs.invoiceHeader() != null ? prefs.invoiceHeader() : "");
        txtInvoiceFooter.setText(prefs.invoiceFooter() != null ? prefs.invoiceFooter() : "");
        txtLogoPath.setText(prefs.logoPath() != null ? prefs.logoPath() : "");
    }

    private void loadPreferences() {
        AppPreferencesDto prefs = preferencesService.getPreferences();

        cmbTheme.setValue("DARK".equalsIgnoreCase(prefs.theme()) ? "Primer Dark" : "Primer Light (Default)");
        cmbLanguage.setValue("bn".equalsIgnoreCase(prefs.language()) ? "বাংলা (Bengali)" : "English (English)");
        cmbDateFormat.setValue(prefs.dateFormat() != null ? prefs.dateFormat() : "YYYY-MM-DD");
        cmbNumberFormat.setValue("STANDARD".equalsIgnoreCase(prefs.numberFormat())
                ? "Standard Western (Millions - 486,530)"
                : "South Asian (লাখ/কোটি - 4,86,530)");
        cmbTableRowSize.setValue(capitalize(prefs.tableRowSize() != null ? prefs.tableRowSize() : "Standard"));
        cmbBdtFormat.setValue("TK".equalsIgnoreCase(prefs.bdtDisplayFormat()) ? "TK (e.g. ৳ 500)" : "BDT (e.g. BDT 500)");
        txtDefaultLowStock.setText(String.valueOf(prefs.defaultLowStockThreshold()));
        cmbDefaultReportPeriod.setValue(prefs.defaultReportPeriod() != null ? prefs.defaultReportPeriod() : "THIS_MONTH");
        chkInAppAlerts.setSelected(prefs.inAppAlertsEnabled());

        Map<String, String> settingsMap = new java.util.HashMap<>();
        for (SettingEntryDto entry : preferencesService.listCustomSettings()) {
            settingsMap.put(entry.key(), entry.value());
        }
        if (chkAllowDirectGrn != null) {
            chkAllowDirectGrn.setSelected(Boolean.parseBoolean(settingsMap.getOrDefault("scm.allow_direct_grn", "true")));
        }
        if (chkAllowOverReceipt != null) {
            chkAllowOverReceipt.setSelected(Boolean.parseBoolean(settingsMap.getOrDefault("scm.allow_over_receipt", "false")));
        }
        if (cmbMrpPolicy != null) {
            cmbMrpPolicy.setValue(settingsMap.getOrDefault("scm.mrp_policy", "WARN"));
        }
        if (chkAllowNegativeStock != null) {
            chkAllowNegativeStock.setSelected(Boolean.parseBoolean(settingsMap.getOrDefault("scm.allow_negative_stock", "false")));
        }
    }

    private void loadSystemInfo() {
        lblAppVersion.setText("Version: " + AppConfig.getAppVersion() + " (Local-First Offline Architecture)");
        if (AppConfig.getAppHomeDir() != null) {
            txtAppHome.setText(AppConfig.getAppHomeDir().toString());
        }
        if (AppConfig.getDataDir() != null) {
            txtDataDir.setText(AppConfig.getDataDir().toString());
            txtDatabaseFile.setText(AppConfig.getDatabasePath().toString());
        }
        if (AppConfig.getBackupDir() != null) {
            txtBackupDir.setText(AppConfig.getBackupDir().toString());
        }
    }

    private void loadNotifications() {
        Thread worker = new Thread(() -> {
            try {
                List<InAppNotificationDto> list = preferencesService.getActiveNotifications();
                Platform.runLater(() -> tblNotifications.setItems(FXCollections.observableArrayList(list)));
            } catch (Exception e) {
                log.error("Failed to load notifications: {}", e.getMessage(), e);
            }
        }, "notifications-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadUsers() {
        if (tblUsers == null) return;
        Thread worker = new Thread(() -> {
            try {
                List<User> list = authService.listUsersForCurrentBusiness();
                Platform.runLater(() -> tblUsers.setItems(FXCollections.observableArrayList(list)));
            } catch (Exception e) {
                log.error("Failed to load users: {}", e.getMessage(), e);
            }
        }, "users-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadAuditLogs() {
        if (tblAuditLogs == null) return;
        String query = txtAuditSearch != null ? txtAuditSearch.getText() : null;
        Thread worker = new Thread(() -> {
            try {
                List<AuditLogListItemDto> list = preferencesService.searchAuditLogs(query, 100);
                Platform.runLater(() -> tblAuditLogs.setItems(FXCollections.observableArrayList(list)));
            } catch (Exception e) {
                log.error("Failed to load audit logs: {}", e.getMessage(), e);
            }
        }, "audit-logs-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadCustomSettings() {
        if (tblCustomSettings == null) return;
        Thread worker = new Thread(() -> {
            try {
                List<SettingEntryDto> list = preferencesService.listCustomSettings();
                Platform.runLater(() -> tblCustomSettings.setItems(FXCollections.observableArrayList(list)));
            } catch (Exception e) {
                log.error("Failed to load custom settings: {}", e.getMessage(), e);
            }
        }, "custom-settings-loader");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    public void onBrowseLogo() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Store Logo Image");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files (*.png, *.jpg, *.jpeg)", "*.png", "*.jpg", "*.jpeg")
        );
        Stage stage = (Stage) txtLogoPath.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            txtLogoPath.setText(file.getAbsolutePath());
        }
    }

    @FXML
    public void onSaveBusinessProfile() {
        String name = txtBizName.getText() != null ? txtBizName.getText().trim() : "";
        if (name.isBlank()) {
            showBanner(false, "Business Name is required and cannot be empty.");
            return;
        }

        try {
            BusinessProfileUpdateDto dto = new BusinessProfileUpdateDto(
                    name,
                    txtBizLegalName.getText() != null ? txtBizLegalName.getText().trim() : "",
                    txtBizPhone.getText() != null ? txtBizPhone.getText().trim() : "",
                    txtBizAddress.getText() != null ? txtBizAddress.getText().trim() : "",
                    txtBizLicense.getText() != null ? txtBizLicense.getText().trim() : ""
            );

            preferencesService.updateBusinessProfile(
                    dto,
                    txtInvoiceHeader.getText(),
                    txtInvoiceFooter.getText(),
                    txtLogoPath.getText()
            );

            showBanner(true, "Business profile and branding settings updated successfully!");
        } catch (Exception e) {
            log.error("Error updating business profile: {}", e.getMessage(), e);
            showBanner(false, "Failed to update business profile: " + e.getMessage());
        }
    }

    @FXML
    public void onSavePreferences() {
        try {
            long businessId = SessionContext.getRequiredBusinessId();
            String theme = cmbTheme.getValue() != null && cmbTheme.getValue().contains("Dark") ? "DARK" : "LIGHT";
            String lang = cmbLanguage.getValue() != null && cmbLanguage.getValue().contains("বাংলা") ? "bn" : "en";
            String dateFmt = cmbDateFormat.getValue() != null ? cmbDateFormat.getValue() : "YYYY-MM-DD";
            String numFmt = cmbNumberFormat.getValue() != null && cmbNumberFormat.getValue().contains("Standard") ? "STANDARD" : "SOUTH_ASIAN";
            String rowSize = cmbTableRowSize.getValue() != null ? cmbTableRowSize.getValue().toUpperCase() : "STANDARD";

            int lowStock = 10;
            try {
                lowStock = Integer.parseInt(txtDefaultLowStock.getText().trim());
                if (lowStock < 0) throw new NumberFormatException();
            } catch (Exception e) {
                showBanner(false, "Default low-stock threshold must be a positive integer.");
                return;
            }

            String repPeriod = cmbDefaultReportPeriod.getValue() != null ? cmbDefaultReportPeriod.getValue() : "THIS_MONTH";
            String bdtFmt = cmbBdtFormat.getValue() != null && cmbBdtFormat.getValue().contains("TK") ? "TK" : "BDT";
            boolean inAppAlerts = chkInAppAlerts.isSelected();

            AppPreferencesDto dto = new AppPreferencesDto(
                    businessId,
                    theme,
                    lang,
                    dateFmt,
                    numFmt,
                    rowSize,
                    lowStock,
                    repPeriod,
                    txtInvoiceHeader.getText(),
                    txtInvoiceFooter.getText(),
                    txtLogoPath.getText(),
                    bdtFmt,
                    inAppAlerts
            );

            preferencesService.updatePreferences(dto);

            if (chkAllowDirectGrn != null) {
                preferencesService.upsertSetting("scm.allow_direct_grn", String.valueOf(chkAllowDirectGrn.isSelected()));
            }
            if (chkAllowOverReceipt != null) {
                preferencesService.upsertSetting("scm.allow_over_receipt", String.valueOf(chkAllowOverReceipt.isSelected()));
            }
            if (cmbMrpPolicy != null && cmbMrpPolicy.getValue() != null) {
                preferencesService.upsertSetting("scm.mrp_policy", cmbMrpPolicy.getValue());
            }
            if (chkAllowNegativeStock != null) {
                preferencesService.upsertSetting("scm.allow_negative_stock", String.valueOf(chkAllowNegativeStock.isSelected()));
            }

            // Apply Theme immediately
            ThemeManager.applyTheme("DARK".equalsIgnoreCase(theme));

            // Apply Language immediately
            I18nSupport.setLanguage(lang);

            showBanner(true, "User preferences saved and applied successfully!");
        } catch (Exception e) {
            log.error("Error updating user preferences: {}", e.getMessage(), e);
            showBanner(false, "Failed to update preferences: " + e.getMessage());
        }
    }

    @FXML
    public void onRefreshAlerts() {
        loadNotifications();
        showBanner(true, "In-app alerts refreshed.");
    }

    @FXML
    public void onBackupDatabase() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Database Backup (ডাটা ব্যাকআপ সংরক্ষণ)");
        String defaultName = "smartkhata_backup_" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".skbak";
        chooser.setInitialFileName(defaultName);
        if (AppConfig.getBackupDir() != null && Files.exists(AppConfig.getBackupDir())) {
            chooser.setInitialDirectory(AppConfig.getBackupDir().toFile());
        }
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SmartKhata Backup (*.skbak)", "*.skbak"),
                new FileChooser.ExtensionFilter("SQLite Database (*.db)", "*.db")
        );

        Stage stage = (Stage) btnCreateBackup.getScene().getWindow();
        File selectedFile = chooser.showSaveDialog(stage);
        if (selectedFile == null) return;

        showBanner(true, "Creating database backup using SQLite VACUUM INTO...");
        btnCreateBackup.setDisable(true);

        Thread worker = new Thread(() -> {
            try {
                BackupMetadataDto metadata = backupService.createBackup(selectedFile.toPath(), "User requested manual backup");
                Platform.runLater(() -> {
                    btnCreateBackup.setDisable(false);
                    showBanner(true, "Backup completed successfully! Size: " + metadata.formattedSize());

                    AlertHelper.showSuccess(
                            resolveOwner(),
                            "Database Backup Verified (ডাটা ব্যাকআপ সম্পন্ন)",
                            String.format(
                                    "File: %s\nSize: %s\nTimestamp: %s\nSHA-256: %s\nStatus: Integrity Verified (OK)",
                                    metadata.fileName(),
                                    metadata.formattedSize(),
                                    metadata.createdAt().format(TIME_FMT),
                                    metadata.sha256()
                            )
                    );
                });
            } catch (Exception e) {
                log.error("Backup failed: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    btnCreateBackup.setDisable(false);
                    showBanner(false, "Backup failed: " + e.getMessage());
                });
            }
        }, "backup-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    public void onRestoreDatabase() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Backup File to Restore (রিস্টোর করার ব্যাকআপ নির্বাচন করুন)");
        if (AppConfig.getBackupDir() != null && Files.exists(AppConfig.getBackupDir())) {
            chooser.setInitialDirectory(AppConfig.getBackupDir().toFile());
        }
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SmartKhata Backups (*.skbak, *.db, *.zip)", "*.skbak", "*.db", "*.zip")
        );

        Stage stage = (Stage) btnRestoreBackup.getScene().getWindow();
        File selectedFile = chooser.showOpenDialog(stage);
        if (selectedFile == null) return;

        RestoreValidationResult validation = backupService.validateBackup(selectedFile.toPath());
        if (!validation.isValid()) {
            AlertHelper.showError(
                    resolveOwner(),
                    "Backup Validation Failed (ত্রুটিযুক্ত ব্যাকআপ)",
                    "The selected file cannot be restored:\n\n" + validation.errorMessage()
            );
            return;
        }

        BackupMetadataDto m = validation.metadata();
        boolean confirmed = AlertHelper.confirm(
                resolveOwner(),
                SmartKhataDialog.ActionTone.WARNING,
                "Confirm Database Restore",
                String.format(
                        "Candidate Store: %s\nSchema Version: %d\nCreated: %s\nSize: %s\nCustomers: %d, Products: %d, Sales: %d\n\nDo you wish to proceed with restoring this database?",
                        m.businessName(), m.schemaVersion(), m.createdAt().format(TIME_FMT), m.formattedSize(),
                        m.customerCount(), m.productCount(), m.saleCount()
                ),
                "Warning: Restore will replace the currently active database. An automated safety backup of your current database will be created first.",
                "Restore Database"
        );
        if (!confirmed) {
            return;
        }

        showBanner(true, "Restoring database from candidate backup...");
        btnRestoreBackup.setDisable(true);

        Thread worker = new Thread(() -> {
            try {
                backupService.restoreBackup(selectedFile.toPath(), true);
                Platform.runLater(() -> {
                    btnRestoreBackup.setDisable(false);
                    showBanner(true, "Database restored successfully!");

                    AlertHelper.showSuccess(
                            resolveOwner(),
                            "Database Restored Successfully (ডাটা রিস্টোর সম্পন্ন)",
                            "Your database has been restored from:\n" + selectedFile.getName() +
                                    "\n\nThe active session and profile have been synchronized."
                    );

                    // Reload view
                    loadBusinessProfile();
                    loadPreferences();
                    loadNotifications();
                });
            } catch (Exception e) {
                log.error("Restore failed: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    btnRestoreBackup.setDisable(false);
                    showBanner(false, "Restore failed: " + e.getMessage());
                });
            }
        }, "restore-worker");
        worker.setDaemon(true);
        worker.start();
    }

    // ==========================================
    // Tab 5 Actions: Users & Staff CRUD
    // ==========================================

    @FXML
    public void onAddUser() {
        Window owner = resolveOwner();

        TextField txtUsername = new TextField();
        txtUsername.setPromptText("e.g. cashier1");
        HBox userWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_USER, txtUsername);

        TextField txtFullName = new TextField();
        txtFullName.setPromptText("e.g. Abdus Salam");
        HBox nameWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_USER, txtFullName);

        ComboBox<UserRole> cmbRole = DialogComponents.styleCombo(
                new ComboBox<>(FXCollections.observableArrayList(UserRole.CASHIER, UserRole.OWNER)));
        cmbRole.setValue(UserRole.CASHIER);

        PasswordField txtPin = new PasswordField();
        txtPin.setPromptText("Min 4 characters");
        HBox pinWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_LOCK, txtPin);

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(
                        DialogComponents.SVG_USERS,
                        "Staff Profile & Credentials",
                        "Cashier role handles daily POS sales; Owner role has full administrative control."
                ),
                DialogComponents.responsiveTwoColGrid(
                        460.0,
                        DialogComponents.formField("Username", true, "Minimum 3 characters, no spaces", userWrap),
                        DialogComponents.formField("Full Name", true, null, nameWrap),
                        DialogComponents.formField("Role Permission", true, null, cmbRole),
                        DialogComponents.formField("PIN / Password", true, "Minimum 4 characters", pinWrap)
                )
        );

        Optional<String> result = SmartKhataDialog.<String>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Add Staff Account (নতুন স্টাফ একাউন্ট)")
                .subtitle("Create a Cashier or Owner user account for this store")
                .icon(DialogComponents.SVG_USERS, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(txtUsername)
                .cancelLabel("Cancel")
                .primaryAction("Create User", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String u = DialogComponents.trimToNull(txtUsername.getText());
                    if (u == null || u.length() < 3) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Username must be at least 3 characters.", txtUsername);
                    }
                    String fn = DialogComponents.trimToNull(txtFullName.getText());
                    if (fn == null) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Full Name is required.", txtFullName);
                    }
                    String pin = txtPin.getText() != null ? txtPin.getText().trim() : "";
                    if (pin.length() < 4) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "PIN / Password must be at least 4 characters.", txtPin);
                    }
                    try {
                        authService.createStaffUser(new StaffCreateDto(
                                SessionContext.getRequiredBusinessId(),
                                u, fn, cmbRole.getValue(), pin.toCharArray()
                        ));
                        return u;
                    } catch (RuntimeException e) {
                        throw new SmartKhataDialog.ValidationFocusException(e.getMessage(), txtUsername);
                    }
                })
                .showAndWait();

        result.ifPresent(u -> {
            showBanner(true, "Staff user @" + u + " created successfully!");
            loadUsers();
            loadAuditLogs();
        });
    }

    @FXML
    public void onEditUser() {
        User selected = tblUsers != null ? tblUsers.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            showBanner(false, "Please select a user from the table to edit.");
            return;
        }

        Window owner = resolveOwner();

        TextField txtFullName = new TextField(selected.fullName());
        txtFullName.setPromptText("Staff member full name");
        HBox nameWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_USER, txtFullName);

        ComboBox<UserRole> cmbRole = DialogComponents.styleCombo(
                new ComboBox<>(FXCollections.observableArrayList(UserRole.CASHIER, UserRole.OWNER)));
        cmbRole.setValue(selected.role());

        ComboBox<String> cmbStatus = DialogComponents.styleCombo(
                new ComboBox<>(FXCollections.observableArrayList("Active", "Inactive")));
        cmbStatus.setValue(selected.isActive() ? "Active" : "Inactive");

        PasswordField txtNewPin = new PasswordField();
        txtNewPin.setPromptText("Leave blank to keep unchanged");
        HBox pinWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_LOCK, txtNewPin);

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(
                        DialogComponents.SVG_EDIT,
                        "Update Account @" + selected.username(),
                        "Modify staff display name, role, account status, or reset login PIN."
                ),
                DialogComponents.responsiveTwoColGrid(
                        460.0,
                        DialogComponents.formField("Full Name", true, null, nameWrap),
                        DialogComponents.formField("Role Permission", true, null, cmbRole),
                        DialogComponents.formField("Account Status", true, null, cmbStatus),
                        DialogComponents.formField("New PIN (Optional)", false, "Min 4 chars if changing", pinWrap)
                )
        );

        Optional<Boolean> result = SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Edit Staff Account (স্টাফ একাউন্ট সম্পাদনা)")
                .subtitle("Editing user @" + selected.username())
                .icon(DialogComponents.SVG_EDIT, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(txtFullName)
                .cancelLabel("Cancel")
                .primaryAction("Save Changes", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String fn = DialogComponents.trimToNull(txtFullName.getText());
                    if (fn == null) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Full Name is required.", txtFullName);
                    }
                    String rawPin = txtNewPin.getText() != null ? txtNewPin.getText().trim() : "";
                    if (!rawPin.isEmpty() && rawPin.length() < 4) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "New PIN must be at least 4 characters when provided.", txtNewPin);
                    }
                    char[] pinChars = !rawPin.isEmpty() ? rawPin.toCharArray() : null;
                    try {
                        authService.updateStaffUser(new StaffUpdateDto(
                                selected.id(),
                                fn,
                                cmbRole.getValue(),
                                "Active".equals(cmbStatus.getValue()),
                                pinChars
                        ));
                        return Boolean.TRUE;
                    } catch (RuntimeException e) {
                        throw new SmartKhataDialog.ValidationFocusException(e.getMessage(), txtFullName);
                    }
                })
                .showAndWait();

        if (result.orElse(false)) {
            showBanner(true, "Staff user @" + selected.username() + " updated successfully!");
            loadUsers();
            loadAuditLogs();
        }
    }

    @FXML
    public void onDeleteUser() {
        User selected = tblUsers != null ? tblUsers.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            showBanner(false, "Please select a user to delete.");
            return;
        }

        boolean confirmed = AlertHelper.confirm(
                resolveOwner(),
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Staff User Account",
                "Delete staff user account @" + selected.username() + "?",
                "If this user has posted sales or receipts, deletion will be blocked and you should deactivate them instead.",
                "Delete User"
        );

        if (confirmed) {
            try {
                authService.deleteStaffUser(selected.id());
                showBanner(true, "Staff user @" + selected.username() + " deleted successfully!");
                loadUsers();
                loadAuditLogs();
            } catch (Exception e) {
                showBanner(false, e.getMessage());
            }
        }
    }

    @FXML
    public void onRefreshUsers() {
        loadUsers();
        showBanner(true, "Staff users list refreshed.");
    }

    // ==========================================
    // Tab 6 Actions: Audit Logs & Custom Settings
    // ==========================================

    @FXML
    public void onAddAuditNote() {
        Optional<String> noteOpt = AlertHelper.promptInput(
                resolveOwner(),
                "Add Audit Trail Note",
                "Record an immutable audit observation or physical cash count note",
                "Note / Summary",
                "",
                "e.g. Evening cash drawer count verified",
                true,
                "Save Note"
        );

        noteOpt.ifPresent(note -> {
            if (!note.isBlank()) {
                preferencesService.recordAuditLog("MANUAL_NOTE", "SYSTEM", null, note.trim());
                showBanner(true, "Audit note recorded.");
                loadAuditLogs();
            }
        });
    }

    @FXML
    public void onDeleteAuditLog() {
        AuditLogListItemDto selected = tblAuditLogs != null ? tblAuditLogs.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            showBanner(false, "Please select an audit log entry to delete.");
            return;
        }

        try {
            preferencesService.deleteAuditLog(selected.id());
            showBanner(true, "Audit log #" + selected.id() + " deleted.");
            loadAuditLogs();
        } catch (Exception e) {
            showBanner(false, "Failed to delete log: " + e.getMessage());
        }
    }

    @FXML
    public void onClearAuditLogs() {
        boolean confirmed = AlertHelper.confirm(
                resolveOwner(),
                SmartKhataDialog.ActionTone.DANGER,
                "Clear All Audit Logs",
                "Are you sure you want to clear all historical audit logs for this store?",
                "This permanently removes historical audit entries from your database and cannot be undone.",
                "Clear All Logs"
        );

        if (confirmed) {
            try {
                preferencesService.clearAuditLogs();
                showBanner(true, "All audit trail entries cleared.");
                loadAuditLogs();
            } catch (Exception e) {
                showBanner(false, "Failed to clear logs: " + e.getMessage());
            }
        }
    }

    @FXML
    public void onRefreshAuditLogs() {
        loadAuditLogs();
        showBanner(true, "Audit logs refreshed.");
    }

    @FXML
    public void onAddSettingKey() {
        Window owner = resolveOwner();
        SettingEntryDto selected = tblCustomSettings != null ? tblCustomSettings.getSelectionModel().getSelectedItem() : null;

        TextField txtKey = new TextField(selected != null ? selected.key() : "");
        txtKey.setPromptText("e.g. hardware.printer_baudrate");
        HBox keyWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, txtKey);

        TextField txtValue = new TextField(selected != null ? selected.value() : "");
        txtValue.setPromptText("e.g. 9600");
        HBox valueWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_SETTINGS, txtValue);

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(
                        DialogComponents.SVG_SETTINGS,
                        "Key/Value Application Flag",
                        "Create or override store configuration parameters."
                ),
                DialogComponents.formField("Setting Key", true, null, keyWrap),
                DialogComponents.formField("Setting Value", false, null, valueWrap)
        );

        Optional<String> savedKey = SmartKhataDialog.<String>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Set / Edit Application Parameter")
                .subtitle("Configure key/value system flag")
                .icon(DialogComponents.SVG_SETTINGS, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(selected != null ? txtValue : txtKey)
                .cancelLabel("Cancel")
                .primaryAction("Save Setting", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String k = DialogComponents.trimToNull(txtKey.getText());
                    if (k == null) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Setting key cannot be empty.", txtKey);
                    }
                    String v = txtValue.getText() != null ? txtValue.getText().trim() : "";
                    try {
                        preferencesService.upsertSetting(k, v);
                        return k;
                    } catch (RuntimeException e) {
                        throw new SmartKhataDialog.ValidationFocusException(e.getMessage(), txtKey);
                    }
                })
                .showAndWait();

        savedKey.ifPresent(k -> {
            showBanner(true, "Setting [" + k + "] saved successfully!");
            loadCustomSettings();
        });
    }

    @FXML
    public void onDeleteSettingKey() {
        SettingEntryDto selected = tblCustomSettings != null ? tblCustomSettings.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            showBanner(false, "Please select a setting key from the table to delete.");
            return;
        }

        boolean confirmed = AlertHelper.confirm(
                resolveOwner(),
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Setting Key",
                "Remove custom setting key \"" + selected.key() + "\"?",
                "Application behavior will revert to built-in default for this parameter.",
                "Remove Key"
        );

        if (confirmed) {
            try {
                preferencesService.deleteSetting(selected.key());
                showBanner(true, "Setting [" + selected.key() + "] removed.");
                loadCustomSettings();
            } catch (Exception e) {
                showBanner(false, e.getMessage());
            }
        }
    }

    @FXML
    public void onRefreshSettingsTable() {
        loadCustomSettings();
        showBanner(true, "Settings table refreshed.");
    }

    @FXML
    public void onLoadSampleData() {
        Business biz = SessionContext.getCurrentBusiness();
        User user = SessionContext.getCurrentUser();
        if (biz == null || user == null) {
            showBanner(false, "No active authenticated session.");
            return;
        }

        boolean confirmed = AlertHelper.confirm(
                resolveOwner(),
                SmartKhataDialog.ActionTone.WARNING,
                "Load Sample Grocery Data (নমুনা ডাটা লোড)",
                "Load representative grocery products (with packaging artwork), categories, suppliers, opening balances, sales, purchases, expenses, and audit logs?",
                "Demo catalog and transaction records will be populated across all local tables for testing and exploration.",
                "Load Sample Data"
        );

        if (!confirmed) {
            return;
        }

        showBanner(true, "Seeding sample demo catalog and transactions across all 19 SQLite tables...");
        if (btnLoadSampleData != null) btnLoadSampleData.setDisable(true);

        Thread worker = new Thread(() -> {
            try {
                boolean seeded = SampleDataSeeder.seedDemoDataForBusiness(biz.id(), user.id());
                Platform.runLater(() -> {
                    if (btnLoadSampleData != null) btnLoadSampleData.setDisable(false);
                    if (seeded) {
                        showBanner(true, "Sample grocery data loaded successfully across all tables!");
                        loadUsers();
                        loadAuditLogs();
                        loadCustomSettings();
                        loadNotifications();

                        AlertHelper.showSuccess(
                                resolveOwner(),
                                "Sample Grocery Catalog Ready (নমুনা ডাটা প্রস্তুত)",
                                "Realistic grocery products, images, suppliers, customers, and transactions are now available across POS, Inventory, Sales, and Reports!"
                        );
                    } else {
                        showBanner(true, "Sample demo dataset is already loaded for this store.");
                    }
                });
            } catch (Exception e) {
                log.error("Failed to load sample demo data: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    if (btnLoadSampleData != null) btnLoadSampleData.setDisable(false);
                    showBanner(false, "Failed to load sample data: " + e.getMessage());
                });
            }
        }, "sample-seeder-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private Window resolveOwner() {
        if (settingsTabPane != null && settingsTabPane.getScene() != null) {
            return settingsTabPane.getScene().getWindow();
        }
        if (btnCreateBackup != null && btnCreateBackup.getScene() != null) {
            return btnCreateBackup.getScene().getWindow();
        }
        return null;
    }

    private void applyLocalizedLabels(Locale locale) {
        lblHeaderTitle.setText(I18nSupport.get("settings.title"));
        lblHeaderSubtitle.setText(I18nSupport.get("settings.subtitle"));
        tabBusinessProfile.setText("  " + I18nSupport.get("settings.tab.business") + "  ");
        tabPreferences.setText("  " + I18nSupport.get("settings.tab.preferences") + "  ");
        tabNotifications.setText("  " + I18nSupport.get("settings.tab.notifications") + "  ");
        tabAbout.setText("  " + I18nSupport.get("settings.tab.about") + "  ");
        if (tabUsers != null) tabUsers.setText("  " + ("bn".equals(locale.getLanguage()) ? "স্টাফ ও ইউজার" : "Users & Staff") + "  ");
        if (tabAuditAndSettings != null) tabAuditAndSettings.setText("  " + ("bn".equals(locale.getLanguage()) ? "অডিট ও সেটিংস কী" : "Audit & Settings") + "  ");

        lblBizName.setText(I18nSupport.get("label.business_name") + " *");
        lblBizLegalName.setText(I18nSupport.get("label.legal_name"));
        lblBizPhone.setText(I18nSupport.get("label.phone"));
        lblBizLicense.setText(I18nSupport.get("label.trade_license"));
        lblBizAddress.setText(I18nSupport.get("label.address"));
        lblInvoiceHeader.setText(I18nSupport.get("label.invoice_header"));
        lblInvoiceFooter.setText(I18nSupport.get("label.invoice_footer"));
        lblLogoPath.setText(I18nSupport.get("label.logo_path"));

        lblTheme.setText(I18nSupport.get("label.theme"));
        lblLanguage.setText(I18nSupport.get("label.language"));
        lblDateFormat.setText(I18nSupport.get("label.date_format"));
        lblNumberFormat.setText(I18nSupport.get("label.number_format"));
        lblTableRowSize.setText(I18nSupport.get("label.table_row_size"));
        lblDefaultLowStock.setText(I18nSupport.get("label.low_stock_threshold"));
        lblDefaultReportPeriod.setText(I18nSupport.get("label.report_period"));

        btnSaveBusinessProfile.setText(I18nSupport.get("action.save"));
        btnSavePreferences.setText(I18nSupport.get("action.save"));
    }

    private void showBanner(boolean success, String message) {
        statusBanner.setVisible(true);
        statusBanner.setManaged(true);
        lblStatusMessage.setText(message);
        if (success) {
            statusBanner.setStyle("-fx-background-color: #DCFCE7; -fx-border-color: #86EFAC; -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-radius: 8;");
            lblStatusMessage.setStyle("-fx-text-fill: #15803D; -fx-font-weight: 600; -fx-font-size: 13px;");
        } else {
            statusBanner.setStyle("-fx-background-color: #FEE2E2; -fx-border-color: #FCA5A5; -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-radius: 8;");
            lblStatusMessage.setStyle("-fx-text-fill: #B91C1C; -fx-font-weight: 600; -fx-font-size: 13px;");
        }
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase() + text.substring(1).toLowerCase();
    }
}


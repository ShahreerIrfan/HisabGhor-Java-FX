package com.smartkhata.controller;

import com.smartkhata.model.dto.BusinessProfileUpdateDto;
import com.smartkhata.model.dto.LowStockItemDto;
import com.smartkhata.model.dto.OutstandingDuesSummary;
import com.smartkhata.model.dto.SearchResultItemDto;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.api.DashboardService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.LocalAuthService;
import com.smartkhata.service.impl.SqliteDashboardService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.ViewRouter;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.app.SmartKhataApp;
import com.smartkhata.util.BengaliMoneyFormatter;
import com.smartkhata.util.I18nSupport;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for the primary desktop application window frame.
 */
public class MainShellController {

    private static final Logger log = LoggerFactory.getLogger(MainShellController.class);
    private static final DateTimeFormatter SHORT_MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter FULL_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final AuthService authService = new LocalAuthService();
    private final DashboardService dashboardService = new SqliteDashboardService();
    private ContextMenu searchResultsMenu;

    @FXML private BorderPane rootPane;
    @FXML private TextField globalSearchField;
    @FXML private Button btnDateRange;
    @FXML private Button btnNotifications;
    @FXML private Circle notificationDot;
    @FXML private HBox userProfileBox;
    @FXML private Label userInitialsLabel;
    @FXML private Label storeNameLabel;
    @FXML private Label storeRoleLabel;
    @FXML private StackPane contentArea;

    // Navigation buttons
    @FXML private ToggleButton navDashboardBtn;
    @FXML private ToggleButton navCustomersBtn;
    @FXML private ToggleButton navSuppliersBtn;
    @FXML private ToggleButton navSalesBtn;
    @FXML private ToggleButton navPurchasesBtn;
    @FXML private ToggleButton navPaymentsBtn;
    @FXML private ToggleButton navExpensesBtn;
    @FXML private ToggleButton navInventoryBtn;
    @FXML private ToggleButton navReportsBtn;
    @FXML private ToggleButton navSettingsBtn;
    @FXML private ToggleGroup navGroup;

    private LocalDate activeStartDate;
    private LocalDate activeEndDate;
    private String activePeriodSubtitle = "for this month";
    private volatile CompletableFuture<Void> badgeRefreshFuture = CompletableFuture.completedFuture(null);

    @FXML
    public void initialize() {
        log.info("Initializing MainShellController...");

        updateProfileLabels();
        initializeDateRangeState();

        // Keep sidebar selection locked on one active module
        if (navGroup != null) {
            navGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
                if (newToggle == null && oldToggle != null) {
                    oldToggle.setSelected(true);
                }
            });
        }

        if (contentArea != null) {
            ViewRouter.setContainer(contentArea);
            ViewRouter.registerMainShell(this);
            ViewRouter.setOnNavigateListener(viewId -> {
                syncNavSelection(viewId);
                if (viewId == ViewRouter.ViewId.DASHBOARD) {
                    refreshNotificationBadge();
                }
            });
            ViewRouter.navigateTo(ViewRouter.ViewId.DASHBOARD);
        }

        if (navDashboardBtn != null) {
            navDashboardBtn.setSelected(true);
        }

        setupGlobalSearch();
        refreshNotificationBadge();

        I18nSupport.addLocaleChangeListener(loc -> updateLocalizedNavLabels());
        updateLocalizedNavLabels();
    }

    private void initializeDateRangeState() {
        LocalDate now = LocalDate.now();
        activeStartDate = now.withDayOfMonth(1);
        activeEndDate = now.withDayOfMonth(now.lengthOfMonth());
        activePeriodSubtitle = "for this month";
        updateDateRangeButtonText();
    }

    private void updateDateRangeButtonText() {
        if (btnDateRange == null || activeStartDate == null || activeEndDate == null) return;
        String rangeText;
        if (activeStartDate.equals(activeEndDate)) {
            rangeText = activeStartDate.format(FULL_DATE_FMT);
        } else if (activeStartDate.getYear() == activeEndDate.getYear()) {
            rangeText = activeStartDate.format(SHORT_MONTH_DAY) + " – " + activeEndDate.format(FULL_DATE_FMT);
        } else {
            rangeText = activeStartDate.format(FULL_DATE_FMT) + " – " + activeEndDate.format(FULL_DATE_FMT);
        }
        btnDateRange.setText(rangeText + "  \u25BE");
    }

    private void applyDateRangeToDashboard(boolean navigateIfDifferent) {
        if (navigateIfDifferent && ViewRouter.getCurrentView() != ViewRouter.ViewId.DASHBOARD) {
            ViewRouter.navigateTo(ViewRouter.ViewId.DASHBOARD);
        }
        DashboardController dc = ViewRouter.getDashboardController();
        if (dc != null) {
            dc.setDateRange(activeStartDate, activeEndDate, activePeriodSubtitle);
        }
    }

    @FXML
    private void onDateRangeClicked() {
        if (btnDateRange == null) return;
        ContextMenu menu = new ContextMenu();
        LocalDate now = LocalDate.now();

        MenuItem todayItem = new MenuItem("Today (" + now.format(SHORT_MONTH_DAY) + ")");
        todayItem.setOnAction(e -> {
            activeStartDate = now;
            activeEndDate = now;
            activePeriodSubtitle = "for today";
            updateDateRangeButtonText();
            applyDateRangeToDashboard(true);
        });

        MenuItem thisWeekItem = new MenuItem("This Week");
        thisWeekItem.setOnAction(e -> {
            activeStartDate = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
            activeEndDate = now;
            activePeriodSubtitle = "for this week";
            updateDateRangeButtonText();
            applyDateRangeToDashboard(true);
        });

        MenuItem thisMonthItem = new MenuItem("This Month");
        thisMonthItem.setOnAction(e -> {
            activeStartDate = now.withDayOfMonth(1);
            activeEndDate = now.withDayOfMonth(now.lengthOfMonth());
            activePeriodSubtitle = "for this month";
            updateDateRangeButtonText();
            applyDateRangeToDashboard(true);
        });

        MenuItem lastMonthItem = new MenuItem("Last Month");
        lastMonthItem.setOnAction(e -> {
            LocalDate prev = now.minusMonths(1);
            activeStartDate = prev.withDayOfMonth(1);
            activeEndDate = prev.withDayOfMonth(prev.lengthOfMonth());
            activePeriodSubtitle = "for last month";
            updateDateRangeButtonText();
            applyDateRangeToDashboard(true);
        });

        MenuItem thisYearItem = new MenuItem("This Year");
        thisYearItem.setOnAction(e -> {
            activeStartDate = now.withDayOfYear(1);
            activeEndDate = now;
            activePeriodSubtitle = "for this year";
            updateDateRangeButtonText();
            applyDateRangeToDashboard(true);
        });

        MenuItem customItem = new MenuItem("Custom Date Range...");
        customItem.setOnAction(e -> showCustomDateRangeDialog());

        menu.getItems().addAll(
                todayItem, thisWeekItem, thisMonthItem, lastMonthItem, thisYearItem,
                new SeparatorMenuItem(), customItem
        );
        menu.show(btnDateRange, Side.BOTTOM, 0, 4);
    }

    private void showCustomDateRangeDialog() {
        Window owner = rootPane != null && rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null;

        DatePicker dpStart = DialogComponents.styleDatePicker(new DatePicker(activeStartDate));
        DatePicker dpEnd = DialogComponents.styleDatePicker(new DatePicker(activeEndDate));

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(
                        DialogComponents.SVG_CALENDAR,
                        "Reporting Period",
                        "Choose start and end dates for dashboard KPI cards and charts."
                ),
                DialogComponents.formField("Start Date", true, null, dpStart),
                DialogComponents.formField("End Date", true, "Dates automatically align if End Date precedes Start Date.", dpEnd)
        );

        Optional<Pair<LocalDate, LocalDate>> res = SmartKhataDialog.<Pair<LocalDate, LocalDate>>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Select Dashboard Date Range")
                .subtitle("Filter overview metrics across a custom period")
                .icon(DialogComponents.SVG_CALENDAR, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(dpStart)
                .cancelLabel("Cancel")
                .primaryAction("Apply Range", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    LocalDate s = dpStart.getValue();
                    if (s == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Please select a valid Start Date.", dpStart);
                    }
                    LocalDate e = dpEnd.getValue();
                    if (e == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Please select a valid End Date.", dpEnd);
                    }
                    return e.isBefore(s) ? new Pair<>(e, s) : new Pair<>(s, e);
                })
                .showAndWait();

        res.ifPresent(pair -> {
            activeStartDate = pair.getKey();
            activeEndDate = pair.getValue();
            activePeriodSubtitle = "for " + activeStartDate.format(SHORT_MONTH_DAY) + " - " + activeEndDate.format(FULL_DATE_FMT);
            updateDateRangeButtonText();
            applyDateRangeToDashboard(true);
        });
    }

    private void refreshNotificationBadge() {
        long businessId = resolveBusinessId();
        badgeRefreshFuture = CompletableFuture.runAsync(() -> {
            try {
                List<LowStockItemDto> lowStock = dashboardService.getLowStockAlerts(businessId, 5);
                OutstandingDuesSummary dues = dashboardService.getOutstandingDues(businessId);
                boolean hasAlerts = !lowStock.isEmpty() || dues.customersWithDueCount() > 0;
                Platform.runLater(() -> {
                    if (notificationDot != null) {
                        notificationDot.setVisible(hasAlerts);
                    }
                });
            } catch (Exception e) {
                log.debug("Notification badge refresh skipped: {}", e.getMessage());
            }
        });
    }

    public void shutdown() {
        try {
            CompletableFuture<Void> pending = badgeRefreshFuture;
            if (pending != null && !pending.isDone()) {
                pending.get(1, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.debug("Notification badge refresh wait interrupted: {}", e.getMessage());
        }
    }

    @FXML
    private void onNotificationsClicked() {
        if (btnNotifications == null) return;
        long businessId = resolveBusinessId();

        CompletableFuture.supplyAsync(() -> {
            List<LowStockItemDto> lowStock = dashboardService.getLowStockAlerts(businessId, 6);
            OutstandingDuesSummary dues = dashboardService.getOutstandingDues(businessId);
            return new Pair<>(lowStock, dues);
        }).thenAccept(pair -> Platform.runLater(() -> {
            List<LowStockItemDto> lowStock = pair.getKey();
            OutstandingDuesSummary dues = pair.getValue();

            if (notificationDot != null) {
                notificationDot.setVisible(!lowStock.isEmpty() || dues.customersWithDueCount() > 0);
            }

            ContextMenu menu = new ContextMenu();
            if (lowStock.isEmpty() && dues.customersWithDueCount() == 0) {
                MenuItem okItem = new MenuItem("All caught up — no low-stock or overdue alerts.");
                okItem.setDisable(true);
                menu.getItems().add(okItem);
            } else {
                if (dues.customersWithDueCount() > 0) {
                    MenuItem dueItem = new MenuItem(
                            "Receivables Due: " + BengaliMoneyFormatter.format(dues.totalDues())
                                    + " (" + dues.customersWithDueCount() + " customer"
                                    + (dues.customersWithDueCount() == 1 ? "" : "s") + ")"
                    );
                    dueItem.setOnAction(e -> ViewRouter.navigateTo(ViewRouter.ViewId.CUSTOMERS));
                    menu.getItems().add(dueItem);
                }
                if (!lowStock.isEmpty() && dues.customersWithDueCount() > 0) {
                    menu.getItems().add(new SeparatorMenuItem());
                }
                for (LowStockItemDto item : lowStock) {
                    MenuItem stockItem = new MenuItem(
                            "Low Stock: " + item.productName() + " (" + item.currentStock() + " " + item.unit() + ")"
                    );
                    stockItem.setOnAction(e -> ViewRouter.navigateTo(ViewRouter.ViewId.INVENTORY));
                    menu.getItems().add(stockItem);
                }
            }

            menu.show(btnNotifications, Side.BOTTOM, -180, 6);
        }));
    }

    private void updateLocalizedNavLabels() {
        if (navDashboardBtn != null) navDashboardBtn.setText(I18nSupport.get("nav.dashboard"));
        if (navCustomersBtn != null) navCustomersBtn.setText(I18nSupport.get("nav.customers"));
        if (navSuppliersBtn != null) navSuppliersBtn.setText(I18nSupport.get("nav.suppliers"));
        if (navSalesBtn != null) navSalesBtn.setText(I18nSupport.get("nav.sales"));
        if (navPurchasesBtn != null) navPurchasesBtn.setText(I18nSupport.get("nav.purchases"));
        if (navPaymentsBtn != null) navPaymentsBtn.setText(I18nSupport.get("nav.payments"));
        if (navExpensesBtn != null) navExpensesBtn.setText(I18nSupport.get("nav.expenses"));
        if (navInventoryBtn != null) navInventoryBtn.setText(I18nSupport.get("nav.inventory"));
        if (navReportsBtn != null) navReportsBtn.setText(I18nSupport.get("nav.reports"));
        if (navSettingsBtn != null) navSettingsBtn.setText(I18nSupport.get("nav.settings"));
    }

    private void setupGlobalSearch() {
        if (globalSearchField == null) return;

        globalSearchField.setPromptText("Search customers, invoices, products...");
        globalSearchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (globalSearchField.getParent() != null) {
                var styles = globalSearchField.getParent().getStyleClass();
                if (Boolean.TRUE.equals(isFocused)) {
                    if (!styles.contains("search-field-focused")) {
                        styles.add("search-field-focused");
                    }
                } else {
                    styles.remove("search-field-focused");
                }
            }
        });
        searchResultsMenu = new ContextMenu();

        globalSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().length() < 2) {
                searchResultsMenu.hide();
                return;
            }
            long businessId = resolveBusinessId();
            List<SearchResultItemDto> results = dashboardService.globalSearch(businessId, newVal.trim(), 8);
            searchResultsMenu.getItems().clear();

            if (results.isEmpty()) {
                MenuItem emptyItem = new MenuItem("No matching records found for \"" + newVal.trim() + "\"");
                emptyItem.setDisable(true);
                searchResultsMenu.getItems().add(emptyItem);
            } else {
                for (SearchResultItemDto item : results) {
                    MenuItem mi = new MenuItem("[" + item.category() + "] " + item.title() + " — " + item.subtitle());
                    mi.setOnAction(e -> {
                        ViewRouter.navigateTo(item.targetView());
                        globalSearchField.clear();
                        searchResultsMenu.hide();
                    });
                    searchResultsMenu.getItems().add(mi);
                }
            }
            if (globalSearchField.getScene() != null && !searchResultsMenu.isShowing()) {
                searchResultsMenu.show(globalSearchField, Side.BOTTOM, 0, 4);
            }
        });

        Platform.runLater(() -> {
            if (rootPane != null && rootPane.getScene() != null) {
                rootPane.getScene().getAccelerators().put(
                        new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN),
                        () -> globalSearchField.requestFocus()
                );
            }
        });
    }

    private void syncNavSelection(ViewRouter.ViewId viewId) {
        if (viewId == null) return;
        switch (viewId) {
            case DASHBOARD -> { if (navDashboardBtn != null) navDashboardBtn.setSelected(true); }
            case CUSTOMERS -> { if (navCustomersBtn != null) navCustomersBtn.setSelected(true); }
            case SUPPLIERS -> { if (navSuppliersBtn != null) navSuppliersBtn.setSelected(true); }
            case SALES -> { if (navSalesBtn != null) navSalesBtn.setSelected(true); }
            case PURCHASES -> { if (navPurchasesBtn != null) navPurchasesBtn.setSelected(true); }
            case PAYMENTS -> { if (navPaymentsBtn != null) navPaymentsBtn.setSelected(true); }
            case EXPENSES -> { if (navExpensesBtn != null) navExpensesBtn.setSelected(true); }
            case INVENTORY -> { if (navInventoryBtn != null) navInventoryBtn.setSelected(true); }
            case REPORTS -> { if (navReportsBtn != null) navReportsBtn.setSelected(true); }
            case SETTINGS -> { if (navSettingsBtn != null) navSettingsBtn.setSelected(true); }
        }
    }

    private long resolveBusinessId() {
        Business biz = SessionContext.getCurrentBusiness();
        return biz != null && biz.id() != null ? biz.id() : 1L;
    }

    private void updateProfileLabels() {
        Business biz = SessionContext.getCurrentBusiness();
        User user = SessionContext.getCurrentUser();

        String displayName = (biz != null && biz.name() != null && !biz.name().isBlank())
                ? biz.name().trim()
                : (user != null && user.fullName() != null && !user.fullName().isBlank() ? user.fullName().trim() : "SmartKhata");

        if (storeNameLabel != null) {
            storeNameLabel.setText(displayName);
        }
        if (userInitialsLabel != null) {
            userInitialsLabel.setText(computeInitials(displayName));
        }
        if (user != null && storeRoleLabel != null) {
            storeRoleLabel.setText(user.role().isOwner() ? "Business Owner" : "Staff Cashier");
        }
    }

    private static String computeInitials(String name) {
        if (name == null || name.isBlank()) {
            return "SK";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) {
            char a = Character.toUpperCase(parts[0].charAt(0));
            char b = Character.toUpperCase(parts[1].charAt(0));
            return "" + a + b;
        }
        String clean = parts[0].replaceAll("[^A-Za-z0-9]", "");
        if (clean.length() >= 2) {
            return clean.substring(0, 2).toUpperCase(Locale.ENGLISH);
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase(Locale.ENGLISH);
    }

    @FXML
    private void onUserProfileMenu(MouseEvent event) {
        ContextMenu menu = new ContextMenu();

        MenuItem lockItem = new MenuItem("Lock Session");
        lockItem.setOnAction(e -> {
            log.info("User requested session lock");
            SessionContext.lock();
            SmartKhataApp.showLoginScreen();
        });

        MenuItem logoutItem = new MenuItem("Sign Out");
        logoutItem.setOnAction(e -> {
            log.info("User requested logout");
            authService.logout();
            SmartKhataApp.showLoginScreen();
        });

        MenuItem editProfileItem = new MenuItem("Edit Store Profile");
        editProfileItem.setOnAction(e -> showEditProfileDialog());

        menu.getItems().addAll(lockItem, logoutItem, new SeparatorMenuItem(), editProfileItem);
        menu.show(userProfileBox, event.getScreenX(), event.getScreenY());
    }

    private void showEditProfileDialog() {
        Business biz = SessionContext.getCurrentBusiness();
        if (biz == null) return;

        Window owner = rootPane != null && rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null;

        TextField nameField = new TextField(biz.name());
        nameField.setPromptText("e.g. Bismillah Enterprise & Grocery");
        HBox nameWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_SHOP, nameField);

        TextField phoneField = new TextField(biz.phone() != null ? biz.phone() : "");
        phoneField.setPromptText("e.g. 017XXXXXXXX");
        HBox phoneWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_PHONE, phoneField);

        TextField addrField = new TextField(biz.address() != null ? biz.address() : "");
        addrField.setPromptText("Shop number, road, market, city");
        HBox addrWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_PIN, addrField);

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(
                        DialogComponents.SVG_SHOP,
                        "Store Details & Address",
                        "Appears in the application header and printed POS/purchase receipts."
                ),
                DialogComponents.formField("Store Name", true, null, nameWrap),
                DialogComponents.formField("Phone Number", false, null, phoneWrap),
                DialogComponents.formField("Business Address", false, null, addrWrap)
        );

        Optional<BusinessProfileUpdateDto> res = SmartKhataDialog.<BusinessProfileUpdateDto>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Edit Business Profile")
                .subtitle("Update store name, phone number, and shop address")
                .icon(DialogComponents.SVG_SHOP, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(nameField)
                .cancelLabel("Cancel")
                .primaryAction("Save Changes", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String name = DialogComponents.trimToNull(nameField.getText());
                    if (name == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Store Name is required.", nameField);
                    }
                    BusinessProfileUpdateDto dto = new BusinessProfileUpdateDto(
                            name,
                            name,
                            DialogComponents.nonNull(DialogComponents.trimToNull(phoneField.getText())),
                            DialogComponents.nonNull(DialogComponents.trimToNull(addrField.getText())),
                            biz.tradeLicense()
                    );
                    try {
                        authService.updateBusinessProfile(biz.id(), dto);
                        return dto;
                    } catch (RuntimeException ex) {
                        throw new SmartKhataDialog.ValidationFocusException(ex.getMessage(), nameField);
                    }
                })
                .showAndWait();

        res.ifPresent(dto -> {
            updateProfileLabels();
            DashboardController dc = ViewRouter.getDashboardController();
            if (dc != null) {
                dc.refreshDashboard();
            }
            AlertHelper.showSuccess(owner, "Profile Updated", "Store profile has been saved successfully.");
        });
    }

    @FXML
    private void onNavigateDashboard() {
        log.debug("Navigating to Dashboard");
        ViewRouter.navigateTo(ViewRouter.ViewId.DASHBOARD);
    }

    @FXML
    private void onNavigateCustomers() {
        log.debug("Navigating to Customers");
        ViewRouter.navigateTo(ViewRouter.ViewId.CUSTOMERS);
    }

    @FXML
    private void onNavigateSuppliers() {
        log.debug("Navigating to Suppliers");
        ViewRouter.navigateTo(ViewRouter.ViewId.SUPPLIERS);
    }

    @FXML
    private void onNavigateSales() {
        log.debug("Navigating to Sales");
        ViewRouter.navigateTo(ViewRouter.ViewId.SALES);
    }

    @FXML
    private void onNavigatePurchases() {
        log.debug("Navigating to Purchases");
        ViewRouter.navigateTo(ViewRouter.ViewId.PURCHASES);
    }

    @FXML
    private void onNavigatePayments() {
        log.debug("Navigating to Payments");
        ViewRouter.navigateTo(ViewRouter.ViewId.PAYMENTS);
    }

    @FXML
    private void onNavigateExpenses() {
        log.debug("Navigating to Expenses");
        ViewRouter.navigateTo(ViewRouter.ViewId.EXPENSES);
    }

    @FXML
    private void onNavigateInventory() {
        log.debug("Navigating to Inventory");
        ViewRouter.navigateTo(ViewRouter.ViewId.INVENTORY);
    }

    @FXML
    private void onNavigateReports() {
        log.debug("Navigating to Reports");
        ViewRouter.navigateTo(ViewRouter.ViewId.REPORTS);
    }

    @FXML
    private void onNavigateSettings() {
        log.debug("Navigating to Settings");
        ViewRouter.navigateTo(ViewRouter.ViewId.SETTINGS);
    }
}

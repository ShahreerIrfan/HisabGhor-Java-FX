package com.smartkhata.controller;

import com.smartkhata.dao.CustomerDao;
import com.smartkhata.dao.SqliteCustomerDao;
import com.smartkhata.dao.SqliteSupplierDao;
import com.smartkhata.dao.SupplierDao;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.ReportCriteria;
import com.smartkhata.model.dto.ReportData;
import com.smartkhata.model.dto.SaleListItemDto;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.enums.ReportType;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.ReportService;
import com.smartkhata.service.api.SaleService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteCustomerService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteReportService;
import com.smartkhata.service.impl.SqliteSaleService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.modal.SaleOrderModals;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ReportsController {

    private static final Logger log = LoggerFactory.getLogger(ReportsController.class);

    private final ReportService reportService;
    private final CustomerDao customerDao;
    private final SupplierDao supplierDao;
    private final SaleService saleService;
    private final CustomerService customerService;
    private final InventoryService inventoryService;

    @FXML private ComboBox<ReportType> cmbReportType;
    @FXML private ComboBox<String> cmbPeriodPreset;
    @FXML private DatePicker dpStartDate;
    @FXML private DatePicker dpEndDate;
    @FXML private ComboBox<PartyOption> cmbPartySelector;
    @FXML private CheckBox chkLowStockOnly;
    @FXML private Button btnGenerateReport;

    @FXML private Button btnExportPdf;
    @FXML private Button btnExportCsv;
    @FXML private Button btnPrint;

    @FXML private Label lblReportTitle;
    @FXML private Label lblReportPeriod;
    @FXML private HBox pnlSummaryCards;
    @FXML private TableView<List<String>> tblReport;

    private ReportData currentReportData;

    public record PartyOption(Long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public ReportsController() {
        this(new SqliteReportService(), new SqliteCustomerDao(), new SqliteSupplierDao(),
                new SqliteSaleService(), new SqliteCustomerService(), new SqliteInventoryService());
    }

    public ReportsController(ReportService reportService, CustomerDao customerDao, SupplierDao supplierDao) {
        this(reportService, customerDao, supplierDao,
                new SqliteSaleService(), new SqliteCustomerService(), new SqliteInventoryService());
    }

    public ReportsController(ReportService reportService, CustomerDao customerDao, SupplierDao supplierDao,
                             SaleService saleService, CustomerService customerService, InventoryService inventoryService) {
        this.reportService = reportService;
        this.customerDao = customerDao;
        this.supplierDao = supplierDao;
        this.saleService = saleService;
        this.customerService = customerService;
        this.inventoryService = inventoryService;
    }

    @FXML
    public void initialize() {
        log.info("Initializing ReportsController...");

        if (cmbReportType != null) {
            cmbReportType.setItems(FXCollections.observableArrayList(ReportType.values()));
            cmbReportType.setValue(ReportType.SALES_SUMMARY);
        }

        LocalDate now = LocalDate.now();
        if (dpStartDate != null) dpStartDate.setValue(now.withDayOfMonth(1));
        if (dpEndDate != null) dpEndDate.setValue(now);

        if (cmbPeriodPreset != null) {
            cmbPeriodPreset.setItems(FXCollections.observableArrayList(
                    "This Month", "Today", "Yesterday", "This Week", "Last Month", "Custom Range"
            ));
            cmbPeriodPreset.setValue("This Month");
        }

        updateVisibilityForSelectedReport();

        if (SessionContext.isAuthenticated()) {
            onGenerateReport();
        }
    }

    public void refreshData() {
        if (SessionContext.isAuthenticated()) {
            onGenerateReport();
        }
    }

    @FXML
    public void onPeriodPresetChanged() {
        if (cmbPeriodPreset == null || dpStartDate == null || dpEndDate == null) return;
        String preset = cmbPeriodPreset.getValue();
        LocalDate now = LocalDate.now();
        if ("Today".equals(preset)) {
            dpStartDate.setValue(now);
            dpEndDate.setValue(now);
        } else if ("Yesterday".equals(preset)) {
            dpStartDate.setValue(now.minusDays(1));
            dpEndDate.setValue(now.minusDays(1));
        } else if ("This Week".equals(preset)) {
            dpStartDate.setValue(now.minusDays(6));
            dpEndDate.setValue(now);
        } else if ("This Month".equals(preset)) {
            dpStartDate.setValue(now.withDayOfMonth(1));
            dpEndDate.setValue(now);
        } else if ("Last Month".equals(preset)) {
            LocalDate firstLast = now.minusMonths(1).withDayOfMonth(1);
            dpStartDate.setValue(firstLast);
            dpEndDate.setValue(firstLast.withDayOfMonth(firstLast.lengthOfMonth()));
        }
    }

    @FXML
    public void onReportTypeChanged() {
        updateVisibilityForSelectedReport();
    }

    private void updateVisibilityForSelectedReport() {
        ReportType type = cmbReportType != null ? cmbReportType.getValue() : null;
        if (type == null) return;

        boolean isCustomerLedger = (type == ReportType.CUSTOMER_LEDGER);
        boolean isSupplierLedger = (type == ReportType.SUPPLIER_LEDGER || type == ReportType.SUPPLIER_PURCHASE_ANALYSIS);
        boolean isProductProfit = (type == ReportType.PRODUCT_PROFITABILITY);
        boolean isInventory = (type == ReportType.INVENTORY_VALUATION);

        if (cmbPartySelector != null) {
            boolean showFilter = isCustomerLedger || isSupplierLedger || isProductProfit;
            cmbPartySelector.setVisible(showFilter);
            cmbPartySelector.setManaged(showFilter);

            if (isCustomerLedger) {
                cmbPartySelector.setPromptText("Select Customer");
                loadCustomerOptions();
            } else if (isSupplierLedger) {
                cmbPartySelector.setPromptText(type == ReportType.SUPPLIER_PURCHASE_ANALYSIS ? "All Suppliers" : "Select Supplier");
                loadSupplierOptions(type == ReportType.SUPPLIER_PURCHASE_ANALYSIS);
            } else if (isProductProfit) {
                cmbPartySelector.setPromptText("All Categories");
                loadCategoryOptions();
            }
        }

        if (chkLowStockOnly != null) {
            chkLowStockOnly.setVisible(isInventory);
            chkLowStockOnly.setManaged(isInventory);
        }
    }

    private void loadCustomerOptions() {
        if (!SessionContext.isAuthenticated()) return;
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            List<Customer> customers = customerDao.search(conn, businessId, "", false);
            List<PartyOption> options = customers.stream()
                    .map(c -> new PartyOption(c.id(), c.name() + (c.phone() != null ? " (" + c.phone() + ")" : "")))
                    .toList();
            cmbPartySelector.setItems(FXCollections.observableArrayList(options));
            if (!options.isEmpty()) {
                cmbPartySelector.setValue(options.get(0));
            }
        } catch (SQLException e) {
            log.error("Failed to load customer options: {}", e.getMessage(), e);
        }
    }

    private void loadSupplierOptions(boolean includeAllOption) {
        if (!SessionContext.isAuthenticated()) return;
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            List<Supplier> suppliers = supplierDao.search(conn, businessId, "", false);
            List<PartyOption> options = new java.util.ArrayList<>();
            if (includeAllOption) {
                options.add(new PartyOption(null, "All Suppliers"));
            }
            suppliers.stream()
                    .map(s -> new PartyOption(s.id(), s.name() + (s.phone() != null ? " (" + s.phone() + ")" : "")))
                    .forEach(options::add);
            cmbPartySelector.setItems(FXCollections.observableArrayList(options));
            if (!options.isEmpty()) {
                cmbPartySelector.setValue(options.get(0));
            }
        } catch (SQLException e) {
            log.error("Failed to load supplier options: {}", e.getMessage(), e);
        }
    }

    private void loadCategoryOptions() {
        if (!SessionContext.isAuthenticated()) return;
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement("SELECT id, name FROM categories WHERE business_id = ? ORDER BY name ASC")) {
            ps.setLong(1, businessId);
            List<PartyOption> options = new java.util.ArrayList<>();
            options.add(new PartyOption(null, "ALL"));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    options.add(new PartyOption(rs.getLong("id"), rs.getString("name")));
                }
            }
            cmbPartySelector.setItems(FXCollections.observableArrayList(options));
            cmbPartySelector.setValue(options.get(0));
        } catch (SQLException e) {
            log.error("Failed to load category options: {}", e.getMessage(), e);
        }
    }

    @FXML
    public void onGenerateReport() {
        if (!SessionContext.isAuthenticated()) return;

        ReportType type = cmbReportType.getValue();
        if (type == null) return;

        LocalDate start = dpStartDate.getValue();
        LocalDate end = dpEndDate.getValue();
        Long partyId = null;
        String categoryFilter = null;

        if (type == ReportType.CUSTOMER_LEDGER || type == ReportType.SUPPLIER_LEDGER) {
            PartyOption selParty = cmbPartySelector.getValue();
            if (selParty == null) {
                AlertHelper.showInfo(null, "Selection Required", "Please select a customer or supplier to view ledger statement.");
                return;
            }
            partyId = selParty.id();
        } else if (type == ReportType.SUPPLIER_PURCHASE_ANALYSIS && cmbPartySelector.getValue() != null) {
            partyId = cmbPartySelector.getValue().id();
        } else if (type == ReportType.PRODUCT_PROFITABILITY && cmbPartySelector.getValue() != null) {
            categoryFilter = cmbPartySelector.getValue().name();
        }

        boolean lowStockOnly = chkLowStockOnly != null && chkLowStockOnly.isSelected();
        long businessId = SessionContext.getRequiredBusinessId();
        ReportCriteria criteria = new ReportCriteria(businessId, type, start, end, partyId, categoryFilter, null, lowStockOnly);

        CompletableFuture.supplyAsync(() -> reportService.generateReport(criteria))
                .thenAccept(data -> Platform.runLater(() -> renderReport(data)))
                .exceptionally(ex -> {
                    log.error("Failed to generate report: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> AlertHelper.showStartupError(null, "Report Generation Error", ex.getMessage(), (Exception) ex));
                    return null;
                });
    }

    private void renderReport(ReportData data) {
        this.currentReportData = data;

        if (lblReportTitle != null) lblReportTitle.setText(data.title());
        if (lblReportPeriod != null) lblReportPeriod.setText(data.periodOrAsOfLabel());

        renderSummaryCards(data.summaryCards());
        renderTable(data.headers(), data.rows());
    }

    private void renderSummaryCards(Map<String, String> summaryCards) {
        if (pnlSummaryCards == null) return;
        pnlSummaryCards.getChildren().clear();

        if (summaryCards == null || summaryCards.isEmpty()) return;

        for (Map.Entry<String, String> entry : summaryCards.entrySet()) {
            VBox card = new VBox(4);
            card.setStyle("-fx-background-color: white; -fx-padding: 10 16; -fx-background-radius: 8; -fx-border-color: #E2E8F0; -fx-border-radius: 8; -fx-min-width: 160px;");

            Label title = new Label(entry.getKey());
            title.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B; -fx-font-weight: 600;");

            Label value = new Label(entry.getValue());
            value.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #1E293B;");

            card.getChildren().addAll(title, value);
            pnlSummaryCards.getChildren().add(card);
        }
    }

    private void renderTable(List<String> headers, List<List<String>> rows) {
        if (tblReport == null) return;

        tblReport.getColumns().clear();

        for (int i = 0; i < headers.size(); i++) {
            final int colIndex = i;
            String headerText = headers.get(i);
            TableColumn<List<String>, String> col = new TableColumn<>(headerText);
            col.setCellValueFactory(cellData -> {
                List<String> row = cellData.getValue();
                return new SimpleStringProperty(colIndex < row.size() ? row.get(colIndex) : "");
            });

            String hLower = headerText.toLowerCase();
            if (hLower.contains("amount") || hLower.contains("paid") || hLower.contains("due")
                    || hLower.contains("price") || hLower.contains("total") || hLower.contains("stock")
                    || hLower.contains("debit") || hLower.contains("credit") || hLower.contains("balance")
                    || hLower.contains("profit") || hLower.contains("cogs") || hLower.contains("revenue") || hLower.contains("margin")) {
                col.setStyle("-fx-alignment: CENTER-RIGHT;");
            }

            col.setPrefWidth(122);
            tblReport.getColumns().add(col);
        }

        ReportType activeType = cmbReportType != null ? cmbReportType.getValue() : null;
        if (activeType == ReportType.SALES_SUMMARY && !headers.isEmpty()) {
            TableColumn<List<String>, String> actionsCol = new TableColumn<>("Actions");
            actionsCol.setSortable(false);
            actionsCol.setMinWidth(195.0);
            actionsCol.setPrefWidth(210.0);
            actionsCol.setCellValueFactory(cellData -> {
                List<String> row = cellData.getValue();
                return new SimpleStringProperty(row != null && !row.isEmpty() ? row.get(0) : "");
            });
            actionsCol.setCellFactory(col -> new TableCell<>() {
                private final Button btnView = new Button("View");
                private final Button btnEdit = new Button("Edit");
                private final Button btnDelete = new Button("Delete");
                private final HBox box = new HBox(5.0, btnView, btnEdit, btnDelete);
                private List<String> currentRow;

                {
                    box.setAlignment(Pos.CENTER);

                    btnView.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_RECEIPT, "#075C32", 11.5));
                    btnView.getStyleClass().add("pos-row-action-details");
                    btnView.setTooltip(new Tooltip("View order details & receipt"));
                    btnView.setOnAction(e -> handleSalesReportRowAction(currentRow, "VIEW"));

                    btnEdit.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_EDIT, "#1D4ED8", 11.5));
                    btnEdit.getStyleClass().add("pos-row-action-edit");
                    btnEdit.setTooltip(new Tooltip("Edit order customer, items, prices or payment"));
                    btnEdit.setOnAction(e -> handleSalesReportRowAction(currentRow, "EDIT"));

                    btnDelete.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_TRASH, "#DC2626", 11.5));
                    btnDelete.getStyleClass().add("pos-row-action-delete");
                    btnDelete.setTooltip(new Tooltip("Delete order and restore stock/balances"));
                    btnDelete.setOnAction(e -> handleSalesReportRowAction(currentRow, "DELETE"));
                }

                @Override
                protected void updateItem(String invoiceNo, boolean empty) {
                    super.updateItem(invoiceNo, empty);
                    List<String> row = getTableRow() != null ? getTableRow().getItem() : null;
                    currentRow = row;
                    if (empty || row == null || row.isEmpty() || row.get(0) == null || row.get(0).isBlank()) {
                        setGraphic(null);
                        setText(null);
                    } else {
                        String status = row.size() > 8 ? row.get(8) : "";
                        btnEdit.setDisable("VOIDED".equalsIgnoreCase(status) || "REVERSED".equalsIgnoreCase(status));
                        setGraphic(box);
                        setText(null);
                    }
                }
            });
            tblReport.getColumns().add(actionsCol);

            tblReport.setRowFactory(tv -> {
                TableRow<List<String>> row = new TableRow<>();
                row.setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !row.isEmpty()) {
                        handleSalesReportRowAction(row.getItem(), "VIEW");
                    }
                });
                return row;
            });
        } else {
            tblReport.setRowFactory(null);
        }

        tblReport.setItems(FXCollections.observableArrayList(rows));
    }

    private void handleSalesReportRowAction(List<String> row, String action) {
        if (row == null || row.isEmpty() || !SessionContext.isAuthenticated()) return;
        String invoiceNo = row.get(0);
        if (invoiceNo == null || invoiceNo.isBlank()) return;

        Window owner = tblReport != null && tblReport.getScene() != null ? tblReport.getScene().getWindow() : null;
        long businessId = SessionContext.getRequiredBusinessId();
        Optional<SaleListItemDto> match = saleService
                .searchSales(businessId, invoiceNo.trim(), null, null, null, null)
                .stream()
                .filter(s -> invoiceNo.trim().equalsIgnoreCase(s.invoiceNumber()))
                .findFirst();

        if (match.isEmpty()) {
            AlertHelper.showError(owner, "Order Not Found",
                    "Could not find POS order " + invoiceNo + " in the database.");
            return;
        }
        SaleListItemDto item = match.get();
        switch (action) {
            case "VIEW" -> SaleOrderModals.showOrderDetailsModal(
                    owner, item, saleService, customerService, inventoryService, this::onGenerateReport);
            case "EDIT" -> SaleOrderModals.showEditOrderModal(
                    owner, item, saleService, customerService, inventoryService, this::onGenerateReport);
            case "DELETE" -> SaleOrderModals.confirmAndDeleteOrder(
                    owner, item, saleService, this::onGenerateReport);
            default -> {}
        }
    }

    @FXML
    public void onExportPdf() {
        if (currentReportData == null || currentReportData.rowCount() == 0) {
            AlertHelper.showInfo(null, "Export Notice", "Please generate a report with records before exporting to PDF.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save PDF Report");
        String safeName = currentReportData.title().replaceAll("[^a-zA-Z0-9_-]", "_") + ".pdf";
        fileChooser.setInitialFileName(safeName);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Document (*.pdf)", "*.pdf"));

        File file = fileChooser.showSaveDialog(tblReport.getScene().getWindow());
        if (file != null) {
            try {
                byte[] pdfBytes = reportService.exportToPdf(currentReportData);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(pdfBytes);
                }
                AlertHelper.showInfo(null, "Export Complete", "PDF report exported successfully to:\n" + file.getAbsolutePath());
            } catch (Exception ex) {
                log.error("Failed to export PDF: {}", ex.getMessage(), ex);
                AlertHelper.showStartupError(null, "PDF Export Failed", ex.getMessage(), ex);
            }
        }
    }

    @FXML
    public void onExportCsv() {
        if (currentReportData == null || currentReportData.rowCount() == 0) {
            AlertHelper.showInfo(null, "Export Notice", "Please generate a report with records before exporting to CSV.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save CSV Report");
        String safeName = currentReportData.title().replaceAll("[^a-zA-Z0-9_-]", "_") + ".csv";
        fileChooser.setInitialFileName(safeName);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Spreadsheet (*.csv)", "*.csv"));

        File file = fileChooser.showSaveDialog(tblReport.getScene().getWindow());
        if (file != null) {
            try {
                String csvText = reportService.exportToCsv(currentReportData);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(csvText.getBytes(StandardCharsets.UTF_8));
                }
                AlertHelper.showInfo(null, "Export Complete", "CSV report exported successfully to:\n" + file.getAbsolutePath());
            } catch (Exception ex) {
                log.error("Failed to export CSV: {}", ex.getMessage(), ex);
                AlertHelper.showStartupError(null, "CSV Export Failed", ex.getMessage(), ex);
            }
        }
    }

    @FXML
    public void onPrint() {
        if (tblReport == null || currentReportData == null) {
            AlertHelper.showInfo(null, "Print Notice", "Please generate a report before printing.");
            return;
        }

        try {
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) {
                AlertHelper.showInfo(null, "Printer Unavailable", "No default printer detected on this system. You can export to PDF instead.");
                return;
            }

            boolean proceed = job.showPrintDialog(tblReport.getScene().getWindow());
            if (proceed) {
                boolean success = job.printPage(tblReport);
                if (success) {
                    job.endJob();
                    AlertHelper.showInfo(null, "Print Sent", "Report document sent to printer successfully.");
                } else {
                    AlertHelper.showInfo(null, "Print Cancelled", "Print job was cancelled or encountered an error.");
                }
            }
        } catch (Exception ex) {
            log.error("Printing failed: {}", ex.getMessage(), ex);
            AlertHelper.showStartupError(null, "Print Error", "Could not print document: " + ex.getMessage(), ex);
        }
    }
}

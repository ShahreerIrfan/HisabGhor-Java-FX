package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.PurchaseOrder;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.PurchaseOrderService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqlitePurchaseOrderService;
import com.smartkhata.service.impl.SqliteSupplierService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import com.smartkhata.util.PurchaseOrderPdfExporter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PurchaseOrdersController {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrdersController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final PurchaseOrderService poService;
    private final SupplierService supplierService;
    private final InventoryService inventoryService;

    @FXML private TextField txtPoSearch;
    @FXML private ComboBox<String> cmbPoDateRange;
    @FXML private ComboBox<String> cmbPoStatus;
    @FXML private Button btnPoClearFilters;
    @FXML private Button btnPoRefresh;
    @FXML private Button btnNewPo;
    @FXML private HBox boxCustomDateRange;
    @FXML private DatePicker dpPoStart;
    @FXML private DatePicker dpPoEnd;
    @FXML private Label lblPoCount;
    @FXML private Label lblPoOpenCount;
    @FXML private Label lblPoTotalValue;
    @FXML private Label lblPoFilterStatus;

    @FXML private TableView<PurchaseOrderListItemDto> tblOrders;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoNumber;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoDate;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoSupplier;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoExpected;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoProgress;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoTotal;
    @FXML private TableColumn<PurchaseOrderListItemDto, String> colPoStatus;

    @FXML private VBox vboxPoNoSelection;
    @FXML private VBox vboxPoDetail;
    @FXML private Label lblDetailPoNumber;
    @FXML private Label lblDetailPoStatus;
    @FXML private Label lblDetailPoMeta;
    @FXML private Label lblDetailPoNotes;
    @FXML private Button btnConfirmPo;
    @FXML private Button btnCreateGrnFromPo;
    @FXML private Button btnEditPo;
    @FXML private Button btnExportPoPdf;
    @FXML private Button btnClosePo;
    @FXML private Button btnCancelPo;
    @FXML private Label lblDetailSubtotal;
    @FXML private Label lblDetailAdjustments;
    @FXML private Label lblDetailGrandTotal;

    @FXML private TableView<PurchaseOrderItemViewDto> tblPoItems;
    @FXML private TableColumn<PurchaseOrderItemViewDto, String> colLineProduct;
    @FXML private TableColumn<PurchaseOrderItemViewDto, String> colLineUnit;
    @FXML private TableColumn<PurchaseOrderItemViewDto, String> colLineOrd;
    @FXML private TableColumn<PurchaseOrderItemViewDto, String> colLineRem;
    @FXML private TableColumn<PurchaseOrderItemViewDto, String> colLineTp;
    @FXML private TableColumn<PurchaseOrderItemViewDto, String> colLineTotal;

    private final ObservableList<PurchaseOrderListItemDto> orderList = FXCollections.observableArrayList();
    private final ObservableList<PurchaseOrderItemViewDto> lineItemList = FXCollections.observableArrayList();
    private volatile PurchaseOrderDetailDto selectedDetail;
    private volatile CompletableFuture<Void> pendingLoad = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> pendingDetailLoad = CompletableFuture.completedFuture(null);
    private boolean suppressingFilterEvents = false;
    private Runnable onDataChangedCallback;
    private Consumer<PurchaseOrderDetailDto> onCreateGrnHandler;

    public PurchaseOrdersController() {
        this(new SqlitePurchaseOrderService(), new SqliteSupplierService(), new SqliteInventoryService());
    }

    public PurchaseOrdersController(
            PurchaseOrderService poService,
            SupplierService supplierService,
            InventoryService inventoryService
    ) {
        this.poService = poService;
        this.supplierService = supplierService;
        this.inventoryService = inventoryService;
    }

    public void setOnDataChangedCallback(Runnable callback) {
        this.onDataChangedCallback = callback;
    }

    public void setOnCreateGrnHandler(Consumer<PurchaseOrderDetailDto> handler) {
        this.onCreateGrnHandler = handler;
    }

    @FXML
    public void initialize() {
        setupTables();
        setupFilters();
        tblOrders.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) loadOrderDetail(n.id());
            else showNoSelection();
        });
        loadOrders();
    }

    private void setupTables() {
        tblOrders.setItems(orderList);
        tblPoItems.setItems(lineItemList);

        colPoNumber.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().poNumber()));
        colPoDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().poDate().format(DT_FMT)));
        colPoSupplier.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierName()));
        colPoExpected.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().expectedDeliveryDate() != null ? d.getValue().expectedDeliveryDate().toString() : "—"));
        colPoProgress.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().totalReceivedQty() + " / " + d.getValue().totalOrderedQty()));
        colPoTotal.setCellValueFactory(d -> new SimpleStringProperty(
                BengaliMoneyFormatter.format(d.getValue().grandTotal())));
        colPoTotal.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: 700;");
        colPoStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status().name()));
        colPoStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String statusStr, boolean empty) {
                super.updateItem(statusStr, empty);
                if (empty || statusStr == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Label badge = new Label(formatStatusLabel(statusStr));
                    badge.setStyle(buildStatusBadgeStyle(statusStr));
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        colLineProduct.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName()));
        colLineUnit.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().purchaseUnit() + " (x" + d.getValue().packSize() + ")"));
        colLineOrd.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().orderedQty() + " / " + d.getValue().receivedQty()));
        if (colLineRem != null) {
            colLineRem.setCellValueFactory(d -> new SimpleStringProperty(
                    String.valueOf(d.getValue().pendingQty())));
        }
        colLineTp.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().tradePrice())));
        colLineTotal.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().lineTotal())));

        showEmptyPlaceholder(false, null);
    }

    private void setupFilters() {
        suppressingFilterEvents = true;
        try {
            if (cmbPoDateRange != null) {
                cmbPoDateRange.setItems(FXCollections.observableArrayList(
                        "All Dates", "Today", "This Week", "This Month", "Last Month", "Custom Range"
                ));
                cmbPoDateRange.setValue("All Dates");
            }
            List<String> opts = new ArrayList<>();
            opts.add("All Statuses");
            for (PurchaseOrderStatus s : PurchaseOrderStatus.values()) {
                opts.add(s.name());
            }
            cmbPoStatus.setItems(FXCollections.observableArrayList(opts));
            cmbPoStatus.setValue("All Statuses");
            updateFilterUiState();
        } finally {
            suppressingFilterEvents = false;
        }
    }

    public void loadOrders() {
        loadOrdersAndSelect(null);
    }

    public void loadOrdersAndSelect(Long preferredOrderId) {
        if (!SessionContext.isAuthenticated()) return;
        long businessId = SessionContext.getRequiredBusinessId();
        String query = txtPoSearch != null ? txtPoSearch.getText() : null;
        String statusStr = cmbPoStatus != null ? cmbPoStatus.getValue() : null;
        PurchaseOrderStatus status = (statusStr == null || "All Statuses".equals(statusStr))
                ? null : PurchaseOrderStatus.valueOf(statusStr);
        LocalDate[] range = resolveDateRange();
        LocalDate startDate = range[0];
        LocalDate endDate = range[1];
        boolean filtered = hasActiveFilters(query, status, startDate, endDate);
        updateFilterUiState();

        pendingLoad.cancel(false);
        pendingLoad = CompletableFuture.supplyAsync(() -> {
            List<PurchaseOrderListItemDto> results = poService.searchPurchaseOrders(
                    businessId, query, null, status, startDate, endDate);
            boolean storeHasAnyPo = !results.isEmpty();
            if (results.isEmpty() && filtered) {
                storeHasAnyPo = !poService.searchPurchaseOrders(
                        businessId, null, null, null, null, null).isEmpty();
            }
            return new PoSearchOutcome(results, filtered && storeHasAnyPo);
        }).thenAccept(outcome -> Platform.runLater(() -> {
            List<PurchaseOrderListItemDto> results = outcome.rows();
            orderList.setAll(results);
            updatePoSummaryStrip(results);
            showEmptyPlaceholder(outcome.hiddenByFilters(), null);

            if (!orderList.isEmpty()) {
                Long desiredId = preferredOrderId;
                if (desiredId == null && tblOrders.getSelectionModel().getSelectedItem() != null) {
                    desiredId = tblOrders.getSelectionModel().getSelectedItem().id();
                }
                PurchaseOrderListItemDto toSelect = null;
                if (desiredId != null) {
                    long matchId = desiredId;
                    toSelect = orderList.stream().filter(p -> p.id() == matchId).findFirst().orElse(null);
                }
                if (toSelect == null) {
                    toSelect = orderList.get(0);
                }
                tblOrders.getSelectionModel().select(toSelect);
                tblOrders.scrollTo(toSelect);
                loadOrderDetail(toSelect.id());
            } else {
                showNoSelection();
            }
        })).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.error("Failed to load POs: {}", cause.getMessage(), cause);
            Platform.runLater(() -> {
                orderList.clear();
                updatePoSummaryStrip(List.of());
                showNoSelection();
                showEmptyPlaceholder(false, cause.getMessage() != null ? cause.getMessage() : "Unable to load purchase orders.");
            });
            return null;
        });
    }

    private record PoSearchOutcome(List<PurchaseOrderListItemDto> rows, boolean hiddenByFilters) {}

    private LocalDate[] resolveDateRange() {
        String preset = cmbPoDateRange != null ? cmbPoDateRange.getValue() : "All Dates";
        LocalDate now = LocalDate.now();
        if ("Today".equals(preset)) {
            return new LocalDate[]{now, now};
        } else if ("This Week".equals(preset)) {
            return new LocalDate[]{now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)), now};
        } else if ("This Month".equals(preset)) {
            return new LocalDate[]{now.withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth())};
        } else if ("Last Month".equals(preset)) {
            LocalDate prev = now.minusMonths(1);
            return new LocalDate[]{prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth())};
        } else if ("Custom Range".equals(preset)) {
            LocalDate s = dpPoStart != null ? dpPoStart.getValue() : null;
            LocalDate e = dpPoEnd != null ? dpPoEnd.getValue() : null;
            if (s != null && e != null && e.isBefore(s)) {
                return new LocalDate[]{e, s};
            }
            return new LocalDate[]{s, e};
        }
        return new LocalDate[]{null, null};
    }

    private boolean hasActiveFilters(String query, PurchaseOrderStatus status, LocalDate startDate, LocalDate endDate) {
        return (query != null && !query.trim().isEmpty())
                || status != null
                || startDate != null
                || endDate != null;
    }

    private void updateFilterUiState() {
        String preset = cmbPoDateRange != null ? cmbPoDateRange.getValue() : "All Dates";
        boolean isCustom = "Custom Range".equals(preset);
        if (boxCustomDateRange != null) {
            boxCustomDateRange.setVisible(isCustom);
            boxCustomDateRange.setManaged(isCustom);
        }
        String query = txtPoSearch != null ? txtPoSearch.getText() : null;
        String st = cmbPoStatus != null ? cmbPoStatus.getValue() : "All Statuses";
        boolean hasFilter = (query != null && !query.trim().isEmpty())
                || (st != null && !"All Statuses".equals(st))
                || (preset != null && !"All Dates".equals(preset));
        if (btnPoClearFilters != null) {
            btnPoClearFilters.setVisible(hasFilter);
            btnPoClearFilters.setManaged(hasFilter);
        }
        if (lblPoFilterStatus != null) {
            LocalDate[] r = resolveDateRange();
            if (r[0] != null && r[1] != null) {
                lblPoFilterStatus.setText("Date filter: " + r[0] + " to " + r[1]);
            } else if (r[0] != null) {
                lblPoFilterStatus.setText("Date filter: From " + r[0]);
            } else if (r[1] != null) {
                lblPoFilterStatus.setText("Date filter: Until " + r[1]);
            } else {
                lblPoFilterStatus.setText("Showing all dates");
            }
        }
    }

    private void updatePoSummaryStrip(List<PurchaseOrderListItemDto> items) {
        int total = items != null ? items.size() : 0;
        long openCount = 0;
        long valuePaisha = 0L;
        if (items != null) {
            for (PurchaseOrderListItemDto po : items) {
                if (po.status() == PurchaseOrderStatus.DRAFT
                        || po.status() == PurchaseOrderStatus.CONFIRMED
                        || po.status() == PurchaseOrderStatus.PARTIALLY_RECEIVED) {
                    openCount++;
                }
                if (po.status() != PurchaseOrderStatus.CANCELLED) {
                    valuePaisha += po.grandTotal().paisha();
                }
            }
        }
        if (lblPoCount != null) lblPoCount.setText(String.valueOf(total));
        if (lblPoOpenCount != null) lblPoOpenCount.setText(String.valueOf(openCount));
        if (lblPoTotalValue != null) lblPoTotalValue.setText(BengaliMoneyFormatter.format(Money.ofPaisha(valuePaisha)));
    }

    private void showEmptyPlaceholder(boolean hiddenByFilters, String errorMsg) {
        if (tblOrders == null) return;
        VBox box = new VBox(8.0);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-padding: 36px 20px;");
        if (errorMsg != null) {
            Label title = new Label("Unable to Load Purchase Orders");
            title.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
            Label sub = new Label(errorMsg);
            sub.setWrapText(true);
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748B;");
            Button retry = new Button("Retry");
            retry.getStyleClass().add("sk-dlg-btn-secondary");
            retry.setOnAction(e -> onRefresh());
            box.getChildren().addAll(title, sub, retry);
        } else if (hiddenByFilters) {
            Label title = new Label("No Matching Purchase Orders");
            title.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #475569;");
            Label sub = new Label("Try adjusting your search, date range, or status filter.");
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
            Button clearBtn = new Button("Clear Filters");
            clearBtn.getStyleClass().add("sk-dlg-btn-subtle-green");
            clearBtn.setOnAction(e -> onClearFilters());
            box.getChildren().addAll(title, sub, clearBtn);
        } else {
            Label title = new Label("No Purchase Orders Yet");
            title.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #64748B;");
            Label sub = new Label("Create your first Purchase Order to start ordering products from suppliers.");
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
            Button addBtn = new Button("+ New Purchase Order");
            addBtn.setStyle("-fx-background-color: #0E7B3A; -fx-text-fill: white; -fx-font-weight: 700; -fx-padding: 7 14; -fx-background-radius: 7; -fx-cursor: hand;");
            addBtn.setOnAction(e -> onNewPurchaseOrder());
            box.getChildren().addAll(title, sub, addBtn);
        }
        tblOrders.setPlaceholder(box);
    }

    public CompletableFuture<Void> pendingLoadsFuture() {
        return CompletableFuture.allOf(pendingLoad, pendingDetailLoad);
    }

    private void loadOrderDetail(long poId) {
        pendingDetailLoad.cancel(false);
        pendingDetailLoad = CompletableFuture.supplyAsync(() -> poService.getPurchaseOrderDetail(poId))
                .thenAccept(opt -> Platform.runLater(() -> opt.ifPresentOrElse(detail -> {
                    selectedDetail = detail;
                    vboxPoNoSelection.setVisible(false);
                    vboxPoNoSelection.setManaged(false);
                    vboxPoDetail.setVisible(true);
                    vboxPoDetail.setManaged(true);

                    PurchaseOrder po = detail.order();
                    lblDetailPoNumber.setText(po.poNumber());
                    if (lblDetailPoStatus != null) {
                        lblDetailPoStatus.setText(formatStatusLabel(po.status().name()));
                        lblDetailPoStatus.setStyle(buildStatusBadgeStyle(po.status().name()));
                    }
                    String supName = detail.supplier() != null ? detail.supplier().name() : "Supplier #" + po.supplierId();
                    lblDetailPoMeta.setText(String.format("Supplier: %s | Date: %s | Expected: %s%s",
                            supName, po.poDate().format(DT_FMT),
                            po.expectedDeliveryDate() != null ? po.expectedDeliveryDate().toString() : "—",
                            po.supplierReference() != null ? " | Ref: " + po.supplierReference() : ""));

                    if (po.notes() != null && !po.notes().isBlank()) {
                        lblDetailPoNotes.setText("Notes: " + po.notes());
                        lblDetailPoNotes.setVisible(true);
                        lblDetailPoNotes.setManaged(true);
                    } else {
                        lblDetailPoNotes.setVisible(false);
                        lblDetailPoNotes.setManaged(false);
                    }

                    lblDetailSubtotal.setText(BengaliMoneyFormatter.format(po.subtotal()));
                    Money adj = po.tax().add(po.additionalCharges()).subtract(po.discount());
                    lblDetailAdjustments.setText(BengaliMoneyFormatter.format(adj));
                    lblDetailGrandTotal.setText(BengaliMoneyFormatter.format(po.grandTotal()));

                    btnConfirmPo.setDisable(po.status() != PurchaseOrderStatus.DRAFT);
                    if (btnCreateGrnFromPo != null) {
                        btnCreateGrnFromPo.setDisable(!po.status().canReceiveGoods());
                    }
                    btnEditPo.setDisable(!po.status().canEdit());
                    btnClosePo.setDisable(!po.status().canClose());
                    btnCancelPo.setDisable(!po.status().canCancel());

                    lineItemList.setAll(detail.items());
                }, this::showNoSelection)));
    }

    private void showNoSelection() {
        selectedDetail = null;
        vboxPoDetail.setVisible(false);
        vboxPoDetail.setManaged(false);
        vboxPoNoSelection.setVisible(true);
        vboxPoNoSelection.setManaged(true);
        lineItemList.clear();
    }

    @FXML private void onSearch() { loadOrders(); }

    @FXML
    private void onFilterChanged() {
        if (!suppressingFilterEvents) {
            loadOrders();
        }
    }

    @FXML
    private void onDatePresetChanged() {
        if (!suppressingFilterEvents) {
            updateFilterUiState();
            loadOrders();
        }
    }

    @FXML
    private void onCustomDateChanged() {
        if (!suppressingFilterEvents) {
            loadOrders();
        }
    }

    @FXML
    private void onClearFilters() {
        suppressingFilterEvents = true;
        try {
            if (txtPoSearch != null) txtPoSearch.clear();
            if (cmbPoStatus != null) cmbPoStatus.setValue("All Statuses");
            if (cmbPoDateRange != null) cmbPoDateRange.setValue("All Dates");
            if (dpPoStart != null) dpPoStart.setValue(null);
            if (dpPoEnd != null) dpPoEnd.setValue(null);
            updateFilterUiState();
        } finally {
            suppressingFilterEvents = false;
        }
        loadOrders();
    }

    @FXML public void onRefresh() { loadOrders(); }

    @FXML
    private void onNewPurchaseOrder() {
        showPoDialog(null);
    }

    @FXML
    private void onEditDraftPo() {
        if (selectedDetail != null && selectedDetail.order().status().canEdit()) {
            showPoDialog(selectedDetail);
        }
    }

    @FXML
    private void onCreateGrnFromPo() {
        if (selectedDetail != null && selectedDetail.order().status().canReceiveGoods() && onCreateGrnHandler != null) {
            onCreateGrnHandler.accept(selectedDetail);
        }
    }

    @FXML
    private void onConfirmPo() {
        if (selectedDetail == null) return;
        Window owner = tblOrders != null && tblOrders.getScene() != null ? tblOrders.getScene().getWindow() : null;
        long poId = selectedDetail.order().id();
        try {
            poService.confirmPurchaseOrder(poId);
            resetFiltersIfExcluding(PurchaseOrderStatus.CONFIRMED, selectedDetail.order().poDate().toLocalDate());
            loadOrdersAndSelect(poId);
            notifyDataChanged();
            AlertHelper.showSuccess(owner, "PO Confirmed",
                    "Purchase Order confirmed. Inventory will update only upon GRN receipt.");
        } catch (Exception e) {
            AlertHelper.showStartupError(owner, "Confirm Failed", e.getMessage(), e);
        }
    }

    @FXML
    private void onClosePo() {
        if (selectedDetail == null) return;
        Window owner = tblOrders != null && tblOrders.getScene() != null ? tblOrders.getScene().getWindow() : null;
        long poId = selectedDetail.order().id();
        AlertHelper.promptInput(
                owner,
                "Close Purchase Order",
                "Short-close unreceived balance for " + selectedDetail.order().poNumber() + "?",
                "Closing Reason",
                "Remaining quantity closed",
                "Enter reason for closing PO...",
                true,
                "Close Order"
        ).ifPresent(reason -> {
            try {
                poService.closePurchaseOrder(poId, reason);
                loadOrdersAndSelect(poId);
                notifyDataChanged();
            } catch (Exception e) {
                AlertHelper.showStartupError(owner, "Close Failed", e.getMessage(), e);
            }
        });
    }

    @FXML
    private void onCancelPo() {
        if (selectedDetail == null) return;
        Window owner = tblOrders != null && tblOrders.getScene() != null ? tblOrders.getScene().getWindow() : null;
        long poId = selectedDetail.order().id();
        AlertHelper.promptInput(
                owner,
                "Cancel Purchase Order",
                "Cancel " + selectedDetail.order().poNumber() + " cleanly?",
                "Cancellation Reason",
                "Cancelled by owner",
                "Enter cancellation reason...",
                true,
                "Cancel PO"
        ).ifPresent(reason -> {
            try {
                poService.cancelPurchaseOrder(poId, reason);
                loadOrdersAndSelect(poId);
                notifyDataChanged();
            } catch (Exception e) {
                AlertHelper.showStartupError(owner, "Cancel Failed", e.getMessage(), e);
            }
        });
    }

    @FXML
    private void onExportPoPdf() {
        if (selectedDetail == null) return;
        Window owner = tblOrders != null && tblOrders.getScene() != null ? tblOrders.getScene().getWindow() : null;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Purchase Order PDF");
        fc.setInitialFileName(selectedDetail.order().poNumber() + ".pdf");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        File file = fc.showSaveDialog(owner);
        if (file == null) return;
        try {
            Business biz = SessionContext.getCurrentBusiness();
            byte[] pdf = PurchaseOrderPdfExporter.exportToPdf(biz, selectedDetail);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(pdf);
            }
            AlertHelper.showInfo(owner, "PDF Exported", "Saved Purchase Order PDF to " + file.getName());
        } catch (Exception e) {
            AlertHelper.showStartupError(owner, "PDF Export Failed", e.getMessage(), e);
        }
    }

    private record DraftPoLine(long productId, String productName, int qty, String unit, int packSize,
                               Money tp, Money discount, Money tax, Money lineTotal, String notes) {}

    private void showPoDialog(PurchaseOrderDetailDto existingDetail) {
        long businessId = SessionContext.getRequiredBusinessId();
        boolean isEdit = existingDetail != null;
        Window owner = tblOrders != null && tblOrders.getScene() != null ? tblOrders.getScene().getWindow() : null;

        List<SupplierListItemDto> sups = supplierService.searchSuppliers(businessId, null, false);
        List<ProductListItemDto> allProducts = inventoryService.searchProducts(businessId, null, null, false);
        if (sups.isEmpty()) {
            AlertHelper.showWarning(owner, "No Active Suppliers",
                    "Please add at least one active supplier before creating a Purchase Order.");
            return;
        }
        if (allProducts.isEmpty()) {
            AlertHelper.showWarning(owner, "No Active Products",
                    "Please add at least one active product in Inventory before creating a Purchase Order.");
            return;
        }

        ComboBox<SupplierListItemDto> cmbSup = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(sups)));
        cmbSup.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SupplierListItemDto s) { return s == null ? "" : s.name(); }
            @Override public SupplierListItemDto fromString(String s) { return null; }
        });
        if (isEdit) {
            sups.stream().filter(s -> s.id() == existingDetail.order().supplierId()).findFirst().ifPresent(cmbSup::setValue);
        } else {
            cmbSup.getSelectionModel().selectFirst();
        }

        DatePicker dpExpected = DialogComponents.styleDatePicker(
                new DatePicker(isEdit ? existingDetail.order().expectedDeliveryDate() : LocalDate.now().plusDays(2))
        );
        TextField txtRef = new TextField(isEdit && existingDetail.order().supplierReference() != null ? existingDetail.order().supplierReference() : "");
        txtRef.setPromptText("Supplier quote / ref#");

        GridPane headerGrid = DialogComponents.twoColGrid(14.0, 10.0);
        headerGrid.getColumnConstraints().clear();
        for (int i = 0; i < 3; i++) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(i == 0 ? 44.0 : 28.0);
            c.setHgrow(Priority.ALWAYS);
            headerGrid.getColumnConstraints().add(c);
        }
        headerGrid.add(DialogComponents.formField("Supplier", true, null, cmbSup), 0, 0);
        headerGrid.add(DialogComponents.formField("Expected Delivery Date", false, null, dpExpected), 1, 0);
        headerGrid.add(DialogComponents.formField("Supplier Ref#", false, null,
                DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, txtRef)), 2, 0);

        VBox headerSection = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_TRUCK, "Purchase Order Header",
                        "Select vendor and expected delivery date (zero stock impact until GRN)."),
                headerGrid
        );

        TextField txtProdFilter = new TextField();
        txtProdFilter.setPromptText("Search product by name, SKU, or barcode...");

        ComboBox<ProductListItemDto> cmbProd = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(allProducts)));
        cmbProd.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ProductListItemDto p) {
                return p == null ? "" : p.name() + (p.sku() != null ? " [" + p.sku() + "]" : "");
            }
            @Override public ProductListItemDto fromString(String s) { return null; }
        });

        txtProdFilter.textProperty().addListener((o, a, b) -> {
            String q = b != null ? b.trim().toLowerCase() : "";
            List<ProductListItemDto> filtered = allProducts.stream().filter(p ->
                    q.isEmpty() || p.name().toLowerCase().contains(q)
                            || (p.sku() != null && p.sku().toLowerCase().contains(q))
                            || (p.barcode() != null && p.barcode().toLowerCase().contains(q))
            ).toList();
            cmbProd.setItems(FXCollections.observableArrayList(filtered));
            if (!filtered.isEmpty()) cmbProd.getSelectionModel().selectFirst();
        });

        TextField txtQty = new TextField("1");
        TextField txtUnit = new TextField("pcs");
        TextField txtPack = new TextField("1");
        TextField txtTp = new TextField();
        TextField txtLineDisc = new TextField("0");
        TextField txtLineTax = new TextField("0");

        Runnable autoSuggestSupplierTp = () -> {
            SupplierListItemDto sup = cmbSup.getValue();
            ProductListItemDto prod = cmbProd.getValue();
            if (sup != null && prod != null) {
                try {
                    ProductSupplierViewDto resolved = poService.resolveProductPricingForSupplier(prod.id(), sup.id());
                    txtTp.setText(resolved.tradePrice().toBdt().toPlainString());
                    txtUnit.setText(resolved.purchaseUnit());
                    txtPack.setText(String.valueOf(resolved.packSize()));
                    txtQty.setText(String.valueOf(Math.max(1, resolved.minOrderQty())));
                } catch (Exception ignored) {
                    txtTp.setText(prod.tradePrice().toBdt().toPlainString());
                    txtUnit.setText(prod.purchaseUnit());
                    txtPack.setText(String.valueOf(Math.max(1, prod.packSize())));
                }
            }
        };
        cmbProd.setOnAction(e -> autoSuggestSupplierTp.run());
        cmbSup.setOnAction(e -> autoSuggestSupplierTp.run());
        cmbProd.getSelectionModel().selectFirst();
        autoSuggestSupplierTp.run();

        ObservableList<DraftPoLine> draftLines = FXCollections.observableArrayList();
        if (isEdit) {
            for (PurchaseOrderItemViewDto iv : existingDetail.items()) {
                draftLines.add(new DraftPoLine(iv.productId(), iv.productName(), iv.orderedQty(),
                        iv.purchaseUnit(), iv.packSize(), iv.tradePrice(), iv.discount(), iv.tax(), iv.lineTotal(), iv.notes()));
            }
        }

        Button btnAddLine = new Button("+ Add Item to Order");
        btnAddLine.getStyleClass().add("sk-dlg-btn-primary");
        btnAddLine.setPrefHeight(40.0);
        btnAddLine.setMinWidth(165.0);

        GridPane pickGrid = DialogComponents.responsiveTwoColGrid(
                520.0,
                DialogComponents.formField("Quick Filter Product", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_SEARCH, txtProdFilter)),
                DialogComponents.formField("Product", true, null, cmbProd)
        );

        GridPane lineInputs = new GridPane();
        lineInputs.setHgap(10.0);
        lineInputs.setVgap(8.0);
        double[] widths = {11.0, 12.0, 10.0, 21.0, 13.0, 12.0, 21.0};
        for (double w : widths) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(w);
            c.setHgrow(Priority.ALWAYS);
            lineInputs.getColumnConstraints().add(c);
        }
        lineInputs.add(DialogComponents.formField("Qty", true, null, DialogComponents.textFieldWrap(null, txtQty)), 0, 0);
        lineInputs.add(DialogComponents.formField("Unit", false, null, DialogComponents.textFieldWrap(null, txtUnit)), 1, 0);
        lineInputs.add(DialogComponents.formField("Pack", false, null, DialogComponents.textFieldWrap(null, txtPack)), 2, 0);
        lineInputs.add(DialogComponents.formField("Trade Price", true, null, DialogComponents.currencyFieldWrap(txtTp)), 3, 0);
        lineInputs.add(DialogComponents.formField("Line Disc", false, null, DialogComponents.textFieldWrap(null, txtLineDisc)), 4, 0);
        lineInputs.add(DialogComponents.formField("Tax", false, null, DialogComponents.textFieldWrap(null, txtLineTax)), 5, 0);
        VBox addBtnWrap = DialogComponents.formField("Add to Table", false, null, btnAddLine);
        lineInputs.add(addBtnWrap, 6, 0);

        VBox addLineSection = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "Add Order Line Item",
                        "Choose a product, review Trade Price & Qty, then click '+ Add Item to Order' before saving."),
                pickGrid,
                lineInputs
        );

        TableView<DraftPoLine> tblDraft = DialogComponents.styleTable(new TableView<>(draftLines));
        tblDraft.setPrefHeight(190);
        TableColumn<DraftPoLine, String> cP = new TableColumn<>("Product");
        cP.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().productName()));
        TableColumn<DraftPoLine, String> cU = new TableColumn<>("Unit / Pack");
        cU.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().unit() + " x" + c.getValue().packSize()));
        TableColumn<DraftPoLine, String> cQ = DialogComponents.rightAlignedColumn("Qty", l -> String.valueOf(l.qty()));
        TableColumn<DraftPoLine, String> cT = DialogComponents.rightAlignedColumn("TP", l -> BengaliMoneyFormatter.format(l.tp()));
        TableColumn<DraftPoLine, String> cL = DialogComponents.rightAlignedColumn("Line Total", l -> BengaliMoneyFormatter.format(l.lineTotal()));
        TableColumn<DraftPoLine, Void> cAct = new TableColumn<>("Action");
        cAct.setMaxWidth(95);
        cAct.setCellFactory(col -> new TableCell<>() {
            private final Button btnRem = new Button("Remove");
            {
                btnRem.getStyleClass().add("sk-prod-row-delete-btn");
                btnRem.setOnAction(ev -> {
                    int i = getIndex();
                    if (i >= 0 && i < draftLines.size()) {
                        draftLines.remove(i);
                    }
                });
            }
            @Override
            protected void updateItem(Void unused, boolean empty) {
                super.updateItem(unused, empty);
                setGraphic(empty ? null : btnRem);
            }
        });
        tblDraft.getColumns().addAll(cP, cU, cQ, cT, cL, cAct);
        tblDraft.setPlaceholder(DialogComponents.emptyState(
                DialogComponents.SVG_CLIPBOARD, "No items added yet",
                "Select a product above and click '+ Add Item to Order' to stage lines for this Purchase Order."
        ));

        TextField txtHdrDisc = new TextField(isEdit ? existingDetail.order().discount().toBdt().toPlainString() : "0");
        TextField txtHdrTax = new TextField(isEdit ? existingDetail.order().tax().toBdt().toPlainString() : "0");
        TextField txtCharges = new TextField(isEdit ? existingDetail.order().additionalCharges().toBdt().toPlainString() : "0");
        TextField txtNotes = new TextField(isEdit && existingDetail.order().notes() != null ? existingDetail.order().notes() : "");
        txtNotes.setPromptText("Optional delivery instructions or supplier notes");

        Label lblGrandPreview = new Label("Items: 0  |  Subtotal: BDT 0  |  Grand Total: BDT 0");
        lblGrandPreview.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #075C32;");

        ToggleGroup statusGroup = new ToggleGroup();
        RadioButton rbConfirm = new RadioButton("Confirm PO Immediately (Ready for GRN)");
        rbConfirm.setToggleGroup(statusGroup);
        rbConfirm.setSelected(!isEdit || existingDetail.order().status() != PurchaseOrderStatus.DRAFT);
        rbConfirm.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 600; -fx-text-fill: #008A32;");
        RadioButton rbDraft = new RadioButton("Save as Draft");
        rbDraft.setToggleGroup(statusGroup);
        if (isEdit && existingDetail.order().status() == PurchaseOrderStatus.DRAFT) {
            rbDraft.setSelected(true);
        }
        rbDraft.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 600; -fx-text-fill: #68778A;");

        Runnable recalcSummary = () -> {
            long sub = draftLines.stream().mapToLong(l -> l.lineTotal().paisha()).sum();
            Money hd = parseMoneyQuiet(txtHdrDisc.getText());
            Money ht = parseMoneyQuiet(txtHdrTax.getText());
            Money ch = parseMoneyQuiet(txtCharges.getText());
            Money grand = Money.ofPaisha(sub).subtract(hd).add(ht).add(ch);
            lblGrandPreview.setText("Items: " + draftLines.size()
                    + "   •   Subtotal: " + BengaliMoneyFormatter.format(Money.ofPaisha(sub))
                    + "   •   Grand Total: " + BengaliMoneyFormatter.format(grand));
        };
        draftLines.addListener((javafx.collections.ListChangeListener<DraftPoLine>) c -> recalcSummary.run());
        txtHdrDisc.textProperty().addListener((o, a, b) -> recalcSummary.run());
        txtHdrTax.textProperty().addListener((o, a, b) -> recalcSummary.run());
        txtCharges.textProperty().addListener((o, a, b) -> recalcSummary.run());
        recalcSummary.run();

        GridPane chargesGrid = new GridPane();
        chargesGrid.setHgap(12.0);
        chargesGrid.setVgap(10.0);
        for (int i = 0; i < 4; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(i == 3 ? 40.0 : 20.0);
            cc.setHgrow(Priority.ALWAYS);
            chargesGrid.getColumnConstraints().add(cc);
        }
        chargesGrid.add(DialogComponents.formField("Order Discount", false, null, DialogComponents.currencyFieldWrap(txtHdrDisc)), 0, 0);
        chargesGrid.add(DialogComponents.formField("Order Tax", false, null, DialogComponents.currencyFieldWrap(txtHdrTax)), 1, 0);
        chargesGrid.add(DialogComponents.formField("Additional Charges", false, null, DialogComponents.currencyFieldWrap(txtCharges)), 2, 0);
        chargesGrid.add(DialogComponents.formField("Notes", false, null, DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes)), 3, 0);

        HBox summaryStrip = new HBox(16.0, rbConfirm, rbDraft, new Region(), lblGrandPreview);
        HBox.setHgrow(summaryStrip.getChildren().get(2), Priority.ALWAYS);
        summaryStrip.setAlignment(Pos.CENTER_LEFT);
        summaryStrip.getStyleClass().add("sk-dlg-section-tint");

        VBox tableSection = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CLIPBOARD, "Order Line Items & Charges",
                        "Review line quantities, trade prices, discounts, and order total."),
                tblDraft,
                chargesGrid,
                summaryStrip
        );

        VBox body = new VBox(14.0, headerSection, addLineSection, tableSection);

        btnAddLine.setOnAction(ev -> {
            Optional<SmartKhataDialog.DialogHandle> handle = SmartKhataDialog.findHandle(body);
            handle.ifPresent(SmartKhataDialog.DialogHandle::clearError);
            ProductListItemDto prod = cmbProd.getValue();
            if (prod == null) {
                handle.ifPresent(h -> h.showError("Select a product to add.", cmbProd));
                return;
            }
            try {
                int qty = DialogComponents.parseIntRange(txtQty.getText(), 0, 1, "Order Qty");
                int pk = DialogComponents.parseIntRange(txtPack.getText(), 1, 1, "Pack Size");
                Money tp = DialogComponents.parseNonNegativeMoney(txtTp.getText(), "Trade Price");
                Money disc = DialogComponents.parseNonNegativeMoney(txtLineDisc.getText(), "Line Discount");
                Money tax = DialogComponents.parseNonNegativeMoney(txtLineTax.getText(), "Line Tax");
                Money gross = tp.multiply(qty);
                if (disc.compareTo(gross) > 0) {
                    throw new IllegalArgumentException("Line discount cannot exceed line gross amount.");
                }
                Money lineTot = gross.subtract(disc).add(tax);
                draftLines.removeIf(l -> l.productId() == prod.id());
                draftLines.add(new DraftPoLine(prod.id(), prod.name(), qty,
                        txtUnit.getText() != null && !txtUnit.getText().isBlank() ? txtUnit.getText().trim() : "pcs",
                        pk, tp, disc, tax, lineTot, null));
            } catch (IllegalArgumentException ex) {
                handle.ifPresent(h -> h.showError(ex.getMessage(), txtQty));
            }
        });

        AtomicBoolean submitStarted = new AtomicBoolean(false);
        long actingUser = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
        Long existingOrderId = isEdit ? existingDetail.order().id() : null;

        Optional<PurchaseOrder> result = SmartKhataDialog.<PurchaseOrder>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title(isEdit ? "Edit Draft Purchase Order" : "New Purchase Order (নতুন ক্রয় আদেশ)")
                .subtitle("Order products from suppliers using independent Trade Prices (no stock change until GRN).")
                .icon(DialogComponents.SVG_CLIPBOARD, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(cmbSup)
                .primaryAction(isEdit ? "Save Purchase Order" : "Save / Confirm PO", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .asyncSubmit(true, "Saving PO...")
                .onSubmit(() -> {
                    SupplierListItemDto sup = cmbSup.getValue();
                    if (sup == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Please select a supplier.", cmbSup);
                    }
                    if (draftLines.isEmpty()) {
                        ProductListItemDto pendingProd = cmbProd.getValue();
                        String msg = pendingProd != null
                                ? "Click '+ Add Item to Order' first to add \"" + pendingProd.name() + "\" to the order table before saving."
                                : "Add at least one product line item.";
                        throw new SmartKhataDialog.ValidationFocusException(msg, btnAddLine);
                    }
                    Money hdrDisc = DialogComponents.parseNonNegativeMoney(txtHdrDisc.getText(), "Order Discount");
                    Money hdrTax = DialogComponents.parseNonNegativeMoney(txtHdrTax.getText(), "Order Tax");
                    Money charges = DialogComponents.parseNonNegativeMoney(txtCharges.getText(), "Additional Charges");
                    List<PurchaseOrderItemCreateDto> reqItems = draftLines.stream()
                            .map(l -> new PurchaseOrderItemCreateDto(l.productId(), l.qty(), l.unit(), l.packSize(),
                                    l.tp(), l.discount(), l.tax(), l.notes()))
                            .toList();
                    if (!submitStarted.compareAndSet(false, true)) {
                        throw new IllegalStateException("Purchase Order save already in progress.");
                    }
                    try {
                        return poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                                existingOrderId,
                                businessId,
                                sup.id(),
                                dpExpected.getValue(),
                                hdrDisc,
                                hdrTax,
                                charges,
                                txtRef.getText(),
                                txtNotes.getText(),
                                rbConfirm.isSelected(),
                                actingUser,
                                reqItems
                        ));
                    } catch (RuntimeException ex) {
                        submitStarted.set(false);
                        throw ex;
                    }
                })
                .showAndWait();

        result.ifPresent(saved -> {
            boolean filtersCleared = resetFiltersIfExcluding(saved.status(), saved.poDate().toLocalDate());
            loadOrdersAndSelect(saved.id());
            notifyDataChanged();
            String msg = "Purchase Order " + saved.poNumber() + " saved as " + saved.status().name() + "."
                    + (filtersCleared ? "\n(Active search/filters were cleared so the new PO is visible.)" : "");
            AlertHelper.showSuccess(owner, "PO Saved", msg);
        });
    }

    private boolean resetFiltersIfExcluding(PurchaseOrderStatus status, LocalDate poDate) {
        String q = txtPoSearch != null ? txtPoSearch.getText() : null;
        String statusStr = cmbPoStatus != null ? cmbPoStatus.getValue() : "All Statuses";
        LocalDate[] range = resolveDateRange();

        boolean excludedBySearch = q != null && !q.trim().isEmpty();
        boolean excludedByStatus = statusStr != null && !"All Statuses".equals(statusStr) && !statusStr.equals(status.name());
        boolean excludedByDate = (range[0] != null && poDate.isBefore(range[0]))
                || (range[1] != null && poDate.isAfter(range[1]));

        if (excludedBySearch || excludedByStatus || excludedByDate) {
            suppressingFilterEvents = true;
            try {
                if (txtPoSearch != null) txtPoSearch.clear();
                if (cmbPoStatus != null) cmbPoStatus.setValue("All Statuses");
                if (cmbPoDateRange != null) cmbPoDateRange.setValue("All Dates");
                if (dpPoStart != null) dpPoStart.setValue(null);
                if (dpPoEnd != null) dpPoEnd.setValue(null);
                updateFilterUiState();
            } finally {
                suppressingFilterEvents = false;
            }
            return true;
        }
        return false;
    }

    private void notifyDataChanged() {
        if (onDataChangedCallback != null) {
            onDataChangedCallback.run();
        }
    }

    private static String formatStatusLabel(String status) {
        return switch (status) {
            case "PARTIALLY_RECEIVED" -> "PARTIALLY RECEIVED";
            case "FULLY_RECEIVED" -> "FULLY RECEIVED";
            default -> status;
        };
    }

    private static String buildStatusBadgeStyle(String status) {
        return switch (status) {
            case "DRAFT" -> "-fx-background-color: #F1F5F9; -fx-text-fill: #475569; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
            case "CONFIRMED" -> "-fx-background-color: #E0F2FE; -fx-text-fill: #0369A1; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
            case "PARTIALLY_RECEIVED" -> "-fx-background-color: #FEF3C7; -fx-text-fill: #92400E; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
            case "FULLY_RECEIVED" -> "-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
            case "CANCELLED" -> "-fx-background-color: #FEE2E2; -fx-text-fill: #B91C1C; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
            case "CLOSED" -> "-fx-background-color: #E2E8F0; -fx-text-fill: #334155; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
            default -> "-fx-background-color: #F1F5F9; -fx-text-fill: #475569; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 6;";
        };
    }

    private static Money parseMoneyQuiet(String s) {
        if (s == null || s.isBlank()) return Money.ZERO;
        try { return parseMoneyStrict(s); } catch (Exception e) { return Money.ZERO; }
    }

    private static Money parseMoneyStrict(String s) {
        if (s == null || s.isBlank()) return Money.ZERO;
        return Money.ofBdt(new BigDecimal(s.trim().replaceAll("[, ]", "")));
    }
}

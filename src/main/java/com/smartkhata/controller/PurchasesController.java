package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Purchase;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.PurchaseService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqlitePurchaseService;
import com.smartkhata.service.impl.SqliteSupplierService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.ViewRouter;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
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
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for Purchases & Inward Stock, Supplier Bills, and Payable Dues.
 */
public class PurchasesController {

    private static final Logger log = LoggerFactory.getLogger(PurchasesController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final PurchaseService purchaseService = new SqlitePurchaseService();
    private final SupplierService supplierService = new SqliteSupplierService();
    private final InventoryService inventoryService = new SqliteInventoryService();

    // Top Summary Pills
    @FXML private Label lblTotalBills;
    @FXML private Label lblTotalPayables;
    @FXML private Label lblTotalPurchases;
    @FXML private Button btnNewPurchase;

    @FXML private TabPane procurementTabPane;
    @FXML private PurchaseOrdersController poTabController;
    @FXML private GrnController grnTabController;
    @FXML private SupplierInvoicesController invTabController;
    @FXML private PurchaseReturnsController returnsTabController;

    // Search & Filter Toolbar
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbStatusFilter;
    @FXML private Button btnRefresh;

    // Main Purchases Table
    @FXML private TableView<PurchaseListItemDto> tblPurchases;
    @FXML private TableColumn<PurchaseListItemDto, String> colPurchaseNo;
    @FXML private TableColumn<PurchaseListItemDto, String> colDate;
    @FXML private TableColumn<PurchaseListItemDto, String> colSupplier;
    @FXML private TableColumn<PurchaseListItemDto, String> colAmount;
    @FXML private TableColumn<PurchaseListItemDto, String> colPaid;
    @FXML private TableColumn<PurchaseListItemDto, String> colDue;
    @FXML private TableColumn<PurchaseListItemDto, String> colStatus;

    // Right Side Detail Panel
    @FXML private VBox pnlDetailContainer;
    @FXML private VBox vboxNoSelection;
    @FXML private VBox vboxPurchaseDetails;
    @FXML private Label lblDetailPurchaseNo;
    @FXML private Label lblDetailSupplierInfo;
    @FXML private Label lblDetailNotes;
    @FXML private Button btnEditPurchaseNote;
    @FXML private Button btnPrintBill;
    @FXML private Button btnVoidPurchase;
    @FXML private Button btnDeleteVoidedPurchase;
    @FXML private Label lblDetailNet;
    @FXML private Label lblDetailPaid;
    @FXML private Label lblDetailDue;
    @FXML private Label lblVoidStatus;

    // Line Items Table
    @FXML private TableView<PurchaseItemDto> tblDetailItems;
    @FXML private TableColumn<PurchaseItemDto, String> colItemProduct;
    @FXML private TableColumn<PurchaseItemDto, String> colItemQty;
    @FXML private TableColumn<PurchaseItemDto, String> colItemCost;
    @FXML private TableColumn<PurchaseItemDto, String> colItemTotal;

    private final ObservableList<PurchaseListItemDto> purchaseList = FXCollections.observableArrayList();
    private final ObservableList<PurchaseItemDto> detailItemList = FXCollections.observableArrayList();
    private volatile Purchase selectedDetailPurchase = null;

    public record SupplierOption(Long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record ProductOption(Long id, String name, Money currentCost, int stock) {
        @Override
        public String toString() {
            return name + " (Stock: " + stock + " | Cost: " + currentCost.toString() + ")";
        }
    }

    @FXML
    public void initialize() {
        log.info("Initializing PurchasesController...");

        setupTableColumns();
        setupFilters();

        tblPurchases.setItems(purchaseList);
        tblDetailItems.setItems(detailItemList);

        tblPurchases.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadPurchaseDetails(newVal.id());
            } else {
                showNoSelection();
            }
        });

        setupTabSynchronization();
        loadPurchases();
    }

    private void setupTabSynchronization() {
        Runnable syncDashboard = () -> {
            DashboardController dc = ViewRouter.getDashboardController();
            if (dc != null) dc.refreshDashboard();
        };

        if (poTabController != null) {
            poTabController.setOnDataChangedCallback(() -> {
                if (grnTabController != null) grnTabController.loadGrns();
                syncDashboard.run();
            });
            poTabController.setOnCreateGrnHandler(detail -> {
                if (procurementTabPane != null && procurementTabPane.getTabs().size() > 1) {
                    procurementTabPane.getSelectionModel().select(1);
                }
                if (grnTabController != null) {
                    grnTabController.openGrnForPurchaseOrder(detail);
                }
            });
        }
        if (grnTabController != null) {
            grnTabController.setOnDataChangedCallback(() -> {
                if (poTabController != null) poTabController.loadOrders();
                if (invTabController != null) invTabController.loadInvoices();
                syncDashboard.run();
            });
        }
        if (invTabController != null) {
            invTabController.setOnDataChangedCallback(() -> {
                loadPurchases();
                syncDashboard.run();
            });
        }
        if (returnsTabController != null) {
            returnsTabController.setOnDataChangedCallback(syncDashboard);
        }

        if (procurementTabPane != null) {
            procurementTabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
                if (newIdx == null) return;
                switch (newIdx.intValue()) {
                    case 0 -> { if (poTabController != null) poTabController.loadOrders(); }
                    case 1 -> { if (grnTabController != null) grnTabController.loadGrns(); }
                    case 2 -> { if (invTabController != null) invTabController.loadInvoices(); }
                    case 3 -> { if (returnsTabController != null) returnsTabController.loadReturns(); }
                    case 4 -> loadPurchases();
                    default -> {}
                }
            });
        }
    }

    public void refreshAll() {
        loadPurchases();
        if (poTabController != null) poTabController.loadOrders();
        if (grnTabController != null) grnTabController.loadGrns();
        if (invTabController != null) invTabController.loadInvoices();
        if (returnsTabController != null) returnsTabController.loadReturns();
    }

    private void setupTableColumns() {
        colPurchaseNo.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().purchaseNumber()));
        colDate.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().purchaseDate().format(DATE_FMT)));
        colSupplier.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().supplierName()));
        colAmount.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().netAmount())));
        colPaid.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().paidAmount())));
        colDue.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().dueAmount())));

        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().paymentStatus()));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    PurchaseListItemDto item = getTableView().getItems().get(getIndex());
                    if (item.isReversed()) {
                        setText("VOIDED");
                        setStyle("-fx-text-fill: #991B1B; -fx-font-weight: bold; -fx-background-color: #FEE2E2; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else if ("PAID".equalsIgnoreCase(status)) {
                        setText("PAID");
                        setStyle("-fx-text-fill: #15803D; -fx-font-weight: bold; -fx-background-color: #DCFCE7; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else if ("PARTIAL".equalsIgnoreCase(status)) {
                        setText("PARTIAL");
                        setStyle("-fx-text-fill: #B45309; -fx-font-weight: bold; -fx-background-color: #FEF3C7; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else {
                        setText("DUE");
                        setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold; -fx-background-color: #FEE2E2; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    }
                }
            }
        });

        // Detail items table
        colItemProduct.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().productName()));
        colItemQty.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().quantity())));
        colItemCost.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().unitCost())));
        colItemTotal.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().lineTotal())));
    }

    private void setupFilters() {
        cmbStatusFilter.getItems().setAll("All Statuses", "PAID", "PARTIAL", "DUE");
        cmbStatusFilter.setValue("All Statuses");
    }

    public void loadPurchases() {
        if (!SessionContext.isAuthenticated()) {
            return;
        }
        long businessId = SessionContext.getRequiredBusinessId();
        String query = txtSearch.getText();
        String status = cmbStatusFilter.getValue();
        if ("All Statuses".equalsIgnoreCase(status)) {
            status = null;
        }

        final String statusParam = status;
        CompletableFuture.supplyAsync(() -> purchaseService.searchPurchases(businessId, query, null, statusParam, null, null))
                .thenAccept(results -> Platform.runLater(() -> {
                    purchaseList.setAll(results);
                    updateMetrics(results);
                    if (!purchaseList.isEmpty()) {
                        tblPurchases.getSelectionModel().selectFirst();
                    } else {
                        showNoSelection();
                    }
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load purchases: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> AlertHelper.showInfo(null, "Data Error", "Could not load purchase records: " + ex.getMessage()));
                    return null;
                });
    }

    private void updateMetrics(List<PurchaseListItemDto> items) {
        lblTotalBills.setText(String.valueOf(items.size()));

        Money totalPurchases = Money.ZERO;
        Money totalPayables = Money.ZERO;

        for (PurchaseListItemDto p : items) {
            if (!p.isReversed()) {
                totalPurchases = totalPurchases.add(p.netAmount());
                totalPayables = totalPayables.add(p.dueAmount());
            }
        }

        lblTotalPurchases.setText(BengaliMoneyFormatter.format(totalPurchases));
        lblTotalPayables.setText(BengaliMoneyFormatter.format(totalPayables));
    }

    private void loadPurchaseDetails(long purchaseId) {
        CompletableFuture.supplyAsync(() -> purchaseService.getPurchaseDetail(purchaseId))
                .thenAccept(opt -> Platform.runLater(() -> {
                    if (opt.isPresent()) {
                        showPurchaseDetails(opt.get());
                    } else {
                        showNoSelection();
                    }
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load purchase detail: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    private void showPurchaseDetails(PurchaseDetailDto detail) {
        vboxNoSelection.setVisible(false);
        vboxNoSelection.setManaged(false);

        vboxPurchaseDetails.setVisible(true);
        vboxPurchaseDetails.setManaged(true);

        Purchase p = detail.purchase();
        selectedDetailPurchase = p;
        lblDetailPurchaseNo.setText(p.purchaseNumber());
        lblDetailSupplierInfo.setText("Supplier: " + detail.supplierName() + " | Date: " + p.purchaseDate().format(DATE_FMT));
        if (lblDetailNotes != null) {
            boolean hasNote = p.notes() != null && !p.notes().isBlank();
            lblDetailNotes.setText(hasNote ? "Note: " + p.notes().trim() : "");
            lblDetailNotes.setVisible(hasNote);
            lblDetailNotes.setManaged(hasNote);
        }

        lblDetailNet.setText(BengaliMoneyFormatter.format(p.netAmount()));
        lblDetailPaid.setText(BengaliMoneyFormatter.format(p.paidAmount()));
        lblDetailDue.setText(BengaliMoneyFormatter.format(p.dueAmount()));

        detailItemList.setAll(detail.items());

        if (p.isReversed()) {
            lblVoidStatus.setText("VOIDED: " + (p.reversalReason() != null ? p.reversalReason() : "Reversed"));
            lblVoidStatus.setVisible(true);
            lblVoidStatus.setManaged(true);
            btnVoidPurchase.setDisable(true);
        } else {
            lblVoidStatus.setVisible(false);
            lblVoidStatus.setManaged(false);
            btnVoidPurchase.setDisable(false);
        }

        boolean canDeleteVoided = p.isReversed() && SessionContext.isOwner();
        if (btnDeleteVoidedPurchase != null) {
            btnDeleteVoidedPurchase.setVisible(canDeleteVoided);
            btnDeleteVoidedPurchase.setManaged(canDeleteVoided);
        }
    }

    private void showNoSelection() {
        selectedDetailPurchase = null;
        if (lblDetailNotes != null) {
            lblDetailNotes.setVisible(false);
            lblDetailNotes.setManaged(false);
        }
        if (btnDeleteVoidedPurchase != null) {
            btnDeleteVoidedPurchase.setVisible(false);
            btnDeleteVoidedPurchase.setManaged(false);
        }
        vboxPurchaseDetails.setVisible(false);
        vboxPurchaseDetails.setManaged(false);
        vboxNoSelection.setVisible(true);
        vboxNoSelection.setManaged(true);
    }

    @FXML
    public void onSearchKeyReleased() {
        loadPurchases();
    }

    @FXML
    public void onStatusFilterChanged() {
        loadPurchases();
    }

    @FXML
    public void onRefresh() {
        loadPurchases();
    }

    @FXML
    public void onEditPurchaseNote() {
        PurchaseListItemDto selected = tblPurchases.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        String currentNote = (selectedDetailPurchase != null && selectedDetailPurchase.id() != null
                && selectedDetailPurchase.id().equals(selected.id()) && selectedDetailPurchase.notes() != null)
                ? selectedDetailPurchase.notes() : "";

        Window owner = tblPurchases != null && tblPurchases.getScene() != null ? tblPurchases.getScene().getWindow() : null;
        AlertHelper.promptInput(
                owner,
                "Edit Purchase Note (ক্রয় নোট সম্পাদনা)",
                "Update challan / remarks note for " + selected.purchaseNumber(),
                "Purchase Note",
                currentNote,
                "Optional challan or supplier notes (leave blank to clear)",
                false,
                "Save Note"
        ).ifPresent(newNote -> {
            try {
                purchaseService.updatePurchaseNotes(selected.id(), newNote);
                loadPurchaseDetails(selected.id());
                AlertHelper.showInfo(owner, "Note Updated", "Purchase note updated for " + selected.purchaseNumber() + ".");
            } catch (Exception ex) {
                log.error("Failed to update purchase note: {}", ex.getMessage(), ex);
                AlertHelper.showWarning(owner, "Update Failed", ex.getMessage());
            }
        });
    }

    @FXML
    public void onDeleteVoidedPurchase() {
        PurchaseListItemDto selected = tblPurchases.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblPurchases != null && tblPurchases.getScene() != null ? tblPurchases.getScene().getWindow() : null;
        if (!SessionContext.isOwner()) {
            AlertHelper.showWarning(owner, "Permission Denied", "Only the Business Owner can delete voided purchase bills.");
            return;
        }
        if (!selected.isReversed()) {
            AlertHelper.showWarning(owner, "Active Bill", "Void this purchase bill first before deleting it.");
            return;
        }

        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Voided Purchase (OWNER)",
                "Permanently delete voided purchase bill " + selected.purchaseNumber() + "?",
                "This removes the voided bill, its line items, and its balanced reversal entries permanently.",
                "Delete Permanently"
        );
        if (confirmed) {
            try {
                purchaseService.deleteVoidedPurchase(selected.id());
                loadPurchases();
                DashboardController dc = ViewRouter.getDashboardController();
                if (dc != null) {
                    dc.refreshDashboard();
                }
                AlertHelper.showInfo(owner, "Voided Bill Deleted",
                        "Voided purchase " + selected.purchaseNumber() + " deleted permanently.");
            } catch (Exception ex) {
                log.error("Failed to delete voided purchase: {}", ex.getMessage(), ex);
                AlertHelper.showWarning(owner, "Delete Failed", ex.getMessage());
            }
        }
    }

    @FXML
    public void onNewPurchase() {
        long businessId = SessionContext.getRequiredBusinessId();
        Window owner = tblPurchases != null && tblPurchases.getScene() != null ? tblPurchases.getScene().getWindow() : null;

        ComboBox<SupplierOption> cmbSupplier = DialogComponents.styleCombo(new ComboBox<>());
        cmbSupplier.setPromptText("Select a supplier...");
        List<SupplierListItemDto> suppliers = supplierService.searchSuppliers(businessId, null, false);
        for (SupplierListItemDto s : suppliers) {
            cmbSupplier.getItems().add(new SupplierOption(s.id(), s.name()));
        }
        if (!cmbSupplier.getItems().isEmpty()) {
            cmbSupplier.setValue(cmbSupplier.getItems().get(0));
        }

        ComboBox<ProductOption> cmbProduct = DialogComponents.styleCombo(new ComboBox<>());
        cmbProduct.setPromptText("Choose product...");
        List<ProductListItemDto> products = inventoryService.searchProducts(businessId, null, null, false);
        for (ProductListItemDto prod : products) {
            int currentStock = inventoryService.getCurrentStock(prod.id());
            cmbProduct.getItems().add(new ProductOption(prod.id(), prod.name(), prod.costPrice(), currentStock));
        }

        Spinner<Integer> spnQty = new Spinner<>(1, 10000, 1);
        spnQty.setEditable(true);
        spnQty.setPrefHeight(40.0);
        spnQty.setMaxWidth(Double.MAX_VALUE);

        TextField txtCost = new TextField();
        txtCost.setPromptText("Unit Cost");

        cmbProduct.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.currentCost() != null) {
                txtCost.setText(newVal.currentCost().toBdt().toPlainString());
            }
        });
        if (!cmbProduct.getItems().isEmpty()) {
            cmbProduct.getSelectionModel().selectFirst();
        }

        Button btnAddItem = new Button("+ Add Item");
        btnAddItem.getStyleClass().add("sk-dlg-btn-subtle-green");
        btnAddItem.setPrefHeight(40.0);

        ObservableList<PurchaseItemDto> cartItems = FXCollections.observableArrayList();
        TableView<PurchaseItemDto> tblCart = DialogComponents.styleTable(new TableView<>(cartItems));
        tblCart.setPrefHeight(195);

        TableColumn<PurchaseItemDto, String> colCartProd = new TableColumn<>("Product");
        colCartProd.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName()));
        colCartProd.setPrefWidth(240);
        TableColumn<PurchaseItemDto, String> colCartQty = DialogComponents.rightAlignedColumn("Qty", d -> String.valueOf(d.quantity()));
        colCartQty.setPrefWidth(70);
        TableColumn<PurchaseItemDto, String> colCartCost = DialogComponents.rightAlignedColumn("Unit Cost", d -> BengaliMoneyFormatter.format(d.unitCost()));
        colCartCost.setPrefWidth(115);
        TableColumn<PurchaseItemDto, String> colCartTotal = DialogComponents.rightAlignedColumn("Line Total", d -> BengaliMoneyFormatter.format(d.lineTotal()));
        colCartTotal.setPrefWidth(125);
        TableColumn<PurchaseItemDto, Void> colCartAct = new TableColumn<>("Action");
        colCartAct.setMaxWidth(90);
        colCartAct.setCellFactory(c -> new TableCell<>() {
            private final Button btnRem = new Button("Remove");
            {
                btnRem.getStyleClass().add("sk-prod-row-delete-btn");
                btnRem.setOnAction(ev -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < cartItems.size()) {
                        cartItems.remove(idx);
                    }
                });
            }
            @Override
            protected void updateItem(Void unused, boolean empty) {
                super.updateItem(unused, empty);
                setGraphic(empty ? null : btnRem);
            }
        });
        tblCart.getColumns().addAll(colCartProd, colCartQty, colCartCost, colCartTotal, colCartAct);
        tblCart.setPlaceholder(DialogComponents.emptyState(
                DialogComponents.SVG_CUBE, "No items added yet",
                "Select a product above and click + Add Item to build this purchase bill."
        ));

        Label lblSubtotal = new Label("BDT 0.00");
        lblSubtotal.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #18283B;");
        TextField txtDiscount = new TextField("0");
        TextField txtPaid = new TextField("0");
        ComboBox<String> cmbPayMethod = DialogComponents.styleCombo(
                new ComboBox<>(FXCollections.observableArrayList("CASH", "BANK", "BKASH", "NAGAD")));
        cmbPayMethod.setValue("CASH");
        Label lblDue = new Label("BDT 0.00");
        lblDue.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #DC3545;");
        TextField txtNotes = new TextField();
        txtNotes.setPromptText("Optional supplier bill notes / batch / challan reference");

        Runnable recomputeTotals = () -> {
            Money subtotal = Money.ZERO;
            for (PurchaseItemDto it : cartItems) {
                subtotal = subtotal.add(it.lineTotal());
            }
            lblSubtotal.setText(BengaliMoneyFormatter.format(subtotal));
            Money discount = Money.ZERO;
            try { discount = DialogComponents.parseNonNegativeMoney(txtDiscount.getText(), "Discount"); } catch (Exception ignored) {}
            Money net = subtotal.subtract(discount);
            if (net.isNegative()) net = Money.ZERO;
            Money paid = Money.ZERO;
            try { paid = DialogComponents.parseNonNegativeMoney(txtPaid.getText(), "Paid"); } catch (Exception ignored) {}
            if (paid.compareTo(net) > 0) paid = net;
            Money due = net.subtract(paid);
            lblDue.setText(BengaliMoneyFormatter.format(due));
        };
        cartItems.addListener((javafx.collections.ListChangeListener<PurchaseItemDto>) c -> recomputeTotals.run());
        txtDiscount.textProperty().addListener((obs, o, n) -> recomputeTotals.run());
        txtPaid.textProperty().addListener((obs, o, n) -> recomputeTotals.run());

        VBox supplierCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_TRUCK, "Supplier & Reference",
                        "Select vendor receiving inward stock & payable posting."),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Supplier (সরবরাহকারী)", true, null, cmbSupplier),
                        DialogComponents.formField("Challan / Notes", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
                )
        );

        GridPane addItemGrid = new GridPane();
        addItemGrid.setHgap(10.0);
        double[] widths = {46.0, 16.0, 22.0, 16.0};
        for (double w : widths) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(w);
            c.setHgrow(Priority.ALWAYS);
            addItemGrid.getColumnConstraints().add(c);
        }
        addItemGrid.add(DialogComponents.formField("Product", true, null, cmbProduct), 0, 0);
        addItemGrid.add(DialogComponents.formField("Qty", true, null, spnQty), 1, 0);
        addItemGrid.add(DialogComponents.formField("Unit Cost", true, null, DialogComponents.currencyFieldWrap(txtCost)), 2, 0);
        addItemGrid.add(DialogComponents.formField(" ", false, null, btnAddItem), 3, 0);

        VBox itemsCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "Purchased Line Items",
                        "Add items below to increase stock and compute bill subtotal."),
                addItemGrid,
                tblCart
        );

        GridPane settlementGrid = new GridPane();
        settlementGrid.setHgap(12.0);
        settlementGrid.setVgap(10.0);
        double[] sw = {20.0, 20.0, 20.0, 20.0, 20.0};
        for (double w : sw) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(w);
            c.setHgrow(Priority.ALWAYS);
            settlementGrid.getColumnConstraints().add(c);
        }
        settlementGrid.add(DialogComponents.formField("Subtotal", false, null, lblSubtotal), 0, 0);
        settlementGrid.add(DialogComponents.formField("Discount (ছাড়)", false, null, DialogComponents.currencyFieldWrap(txtDiscount)), 1, 0);
        settlementGrid.add(DialogComponents.formField("Paid Now (পরিশোধ)", false, null, DialogComponents.currencyFieldWrap(txtPaid)), 2, 0);
        settlementGrid.add(DialogComponents.formField("Method", false, null, cmbPayMethod), 3, 0);
        settlementGrid.add(DialogComponents.formField("Payable Due (দেনা)", false, null, lblDue), 4, 0);

        VBox summaryCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Payment & Payable Summary",
                        "Any unpaid balance is posted directly to the Supplier Payable Ledger."),
                settlementGrid
        );

        VBox body = new VBox(14.0, supplierCard, itemsCard, summaryCard);

        btnAddItem.setOnAction(e -> {
            Optional<SmartKhataDialog.DialogHandle> handle = SmartKhataDialog.findHandle(body);
            handle.ifPresent(SmartKhataDialog.DialogHandle::clearError);
            ProductOption pOpt = cmbProduct.getValue();
            if (pOpt == null) {
                handle.ifPresent(h -> h.showError("Please select a product to purchase.", cmbProduct));
                return;
            }
            int qty = spnQty.getValue();
            if (qty <= 0) {
                handle.ifPresent(h -> h.showError("Quantity must be greater than zero.", spnQty));
                return;
            }
            Money cost;
            try {
                cost = DialogComponents.parseNonNegativeMoney(txtCost.getText(), "Unit Cost");
            } catch (IllegalArgumentException ex) {
                handle.ifPresent(h -> h.showError(ex.getMessage(), txtCost));
                return;
            }
            cartItems.add(PurchaseItemDto.of(pOpt.id(), pOpt.name(), qty, cost));
        });

        Optional<Purchase> result = SmartKhataDialog.<Purchase>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title("New Purchase Bill (ক্রয় চালান)")
                .subtitle("Record supplier purchase invoice, receive inventory, and post payable ledger.")
                .icon(DialogComponents.SVG_RECEIPT, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(cmbSupplier)
                .primaryAction("Confirm Purchase", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    if (cmbSupplier.getValue() == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Please select a registered supplier.", cmbSupplier);
                    }
                    if (cartItems.isEmpty()) {
                        throw new SmartKhataDialog.ValidationFocusException("Please add at least one line item.", cmbProduct);
                    }
                    Money discount = DialogComponents.parseNonNegativeMoney(txtDiscount.getText(), "Discount");
                    Money paid = DialogComponents.parseNonNegativeMoney(txtPaid.getText(), "Paid Amount");
                    long actingUserId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                    return purchaseService.createPurchase(new PurchaseCreateDto(
                            businessId,
                            cmbSupplier.getValue().id(),
                            new ArrayList<>(cartItems),
                            discount,
                            paid,
                            cmbPayMethod.getValue(),
                            txtNotes.getText(),
                            UUID.randomUUID().toString(),
                            actingUserId
                    ));
                })
                .showAndWait();

        result.ifPresent(p -> {
            AlertHelper.showInfo(owner, "Purchase Recorded",
                    "Purchase bill " + p.purchaseNumber() + " posted successfully!\nStock increased and supplier dues recorded.");
            loadPurchases();
        });
    }

    @FXML
    public void onPrintBill() {
        PurchaseListItemDto selected = tblPurchases.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblPurchases != null && tblPurchases.getScene() != null ? tblPurchases.getScene().getWindow() : null;

        Optional<PurchaseDetailDto> opt = purchaseService.getPurchaseDetail(selected.id());
        if (opt.isEmpty()) return;
        PurchaseDetailDto detail = opt.get();
        Purchase p = detail.purchase();

        StringBuilder sb = new StringBuilder();
        sb.append("=========================================\n");
        sb.append("         SMARTKHATA PURCHASE BILL        \n");
        sb.append("=========================================\n");
        sb.append("Bill Number : ").append(p.purchaseNumber()).append("\n");
        sb.append("Date        : ").append(p.purchaseDate().format(DATE_FMT)).append("\n");
        sb.append("Supplier    : ").append(detail.supplierName()).append("\n");
        sb.append("Phone       : ").append(detail.supplierPhone()).append("\n");
        sb.append("-----------------------------------------\n");
        sb.append(String.format("%-18s %5s %8s %9s\n", "Item", "Qty", "Cost", "Total"));
        sb.append("-----------------------------------------\n");
        for (PurchaseItemDto it : detail.items()) {
            sb.append(String.format("%-18s %5d %8s %9s\n",
                    it.productName().length() > 18 ? it.productName().substring(0, 18) : it.productName(),
                    it.quantity(),
                    it.unitCost().toBdt().toPlainString(),
                    it.lineTotal().toBdt().toPlainString()
            ));
        }
        sb.append("-----------------------------------------\n");
        sb.append("Subtotal    : ").append(BengaliMoneyFormatter.format(p.totalAmount())).append("\n");
        sb.append("Discount    : ").append(BengaliMoneyFormatter.format(p.discount())).append("\n");
        sb.append("Net Bill    : ").append(BengaliMoneyFormatter.format(p.netAmount())).append("\n");
        sb.append("Paid        : ").append(BengaliMoneyFormatter.format(p.paidAmount())).append("\n");
        sb.append("Due Payable : ").append(BengaliMoneyFormatter.format(p.dueAmount())).append("\n");
        sb.append("Status      : ").append(p.paymentStatus()).append("\n");
        sb.append("Created By  : ").append(detail.createdByName()).append("\n");
        sb.append("=========================================\n");

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        textArea.setPrefRowCount(18);
        textArea.setPrefColumnCount(42);

        SmartKhataDialog.<Void>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Purchase Bill Preview (ক্রয় রসিদ)")
                .subtitle("Purchase Bill: " + p.purchaseNumber())
                .icon(DialogComponents.SVG_PRINT, DialogComponents.Tone.SOFT_GREEN)
                .content(DialogComponents.sectionCard(textArea))
                .infoOnly(true)
                .primaryAction("Close", DialogComponents.SVG_CLOSE, SmartKhataDialog.ActionTone.PRIMARY)
                .showAndWait();
    }

    @FXML
    public void onVoidPurchase() {
        PurchaseListItemDto selected = tblPurchases.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblPurchases != null && tblPurchases.getScene() != null ? tblPurchases.getScene().getWindow() : null;

        AlertHelper.promptInput(
                owner,
                "Void Purchase Bill (ক্রয় চালান বাতিল)",
                "Voiding purchase " + selected.purchaseNumber() + " restores supplier payables and removes inward inventory movements.",
                "Mandatory Reversal Reason",
                "",
                "Enter audit reason for reversal...",
                true,
                "Void Purchase Bill"
        ).ifPresent(reason -> {
            try {
                long actingUserId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                purchaseService.voidPurchase(selected.id(), reason.trim(), actingUserId);
                AlertHelper.showInfo(owner, "Bill Voided", "Purchase " + selected.purchaseNumber() + " has been voided successfully.");
                loadPurchases();
            } catch (Exception ex) {
                log.error("Failed to void purchase: {}", ex.getMessage(), ex);
                AlertHelper.showStartupError(owner, "Void Failed", "Failed to void purchase: " + ex.getMessage(), ex);
            }
        });
    }
}

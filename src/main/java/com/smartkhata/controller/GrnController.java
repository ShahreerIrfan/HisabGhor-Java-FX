package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.GoodsReceiptNote;
import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.*;
import com.smartkhata.service.impl.*;
import com.smartkhata.ui.AlertHelper;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class GrnController {

    private static final Logger log = LoggerFactory.getLogger(GrnController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final GrnService grnService = new SqliteGrnService();
    private final PurchaseOrderService poService = new SqlitePurchaseOrderService();
    private final SupplierService supplierService = new SqliteSupplierService();
    private final InventoryService inventoryService = new SqliteInventoryService();

    @FXML private TextField txtGrnSearch;
    @FXML private ComboBox<String> cmbGrnStatus;
    @FXML private TableView<GrnListItemDto> tblGrns;
    @FXML private TableColumn<GrnListItemDto, String> colGrnNumber;
    @FXML private TableColumn<GrnListItemDto, String> colGrnDate;
    @FXML private TableColumn<GrnListItemDto, String> colGrnPo;
    @FXML private TableColumn<GrnListItemDto, String> colGrnSupplier;
    @FXML private TableColumn<GrnListItemDto, String> colGrnQty;
    @FXML private TableColumn<GrnListItemDto, String> colGrnTotal;
    @FXML private TableColumn<GrnListItemDto, String> colGrnStatus;

    @FXML private VBox vboxGrnNoSelection;
    @FXML private VBox vboxGrnDetail;
    @FXML private Label lblDetailGrnNo;
    @FXML private Label lblDetailGrnMeta;
    @FXML private Label lblDetailGrnReversal;
    @FXML private Button btnReverseGrn;
    @FXML private Label lblDetailSubtotal;
    @FXML private Label lblDetailFreight;
    @FXML private Label lblDetailTotalCost;

    @FXML private TableView<GrnItemViewDto> tblGrnItems;
    @FXML private TableColumn<GrnItemViewDto, String> colGiProduct;
    @FXML private TableColumn<GrnItemViewDto, String> colGiQty;
    @FXML private TableColumn<GrnItemViewDto, String> colGiBaseQty;
    @FXML private TableColumn<GrnItemViewDto, String> colGiUnitCost;
    @FXML private TableColumn<GrnItemViewDto, String> colGiTotal;

    private final ObservableList<GrnListItemDto> grnList = FXCollections.observableArrayList();
    private final ObservableList<GrnItemViewDto> itemList = FXCollections.observableArrayList();
    private volatile GrnDetailDto selectedDetail;
    private Runnable onDataChangedCallback;

    public void setOnDataChangedCallback(Runnable callback) {
        this.onDataChangedCallback = callback;
    }

    public void openGrnForPurchaseOrder(PurchaseOrderDetailDto poDetail) {
        if (poDetail != null) {
            openGrnPostingDialog(poDetail, null);
        }
    }

    @FXML
    public void initialize() {
        setupTables();
        cmbGrnStatus.setItems(FXCollections.observableArrayList("All Statuses", "POSTED", "REVERSED"));
        cmbGrnStatus.setValue("All Statuses");
        tblGrns.getSelectionModel().selectedItemProperty().addListener((o, oldV, newV) -> {
            if (newV != null) loadGrnDetail(newV.id());
            else showNoSelection();
        });
        loadGrns();
    }

    private void setupTables() {
        tblGrns.setItems(grnList);
        tblGrnItems.setItems(itemList);
        colGrnNumber.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().grnNumber()));
        colGrnDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().receiptDate().format(DT_FMT)));
        colGrnPo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().poNumber() != null ? d.getValue().poNumber() : "Direct Receipt"));
        colGrnSupplier.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierName()));
        colGrnQty.setCellValueFactory(d -> new SimpleStringProperty(
                "+" + d.getValue().totalAcceptedBaseQty() + " / -" + d.getValue().totalRejectedOrDamagedQty()));
        colGrnTotal.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().totalCost())));
        colGrnStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status().name()));

        colGiProduct.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName()));
        colGiQty.setCellValueFactory(d -> {
            GrnItemViewDto i = d.getValue();
            return new SimpleStringProperty(i.acceptedQty() + " / " + i.rejectedQty() + " / " + i.damagedQty());
        });
        colGiBaseQty.setCellValueFactory(d -> new SimpleStringProperty(
                "+" + d.getValue().acceptedBaseQty() + " " + d.getValue().baseUnit()));
        colGiUnitCost.setCellValueFactory(d -> new SimpleStringProperty(
                BengaliMoneyFormatter.format(d.getValue().effectiveBaseUnitCost())));
        colGiTotal.setCellValueFactory(d -> new SimpleStringProperty(
                BengaliMoneyFormatter.format(d.getValue().lineTotal())));
    }

    public void loadGrns() {
        if (!SessionContext.isAuthenticated()) return;
        long bizId = SessionContext.getRequiredBusinessId();
        String q = txtGrnSearch != null ? txtGrnSearch.getText() : null;
        String stStr = cmbGrnStatus != null ? cmbGrnStatus.getValue() : null;
        GrnStatus status = (stStr == null || "All Statuses".equals(stStr)) ? null : GrnStatus.valueOf(stStr);

        CompletableFuture.supplyAsync(() -> grnService.searchGrns(bizId, q, null, null, status, null, null))
                .thenAccept(list -> Platform.runLater(() -> {
                    grnList.setAll(list);
                    if (!grnList.isEmpty()) tblGrns.getSelectionModel().selectFirst();
                    else showNoSelection();
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load GRNs: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    private void loadGrnDetail(long grnId) {
        CompletableFuture.supplyAsync(() -> grnService.getGrnDetail(grnId))
                .thenAccept(opt -> Platform.runLater(() -> opt.ifPresentOrElse(detail -> {
                    selectedDetail = detail;
                    vboxGrnNoSelection.setVisible(false);
                    vboxGrnNoSelection.setManaged(false);
                    vboxGrnDetail.setVisible(true);
                    vboxGrnDetail.setManaged(true);

                    GoodsReceiptNote g = detail.grn();
                    lblDetailGrnNo.setText(g.grnNumber() + " (" + g.status() + ")");
                    String sup = detail.supplier() != null ? detail.supplier().name() : "Supplier #" + g.supplierId();
                    lblDetailGrnMeta.setText(String.format("Supplier: %s | Source: %s | Date: %s%s",
                            sup, detail.poNumber() != null ? "PO " + detail.poNumber() : "Direct Receipt",
                            g.receiptDate().format(DT_FMT),
                            g.challanReference() != null ? " | Challan: " + g.challanReference() : ""));

                    if (g.status() == GrnStatus.REVERSED) {
                        lblDetailGrnReversal.setText("REVERSED: " + g.reversalReason());
                        lblDetailGrnReversal.setVisible(true);
                        lblDetailGrnReversal.setManaged(true);
                    } else {
                        lblDetailGrnReversal.setVisible(false);
                        lblDetailGrnReversal.setManaged(false);
                    }
                    btnReverseGrn.setDisable(g.status() != GrnStatus.POSTED);
                    lblDetailSubtotal.setText(BengaliMoneyFormatter.format(g.subtotal()));
                    lblDetailFreight.setText(BengaliMoneyFormatter.format(g.freightAndOtherCosts()));
                    lblDetailTotalCost.setText(BengaliMoneyFormatter.format(g.totalCost()));
                    itemList.setAll(detail.items());
                }, this::showNoSelection)));
    }

    private void showNoSelection() {
        selectedDetail = null;
        vboxGrnDetail.setVisible(false);
        vboxGrnDetail.setManaged(false);
        vboxGrnNoSelection.setVisible(true);
        vboxGrnNoSelection.setManaged(true);
        itemList.clear();
    }

    @FXML private void onSearch() { loadGrns(); }
    @FXML private void onFilterChanged() { loadGrns(); }
    @FXML private void onRefresh() { loadGrns(); }

    @FXML
    private void onReverseGrn() {
        if (selectedDetail == null) return;
        Window owner = tblGrns != null && tblGrns.getScene() != null ? tblGrns.getScene().getWindow() : null;
        AlertHelper.promptInput(
                owner,
                "Reverse Posted GRN",
                "Reverse " + selectedDetail.grn().grnNumber() + " and deduct accepted stock via RETURN_OUT?",
                "Reversal Reason",
                "",
                "Mandatory audit reason for GRN reversal...",
                true,
                "Reverse GRN"
        ).ifPresent(reason -> {
            try {
                long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                grnService.reverseGrn(selectedDetail.grn().id(), reason, uid);
                loadGrns();
                if (onDataChangedCallback != null) onDataChangedCallback.run();
                AlertHelper.showInfo(owner, "GRN Reversed", "GRN reversed cleanly with compensating RETURN_OUT movement.");
            } catch (Exception e) {
                AlertHelper.showStartupError(owner, "Reversal Failed", e.getMessage(), e);
            }
        });
    }

    private record GrnLineRow(Long poItemId, long productId, String productName, int orderedPending,
                              TextField txtRec, TextField txtAcc, TextField txtRej, TextField txtDmg,
                              String unit, int packSize, TextField txtTp) {}

    @FXML
    private void onReceiveFromPo() {
        long bizId = SessionContext.getRequiredBusinessId();
        Window owner = tblGrns != null && tblGrns.getScene() != null ? tblGrns.getScene().getWindow() : null;
        List<PurchaseOrderListItemDto> receivable = poService.searchPurchaseOrders(bizId, null, null, null, null, null)
                .stream().filter(po -> po.status().canReceiveGoods()).toList();
        if (receivable.isEmpty()) {
            AlertHelper.showInfo(owner, "No Open POs", "No Confirmed or Partially Received Purchase Orders are awaiting receipt.");
            return;
        }
        AlertHelper.chooseOption(
                owner,
                "Select Purchase Order",
                "Choose a Confirmed or Partially Received PO to receive goods against.",
                "Purchase Order",
                receivable,
                receivable.get(0),
                po -> po.poNumber() + " — " + po.supplierName() + " (" + BengaliMoneyFormatter.format(po.grandTotal()) + ")",
                "Continue to GRN"
        ).ifPresent(chosenPo -> poService.getPurchaseOrderDetail(chosenPo.id())
                .ifPresent(detail -> openGrnPostingDialog(detail, null)));
    }

    @FXML
    private void onDirectGrn() {
        long bizId = SessionContext.getRequiredBusinessId();
        Window owner = tblGrns != null && tblGrns.getScene() != null ? tblGrns.getScene().getWindow() : null;
        List<SupplierListItemDto> sups = supplierService.searchSuppliers(bizId, null, false);
        if (sups.isEmpty()) {
            AlertHelper.showInfo(owner, "No Suppliers", "Create an active supplier before posting a direct GRN.");
            return;
        }
        AlertHelper.chooseOption(
                owner,
                "Direct GRN (Without PO)",
                "Select the supplier for direct goods receipt into inventory.",
                "Supplier",
                sups,
                sups.get(0),
                SupplierListItemDto::name,
                "Continue to GRN"
        ).ifPresent(sup -> openGrnPostingDialog(null, sup));
    }

    private void openGrnPostingDialog(PurchaseOrderDetailDto poDetail, SupplierListItemDto directSup) {
        long bizId = SessionContext.getRequiredBusinessId();
        Window owner = tblGrns != null && tblGrns.getScene() != null ? tblGrns.getScene().getWindow() : null;
        boolean isPoLinked = poDetail != null;
        long supplierId = isPoLinked ? poDetail.order().supplierId() : directSup.id();
        String supName = isPoLinked
                ? (poDetail.supplier() != null ? poDetail.supplier().name() : "Supplier #" + supplierId)
                : directSup.name();

        ObservableList<GrnLineRow> lines = FXCollections.observableArrayList();
        if (isPoLinked) {
            for (PurchaseOrderItemViewDto item : poDetail.items()) {
                int pending = item.pendingQty();
                if (pending > 0) {
                    TextField rec = new TextField(String.valueOf(pending));
                    TextField acc = new TextField(String.valueOf(pending));
                    TextField rej = new TextField("0");
                    TextField dmg = new TextField("0");
                    TextField tp = new TextField(item.tradePrice().toBdt().toPlainString());
                    lines.add(new GrnLineRow(item.id(), item.productId(), item.productName(), pending,
                            rec, acc, rej, dmg, item.purchaseUnit(), item.packSize(), tp));
                }
            }
        }

        TextField txtChallan = new TextField();
        txtChallan.setPromptText("Supplier Challan#");
        TextField txtDisc = new TextField("0");
        TextField txtFreight = new TextField("0");
        TextField txtNotes = new TextField();
        txtNotes.setPromptText("Inspection remarks or delivery condition notes");

        Label lblSummary = new Label("Accepted Goods Value: BDT 0");
        lblSummary.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #075C32;");

        Runnable updateCostSummary = () -> {
            long accTotal = 0L;
            for (GrnLineRow r : lines) {
                try {
                    int acc = Integer.parseInt(r.txtAcc().getText().trim());
                    Money tp = DialogComponents.parseNonNegativeMoney(r.txtTp().getText(), "TP");
                    if (acc > 0) accTotal += tp.multiply(acc).paisha();
                } catch (Exception ignored) {}
            }
            Money disc = Money.ZERO;
            Money fr = Money.ZERO;
            try { disc = DialogComponents.parseNonNegativeMoney(txtDisc.getText(), "Disc"); } catch (Exception ignored) {}
            try { fr = DialogComponents.parseNonNegativeMoney(txtFreight.getText(), "Freight"); } catch (Exception ignored) {}
            Money net = Money.ofPaisha(accTotal).subtract(disc).add(fr);
            lblSummary.setText("Accepted Line Value: " + BengaliMoneyFormatter.format(Money.ofPaisha(accTotal))
                    + "   •   Net Landed Cost: " + BengaliMoneyFormatter.format(net));
        };
        txtDisc.textProperty().addListener((o, a, b) -> updateCostSummary.run());
        txtFreight.textProperty().addListener((o, a, b) -> updateCostSummary.run());

        VBox linesContainer = new VBox(10.0);
        Runnable rebuildLinesUi = () -> {
            linesContainer.getChildren().clear();
            if (lines.isEmpty()) {
                linesContainer.getChildren().add(DialogComponents.emptyState(
                        DialogComponents.SVG_CUBE, "No GRN lines added yet",
                        "Add products above to record quantities accepted into inventory."
                ));
            } else {
                for (GrnLineRow r : lines) {
                    r.txtAcc().textProperty().addListener((o, a, b) -> updateCostSummary.run());
                    r.txtTp().textProperty().addListener((o, a, b) -> updateCostSummary.run());

                    Label prodTitle = new Label(r.productName() + " (" + r.unit() + " x" + r.packSize() + ")");
                    prodTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #18283B;");

                    GridPane rowGrid = new GridPane();
                    rowGrid.setHgap(10.0);
                    double[] cols = {16.0, 16.0, 14.0, 14.0, 26.0, 14.0};
                    for (double p : cols) {
                        javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
                        cc.setPercentWidth(p);
                        cc.setHgrow(Priority.ALWAYS);
                        rowGrid.getColumnConstraints().add(cc);
                    }
                    rowGrid.add(DialogComponents.formField("Received", true, null, DialogComponents.textFieldWrap(null, r.txtRec())), 0, 0);
                    rowGrid.add(DialogComponents.formField("Accepted", true, null, DialogComponents.textFieldWrap(null, r.txtAcc())), 1, 0);
                    rowGrid.add(DialogComponents.formField("Rejected", false, null, DialogComponents.textFieldWrap(null, r.txtRej())), 2, 0);
                    rowGrid.add(DialogComponents.formField("Damaged", false, null, DialogComponents.textFieldWrap(null, r.txtDmg())), 3, 0);
                    rowGrid.add(DialogComponents.formField("Trade Price", true, null, DialogComponents.currencyFieldWrap(r.txtTp())), 4, 0);

                    VBox lineBox = new VBox(8.0, prodTitle, rowGrid);
                    if (!isPoLinked) {
                        Button btnRem = new Button("Remove");
                        btnRem.getStyleClass().add("sk-prod-row-delete-btn");
                        btnRem.setOnAction(ev -> {
                            lines.remove(r);
                            updateCostSummary.run();
                        });
                        rowGrid.add(DialogComponents.formField(" ", false, null, btnRem), 5, 0);
                    }
                    lineBox.setStyle("-fx-background-color: #F7F9FB; -fx-border-color: #DCE5E9; -fx-border-radius: 9; -fx-background-radius: 9; -fx-padding: 10 12;");
                    linesContainer.getChildren().add(lineBox);
                }
            }
            updateCostSummary.run();
        };
        lines.addListener((javafx.collections.ListChangeListener<GrnLineRow>) c -> rebuildLinesUi.run());
        rebuildLinesUi.run();

        GridPane headerGrid = DialogComponents.responsiveTwoColGrid(
                520.0,
                DialogComponents.formField("Supplier", false, null,
                        DialogComponents.statusBadge(supName, DialogComponents.Tone.SUCCESS)),
                DialogComponents.formField("Supplier Challan#", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_RECEIPT, txtChallan))
        );
        VBox headerSection = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CLIPBOARD, "GRN Information",
                        isPoLinked ? "Receiving against " + poDetail.order().poNumber() : "Direct supplier delivery receipt"),
                headerGrid
        );

        VBox itemsCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "Received Products & Quantity Verification",
                        "Only accepted base units enter sellable stock and update Weighted Average Cost.")
        );
        if (!isPoLinked) {
            List<ProductListItemDto> products = inventoryService.searchProducts(bizId, null, null, false);
            ComboBox<ProductListItemDto> cmbProd = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(products)));
            cmbProd.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(ProductListItemDto p) {
                    return p == null ? "" : p.name() + (p.sku() != null ? " [" + p.sku() + "]" : "");
                }
                @Override public ProductListItemDto fromString(String s) { return null; }
            });
            if (!products.isEmpty()) cmbProd.getSelectionModel().selectFirst();
            Button btnAddProd = new Button("+ Add Product Line");
            btnAddProd.getStyleClass().add("sk-dlg-btn-subtle-green");
            btnAddProd.setPrefHeight(40.0);
            btnAddProd.setOnAction(ev -> {
                ProductListItemDto p = cmbProd.getValue();
                if (p == null) return;
                ProductSupplierViewDto resolved = poService.resolveProductPricingForSupplier(p.id(), supplierId);
                lines.add(new GrnLineRow(null, p.id(), p.name(), 0,
                        new TextField("1"), new TextField("1"), new TextField("0"), new TextField("0"),
                        resolved.purchaseUnit(), resolved.packSize(),
                        new TextField(resolved.tradePrice().toBdt().toPlainString())));
            });
            HBox pickerRow = new HBox(10.0, cmbProd, btnAddProd);
            HBox.setHgrow(cmbProd, Priority.ALWAYS);
            pickerRow.setAlignment(Pos.CENTER_LEFT);
            itemsCard.getChildren().add(pickerRow);
        }
        itemsCard.getChildren().add(linesContainer);

        GridPane costGrid = new GridPane();
        costGrid.setHgap(12.0);
        costGrid.setVgap(10.0);
        double[] cw = {24.0, 26.0, 50.0};
        for (double w : cw) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(w);
            c.setHgrow(Priority.ALWAYS);
            costGrid.getColumnConstraints().add(c);
        }
        costGrid.add(DialogComponents.formField("GRN Discount", false, null, DialogComponents.currencyFieldWrap(txtDisc)), 0, 0);
        costGrid.add(DialogComponents.formField("Freight / Handling Cost", false, null, DialogComponents.currencyFieldWrap(txtFreight)), 1, 0);
        costGrid.add(DialogComponents.formField("Receipt Notes", false, null, DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes)), 2, 0);

        VBox summaryCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Cost Summary & Adjustments",
                        "Freight and extra handling costs allocate proportionally into accepted inventory valuation."),
                costGrid,
                DialogComponents.tintedSectionCard(lblSummary)
        );

        VBox body = new VBox(14.0, headerSection, itemsCard, summaryCard);

        Optional<GoodsReceiptNote> result = SmartKhataDialog.<GoodsReceiptNote>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title(isPoLinked ? "Post GRN — " + poDetail.order().poNumber() : "Post Direct GRN — " + supName)
                .subtitle("Verify received, accepted, rejected, and damaged quantities before posting stock.")
                .icon(DialogComponents.SVG_TRUCK, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(txtChallan)
                .primaryAction("Post GRN & Update Stock", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    if (lines.isEmpty()) {
                        throw new IllegalArgumentException("Add at least one line item before posting GRN.");
                    }
                    List<GrnItemCreateDto> reqItems = new ArrayList<>();
                    for (GrnLineRow r : lines) {
                        int rec = DialogComponents.parseIntRange(r.txtRec().getText(), -1, 0, "Received qty (" + r.productName() + ")");
                        int acc = DialogComponents.parseIntRange(r.txtAcc().getText(), -1, 0, "Accepted qty (" + r.productName() + ")");
                        int rej = DialogComponents.parseIntRange(r.txtRej().getText(), 0, 0, "Rejected qty");
                        int dmg = DialogComponents.parseIntRange(r.txtDmg().getText(), 0, 0, "Damaged qty");
                        Money tp = DialogComponents.parseNonNegativeMoney(r.txtTp().getText(), "Trade Price (" + r.productName() + ")");
                        if (acc + rej + dmg != rec) {
                            throw new SmartKhataDialog.ValidationFocusException(
                                    r.productName() + ": Accepted + Rejected + Damaged must equal Received (" + rec + ").",
                                    r.txtAcc()
                            );
                        }
                        reqItems.add(new GrnItemCreateDto(r.poItemId(), r.productId(), rec, acc, rej, dmg,
                                r.unit(), r.packSize(), tp, Money.ZERO, null));
                    }
                    Money disc = DialogComponents.parseNonNegativeMoney(txtDisc.getText(), "Discount");
                    Money freight = DialogComponents.parseNonNegativeMoney(txtFreight.getText(), "Freight Cost");
                    long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                    return grnService.postGoodsReceipt(new GrnCreateDto(
                            bizId, isPoLinked ? poDetail.order().id() : null, supplierId,
                            disc, freight, txtChallan.getText(), txtNotes.getText(),
                            UUID.randomUUID().toString(), uid, reqItems
                    ));
                })
                .showAndWait();

        result.ifPresent(posted -> {
            loadGrns();
            if (onDataChangedCallback != null) onDataChangedCallback.run();
            AlertHelper.showInfo(owner, "GRN Posted",
                    "GRN " + posted.grnNumber() + " posted cleanly. Stock and Weighted Average Cost updated.");
        });
    }
}


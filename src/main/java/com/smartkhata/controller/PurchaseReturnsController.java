package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.GrnService;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteGrnService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteSupplierService;
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
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PurchaseReturnsController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final GrnService grnService = new SqliteGrnService();
    private final SupplierService supplierService = new SqliteSupplierService();
    private final InventoryService inventoryService = new SqliteInventoryService();

    @FXML private TableView<PurchaseReturnListItemDto> tblReturns;
    @FXML private TableColumn<PurchaseReturnListItemDto, String> colRetNumber;
    @FXML private TableColumn<PurchaseReturnListItemDto, String> colRetDate;
    @FXML private TableColumn<PurchaseReturnListItemDto, String> colRetSupplier;
    @FXML private TableColumn<PurchaseReturnListItemDto, String> colRetGrn;
    @FXML private TableColumn<PurchaseReturnListItemDto, String> colRetAmount;
    @FXML private TableColumn<PurchaseReturnListItemDto, String> colRetReason;

    private final ObservableList<PurchaseReturnListItemDto> rows = FXCollections.observableArrayList();
    private Runnable onDataChangedCallback;

    public void setOnDataChangedCallback(Runnable callback) {
        this.onDataChangedCallback = callback;
    }

    @FXML
    public void initialize() {
        tblReturns.setItems(rows);
        colRetNumber.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().returnNumber()));
        colRetDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().returnDate().format(DT_FMT)));
        colRetSupplier.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierName()));
        colRetGrn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().grnNumber() != null ? d.getValue().grnNumber() : "—"));
        colRetAmount.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().totalReturnAmount())));
        colRetReason.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reason()));
        loadReturns();
    }

    @FXML private void onRefresh() { loadReturns(); }

    public void loadReturns() {
        if (!SessionContext.isAuthenticated()) return;
        long bizId = SessionContext.getRequiredBusinessId();
        CompletableFuture.supplyAsync(() -> grnService.getPurchaseReturns(bizId))
                .thenAccept(list -> Platform.runLater(() -> rows.setAll(list)));
    }

    @FXML
    private void onNewReturn() {
        long bizId = SessionContext.getRequiredBusinessId();
        Window owner = tblReturns != null && tblReturns.getScene() != null ? tblReturns.getScene().getWindow() : null;
        List<SupplierListItemDto> sups = supplierService.searchSuppliers(bizId, null, false);
        List<ProductListItemDto> prods = inventoryService.searchProducts(bizId, null, null, false);
        if (sups.isEmpty() || prods.isEmpty()) {
            AlertHelper.showInfo(owner, "Prerequisites Missing", "Add at least one active supplier and product first.");
            return;
        }

        ComboBox<SupplierListItemDto> cmbSup = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(sups)));
        cmbSup.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SupplierListItemDto s) { return s == null ? "" : s.name(); }
            @Override public SupplierListItemDto fromString(String s) { return null; }
        });
        cmbSup.getSelectionModel().selectFirst();

        ComboBox<ProductListItemDto> cmbProd = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(prods)));
        cmbProd.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ProductListItemDto p) {
                return p == null ? "" : p.name() + " (Stock: " + p.currentStock() + " " + p.unit() + ")";
            }
            @Override public ProductListItemDto fromString(String s) { return null; }
        });
        cmbProd.getSelectionModel().selectFirst();

        TextField txtQty = new TextField("1");
        TextField txtCost = new TextField(prods.get(0).costPrice().toBdt().toPlainString());
        cmbProd.valueProperty().addListener((o, a, prod) -> {
            if (prod != null) txtCost.setText(prod.costPrice().toBdt().toPlainString());
        });
        CheckBox chkCreditLedger = new CheckBox("Reduce Supplier Payable Ledger");
        chkCreditLedger.setSelected(true);
        chkCreditLedger.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #18283B;");

        TextField txtReason = new TextField("Damaged goods returned to vendor");
        Label lblTotalReturn = new Label("Total Return Value: " + BengaliMoneyFormatter.format(prods.get(0).costPrice()));
        lblTotalReturn.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #075C32;");

        Runnable updateTotal = () -> {
            try {
                int q = Math.max(0, Integer.parseInt(txtQty.getText().trim()));
                Money c = DialogComponents.parseNonNegativeMoney(txtCost.getText(), "Cost");
                lblTotalReturn.setText("Total Return Value: " + BengaliMoneyFormatter.format(c.multiply(q)));
            } catch (Exception ignored) {
                lblTotalReturn.setText("Total Return Value: —");
            }
        };
        txtQty.textProperty().addListener((o, a, b) -> updateTotal.run());
        txtCost.textProperty().addListener((o, a, b) -> updateTotal.run());

        VBox partyProdCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_RETURN, "Supplier & Product Selection",
                        "Select vendor and product stock to return via RETURN_OUT."),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Supplier", true, null, cmbSup),
                        DialogComponents.formField("Product", true, null, cmbProd),
                        DialogComponents.formField("Return Base Quantity", true, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_CUBE, txtQty)),
                        DialogComponents.formField("Unit Cost (BDT)", true, null,
                                DialogComponents.currencyFieldWrap(txtCost))
                )
        );

        VBox reasonCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_NOTE, "Ledger & Reason",
                        "Provide audit reason and choose whether to credit vendor payable."),
                DialogComponents.formField("Return Reason", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtReason)),
                chkCreditLedger,
                DialogComponents.tintedSectionCard(lblTotalReturn)
        );

        VBox body = new VBox(14.0, partyProdCard, reasonCard);

        SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("New Supplier Purchase Return")
                .subtitle("Return damaged or excess stock and optionally adjust supplier payable.")
                .icon(DialogComponents.SVG_RETURN, DialogComponents.Tone.WARNING)
                .content(body)
                .initialFocus(txtQty)
                .primaryAction("Post Purchase Return", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.WARNING)
                .onSubmit(() -> {
                    SupplierListItemDto sup = cmbSup.getValue();
                    ProductListItemDto prod = cmbProd.getValue();
                    if (sup == null || prod == null) {
                        throw new IllegalArgumentException("Supplier and product selection are required.");
                    }
                    int qty = DialogComponents.parseIntRange(txtQty.getText(), 0, 1, "Return Quantity");
                    Money cost = DialogComponents.parseNonNegativeMoney(txtCost.getText(), "Unit Cost");
                    String reason = txtReason.getText() != null ? txtReason.getText().trim() : "";
                    if (reason.isBlank()) {
                        throw new SmartKhataDialog.ValidationFocusException("Return Reason is required.", txtReason);
                    }
                    long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                    grnService.createPurchaseReturn(new PurchaseReturnCreateDto(
                            bizId, sup.id(), null, chkCreditLedger.isSelected(), reason, uid,
                            List.of(new PurchaseReturnItemCreateDto(null, prod.id(), qty, cost, null))
                    ));
                    return Boolean.TRUE;
                })
                .showAndWait()
                .ifPresent(ok -> {
                    loadReturns();
                    if (onDataChangedCallback != null) onDataChangedCallback.run();
                    AlertHelper.showInfo(owner, "Return Recorded",
                            "Stock reduced via RETURN_OUT and supplier return posted.");
                });
    }
}


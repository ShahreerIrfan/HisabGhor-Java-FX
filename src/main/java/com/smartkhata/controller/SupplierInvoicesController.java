package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.SupplierInvoice;
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
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SupplierInvoicesController {

    private final SupplierInvoiceService invoiceService = new SqliteSupplierInvoiceService();
    private final GrnService grnService = new SqliteGrnService();
    private final PaymentService paymentService = new SqlitePaymentService();

    @FXML private TextField txtInvSearch;
    @FXML private Label lblUninvoicedLiability;
    @FXML private ComboBox<String> cmbInvPayStatus;
    @FXML private TableView<SupplierInvoiceListItemDto> tblInvoices;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvNo;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colBillNo;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvSupplier;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvGrn;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvMatch;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvNet;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvDue;
    @FXML private TableColumn<SupplierInvoiceListItemDto, String> colInvStatus;

    @FXML private VBox vboxInvNoSelection;
    @FXML private VBox vboxInvDetail;
    @FXML private Label lblDetailInvNo;
    @FXML private Label lblDetailInvMeta;
    @FXML private Button btnPayInvoice;
    @FXML private Button btnVoidInvoice;
    @FXML private Label lblDetailNet;
    @FXML private Label lblDetailPaid;
    @FXML private Label lblDetailDue;

    @FXML private TableView<SupplierInvoiceItemViewDto> tblInvItems;
    @FXML private TableColumn<SupplierInvoiceItemViewDto, String> colSiiProduct;
    @FXML private TableColumn<SupplierInvoiceItemViewDto, String> colSiiQty;
    @FXML private TableColumn<SupplierInvoiceItemViewDto, String> colSiiPrice;
    @FXML private TableColumn<SupplierInvoiceItemViewDto, String> colSiiVar;
    @FXML private TableColumn<SupplierInvoiceItemViewDto, String> colSiiTotal;

    private final ObservableList<SupplierInvoiceListItemDto> rows = FXCollections.observableArrayList();
    private final ObservableList<SupplierInvoiceItemViewDto> detailLines = FXCollections.observableArrayList();
    private volatile SupplierInvoiceDetailDto selectedDetail;
    private Runnable onDataChangedCallback;

    public void setOnDataChangedCallback(Runnable callback) {
        this.onDataChangedCallback = callback;
    }

    @FXML
    public void initialize() {
        tblInvoices.setItems(rows);
        tblInvItems.setItems(detailLines);

        colInvNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().invoiceNumber()));
        colBillNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierBillNo() != null ? d.getValue().supplierBillNo() : "—"));
        colInvSupplier.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().supplierName()));
        colInvGrn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().grnNumber() != null ? d.getValue().grnNumber() : "Direct"));
        colInvMatch.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().matchingStatus()));
        colInvNet.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().netAmount())));
        colInvDue.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().dueAmount())));
        colInvStatus.setCellValueFactory(d -> new SimpleStringProperty("VOIDED".equals(d.getValue().status()) ? "VOIDED" : d.getValue().paymentStatus()));

        colSiiProduct.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().productName()));
        colSiiQty.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().invoicedBaseQty() + " " + d.getValue().baseUnit()));
        colSiiPrice.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().unitPrice())));
        colSiiVar.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().variance())));
        colSiiTotal.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().lineTotal())));

        cmbInvPayStatus.setItems(FXCollections.observableArrayList("All Statuses", "DUE", "PARTIAL", "PAID"));
        cmbInvPayStatus.setValue("All Statuses");
        tblInvoices.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) loadDetail(b.id());
            else showNoSelection();
        });
        loadInvoices();
    }

    public void loadInvoices() {
        if (!SessionContext.isAuthenticated()) return;
        long bizId = SessionContext.getRequiredBusinessId();
        String q = txtInvSearch != null ? txtInvSearch.getText() : null;
        String ps = cmbInvPayStatus != null ? cmbInvPayStatus.getValue() : null;
        String filter = (ps == null || "All Statuses".equals(ps)) ? null : ps;

        CompletableFuture.runAsync(() -> {
            List<SupplierInvoiceListItemDto> list = invoiceService.searchSupplierInvoices(bizId, q, null, filter, null, null);
            Money uninvoiced = invoiceService.getTotalUninvoicedGrnLiability(bizId);
            Platform.runLater(() -> {
                rows.setAll(list);
                lblUninvoicedLiability.setText(BengaliMoneyFormatter.format(uninvoiced));
                if (!rows.isEmpty()) tblInvoices.getSelectionModel().selectFirst();
                else showNoSelection();
            });
        });
    }

    private void loadDetail(long id) {
        CompletableFuture.supplyAsync(() -> invoiceService.getSupplierInvoiceDetail(id))
                .thenAccept(opt -> Platform.runLater(() -> opt.ifPresentOrElse(d -> {
                    selectedDetail = d;
                    vboxInvNoSelection.setVisible(false);
                    vboxInvNoSelection.setManaged(false);
                    vboxInvDetail.setVisible(true);
                    vboxInvDetail.setManaged(true);

                    SupplierInvoice inv = d.invoice();
                    lblDetailInvNo.setText(inv.invoiceNumber() + " (" + inv.status() + ")");
                    lblDetailInvMeta.setText(String.format("Supplier: %s | Bill#: %s | GRN: %s | Match: %s%s",
                            d.supplier() != null ? d.supplier().name() : "#" + inv.supplierId(),
                            inv.supplierBillNo() != null ? inv.supplierBillNo() : "—",
                            d.grnNumber() != null ? d.grnNumber() : "Direct",
                            inv.matchingStatus(),
                            inv.dueDate() != null ? " | Due: " + inv.dueDate() : ""));
                    lblDetailNet.setText(BengaliMoneyFormatter.format(inv.netAmount()));
                    lblDetailPaid.setText(BengaliMoneyFormatter.format(inv.paidAmount()));
                    lblDetailDue.setText(BengaliMoneyFormatter.format(inv.dueAmount()));
                    btnPayInvoice.setDisable("VOIDED".equals(inv.status()) || inv.dueAmount().isZero());
                    btnVoidInvoice.setDisable("VOIDED".equals(inv.status()) || inv.paidAmount().isPositive());
                    detailLines.setAll(d.items());
                }, this::showNoSelection)));
    }

    private void showNoSelection() {
        selectedDetail = null;
        vboxInvDetail.setVisible(false);
        vboxInvDetail.setManaged(false);
        vboxInvNoSelection.setVisible(true);
        vboxInvNoSelection.setManaged(true);
        detailLines.clear();
    }

    @FXML private void onSearch() { loadInvoices(); }
    @FXML private void onFilterChanged() { loadInvoices(); }
    @FXML private void onRefresh() { loadInvoices(); }

    @FXML
    private void onInvoiceFromGrn() {
        long bizId = SessionContext.getRequiredBusinessId();
        Window owner = tblInvoices != null && tblInvoices.getScene() != null ? tblInvoices.getScene().getWindow() : null;
        List<GrnListItemDto> uninvoiced = invoiceService.getUninvoicedGrns(bizId, null);
        if (uninvoiced.isEmpty()) {
            AlertHelper.showInfo(owner, "All Caught Up", "All posted GRNs have already been matched and invoiced.");
            return;
        }
        AlertHelper.chooseOption(
                owner,
                "Select Uninvoiced GRN",
                "Choose a posted GRN (Received Not Invoiced) to match & post vendor bill.",
                "Posted GRN",
                uninvoiced,
                uninvoiced.get(0),
                g -> g.grnNumber() + " — " + g.supplierName() + " (" + BengaliMoneyFormatter.format(g.totalCost()) + ")",
                "Continue to Invoice"
        ).ifPresent(chosen -> grnService.getGrnDetail(chosen.id()).ifPresent(grnDetail -> {
            VBox summaryStrip = DialogComponents.tintedSectionCard(
                    DialogComponents.sectionHeader(DialogComponents.SVG_CLIPBOARD,
                            "3-Way Match Verification (" + grnDetail.grn().grnNumber() + ")",
                            "Supplier: " + chosen.supplierName() + "  •  GRN Verified Total: " + BengaliMoneyFormatter.format(grnDetail.grn().totalCost()))
            );

            TableView<GrnItemViewDto> tblVerify = DialogComponents.styleTable(
                    new TableView<>(FXCollections.observableArrayList(
                            grnDetail.items().stream().filter(i -> i.acceptedBaseQty() > 0).toList()))
            );
            tblVerify.setPrefHeight(175);
            TableColumn<GrnItemViewDto, String> cProd = new TableColumn<>("Product");
            cProd.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().productName()));
            TableColumn<GrnItemViewDto, String> cQty = DialogComponents.rightAlignedColumn("Accepted Base Qty",
                    i -> i.acceptedBaseQty() + " " + i.baseUnit());
            TableColumn<GrnItemViewDto, String> cRate = DialogComponents.rightAlignedColumn("Eff. Unit Cost",
                    i -> BengaliMoneyFormatter.format(i.effectiveBaseUnitCost()));
            TableColumn<GrnItemViewDto, String> cTot = DialogComponents.rightAlignedColumn("Line Cost",
                    i -> BengaliMoneyFormatter.format(i.lineTotal()));
            tblVerify.getColumns().addAll(cProd, cQty, cRate, cTot);

            TextField txtBillNo = new TextField();
            txtBillNo.setPromptText("e.g. VB-2026-991");
            DatePicker dpDue = DialogComponents.styleDatePicker(new DatePicker(LocalDate.now().plusDays(
                    grnDetail.supplier() != null ? grnDetail.supplier().creditPeriodDays() : 0)));
            TextField txtTax = new TextField("0");
            TextField txtPayNow = new TextField("0");
            ComboBox<String> cmbMethod = DialogComponents.styleCombo(
                    new ComboBox<>(FXCollections.observableArrayList("CASH", "BANK", "BKASH", "NAGAD", "ROCKET")));
            cmbMethod.getSelectionModel().select("CASH");
            CheckBox chkApproveVar = new CheckBox("Owner Override if 3-Way Tolerance Exceeded");
            chkApproveVar.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 600; -fx-text-fill: #18283B;");

            VBox itemsCard = DialogComponents.sectionCard(
                    DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "GRN Accepted Line Items",
                            "Matched against PO/GRN accepted quantities and landed cost."),
                    tblVerify
            );

            VBox billingCard = DialogComponents.sectionCard(
                    DialogComponents.sectionHeader(DialogComponents.SVG_RECEIPT, "Vendor Bill & Payment Details",
                            "Enter vendor bill number, tax, due date, and optional immediate disbursement."),
                    DialogComponents.responsiveTwoColGrid(
                            540.0,
                            DialogComponents.formField("Vendor Bill #", false, null,
                                    DialogComponents.textFieldWrap(DialogComponents.SVG_RECEIPT, txtBillNo)),
                            DialogComponents.formField("Payment Due Date", false, null, dpDue),
                            DialogComponents.formField("Tax / VAT (BDT)", false, null,
                                    DialogComponents.currencyFieldWrap(txtTax)),
                            DialogComponents.formField("Pay Now (BDT)", false, null,
                                    DialogComponents.currencyFieldWrap(txtPayNow))
                    ),
                    DialogComponents.responsiveTwoColGrid(
                            540.0,
                            DialogComponents.formField("Payment Method", false, null, cmbMethod),
                            DialogComponents.formField("Tolerance Override", false, null, chkApproveVar)
                    )
            );

            VBox body = new VBox(14.0, summaryStrip, itemsCard, billingCard);

            Optional<SupplierInvoice> res = SmartKhataDialog.<SupplierInvoice>builder()
                    .owner(owner)
                    .size(SmartKhataDialog.Size.LARGE)
                    .title("Post Supplier Invoice — " + grnDetail.grn().grnNumber())
                    .subtitle("Verify 3-Way match and credit supplier payable ledger.")
                    .icon(DialogComponents.SVG_RECEIPT, DialogComponents.Tone.SOFT_GREEN)
                    .content(body)
                    .initialFocus(txtBillNo)
                    .primaryAction("Post Invoice & Update Payable", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                    .onSubmit(() -> {
                        Money tax = DialogComponents.parseNonNegativeMoney(txtTax.getText(), "Tax");
                        Money pay = DialogComponents.parseNonNegativeMoney(txtPayNow.getText(), "Pay Now");
                        List<SupplierInvoiceItemCreateDto> invLines = new ArrayList<>();
                        for (GrnItemViewDto gi : grnDetail.items()) {
                            if (gi.acceptedBaseQty() > 0) {
                                invLines.add(new SupplierInvoiceItemCreateDto(
                                        gi.id(), gi.poItemId(), gi.productId(),
                                        gi.acceptedBaseQty(), gi.effectiveBaseUnitCost(), gi.batchOrRemark()
                                ));
                            }
                        }
                        long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                        return invoiceService.postSupplierInvoice(new SupplierInvoiceCreateDto(
                                bizId, grnDetail.grn().supplierId(), grnDetail.grn().purchaseOrderId(),
                                grnDetail.grn().id(), txtBillNo.getText(), dpDue.getValue(),
                                Money.ZERO, tax, Money.ZERO, pay, cmbMethod.getValue(), null,
                                chkApproveVar.isSelected(), "3-Way Matched Invoice for " + grnDetail.grn().grnNumber(),
                                UUID.randomUUID().toString(), uid, invLines
                        ));
                    })
                    .showAndWait();

            res.ifPresent(posted -> {
                loadInvoices();
                if (onDataChangedCallback != null) onDataChangedCallback.run();
                AlertHelper.showInfo(owner, "Invoice Posted",
                        "Supplier Invoice " + posted.invoiceNumber() + " verified & added to Supplier Payable Ledger.");
            });
        }));
    }

    @FXML
    private void onRecordPayment() {
        if (selectedDetail == null) return;
        Window owner = tblInvoices != null && tblInvoices.getScene() != null ? tblInvoices.getScene().getWindow() : null;
        SupplierInvoice inv = selectedDetail.invoice();
        String supName = selectedDetail.supplier() != null ? selectedDetail.supplier().name() : "Supplier";

        AlertHelper.promptInput(
                owner,
                "Record Supplier Disbursement",
                "Pay " + supName + " (Invoice Due: " + BengaliMoneyFormatter.format(inv.dueAmount()) + ")",
                "Disbursement Amount (BDT)",
                inv.dueAmount().toBdt().toPlainString(),
                "Enter amount in BDT",
                true,
                "Record Payment"
        ).ifPresent(amtStr -> {
            try {
                Money amt = DialogComponents.parsePositiveMoney(amtStr, "Disbursement Amount");
                long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                paymentService.makeSupplierPayment(new PaymentCreateDto(
                        inv.businessId(), "SUPPLIER", inv.supplierId(), amt, "CASH",
                        inv.invoiceNumber(), "Payment against " + inv.invoiceNumber(), UUID.randomUUID().toString(), uid
                ));
                loadInvoices();
                if (onDataChangedCallback != null) onDataChangedCallback.run();
                AlertHelper.showInfo(owner, "Payment Recorded", "Supplier disbursement applied FIFO cleanly.");
            } catch (Exception e) {
                AlertHelper.showStartupError(owner, "Payment Failed", e.getMessage(), e);
            }
        });
    }

    @FXML
    private void onVoidInvoice() {
        if (selectedDetail == null) return;
        Window owner = tblInvoices != null && tblInvoices.getScene() != null ? tblInvoices.getScene().getWindow() : null;
        AlertHelper.promptInput(
                owner,
                "Void Supplier Invoice",
                "Void " + selectedDetail.invoice().invoiceNumber() + " and reverse supplier payable?",
                "Void Reason",
                "",
                "Mandatory reason for voiding this invoice...",
                true,
                "Void Invoice"
        ).ifPresent(reason -> {
            try {
                long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                invoiceService.voidSupplierInvoice(selectedDetail.invoice().id(), reason, uid);
                loadInvoices();
                if (onDataChangedCallback != null) onDataChangedCallback.run();
            } catch (Exception e) {
                AlertHelper.showStartupError(owner, "Void Failed", e.getMessage(), e);
            }
        });
    }
}

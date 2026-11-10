package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Payment;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.PaymentService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteCustomerService;
import com.smartkhata.service.impl.SqlitePaymentService;
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

/**
 * Controller for Customer Dues Collections and Supplier Disbursements.
 */
public class PaymentsController {

    private static final Logger log = LoggerFactory.getLogger(PaymentsController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final PaymentService paymentService = new SqlitePaymentService();
    private final CustomerService customerService = new SqliteCustomerService();
    private final SupplierService supplierService = new SqliteSupplierService();

    // Top Header Pills & Actions
    @FXML private Label lblTotalCollections;
    @FXML private Label lblTotalDisbursements;
    @FXML private Button btnReceivePayment;
    @FXML private Button btnMakePayment;

    // Filters
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbPartyTypeFilter;
    @FXML private ComboBox<String> cmbMethodFilter;
    @FXML private Button btnRefresh;

    // Main Table
    @FXML private TableView<PaymentListItemDto> tblPayments;
    @FXML private TableColumn<PaymentListItemDto, String> colPaymentNo;
    @FXML private TableColumn<PaymentListItemDto, String> colDate;
    @FXML private TableColumn<PaymentListItemDto, String> colPartyType;
    @FXML private TableColumn<PaymentListItemDto, String> colPartyName;
    @FXML private TableColumn<PaymentListItemDto, String> colMethod;
    @FXML private TableColumn<PaymentListItemDto, String> colAmount;
    @FXML private TableColumn<PaymentListItemDto, String> colReference;
    @FXML private TableColumn<PaymentListItemDto, String> colStatus;

    // Right Detail Panel
    @FXML private VBox pnlDetailContainer;
    @FXML private VBox vboxNoSelection;
    @FXML private VBox vboxPaymentDetails;
    @FXML private Label lblDetailPaymentNo;
    @FXML private Label lblDetailPartyInfo;
    @FXML private Label lblDetailNotes;
    @FXML private Button btnEditPayment;
    @FXML private Button btnPrintVoucher;
    @FXML private Button btnVoidPayment;
    @FXML private Button btnDeleteVoidedPayment;
    @FXML private Label lblDetailAmount;
    @FXML private Label lblDetailMethod;
    @FXML private Label lblDetailTrxRef;
    @FXML private Label lblVoidStatus;

    // Allocations Table
    @FXML private TableView<PaymentAllocationDto> tblDetailAllocations;
    @FXML private TableColumn<PaymentAllocationDto, String> colAllocType;
    @FXML private TableColumn<PaymentAllocationDto, String> colAllocRef;
    @FXML private TableColumn<PaymentAllocationDto, String> colAllocAmount;

    private final ObservableList<PaymentListItemDto> paymentList = FXCollections.observableArrayList();
    private final ObservableList<PaymentAllocationDto> detailAllocList = FXCollections.observableArrayList();
    private volatile Payment selectedDetailPayment = null;

    public record PartyOption(Long id, String name, Money currentBalance) {
        @Override
        public String toString() {
            return name + " (Due: " + BengaliMoneyFormatter.format(currentBalance) + ")";
        }
    }

    @FXML
    public void initialize() {
        log.info("Initializing PaymentsController...");

        setupTableColumns();
        setupFilters();

        tblPayments.setItems(paymentList);
        tblDetailAllocations.setItems(detailAllocList);

        tblPayments.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadPaymentDetails(newVal.id());
            } else {
                showNoSelection();
            }
        });

        loadPayments();
    }

    private void setupTableColumns() {
        colPaymentNo.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().paymentNumber()));
        colDate.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().paymentDate().format(DATE_FMT)));
        colPartyType.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().partyType()));
        colPartyName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().partyName()));
        colMethod.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().paymentMethod()));
        colAmount.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().amount())));
        colReference.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().referenceTrxId() != null ? data.getValue().referenceTrxId() : "—"));

        colPartyType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String type, boolean empty) {
                super.updateItem(type, empty);
                if (empty || type == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(type);
                    if ("CUSTOMER".equalsIgnoreCase(type)) {
                        setStyle("-fx-text-fill: #15803D; -fx-font-weight: bold; -fx-background-color: #DCFCE7; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else {
                        setStyle("-fx-text-fill: #1E40AF; -fx-font-weight: bold; -fx-background-color: #DBEAFE; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    }
                }
            }
        });

        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isReversed() ? "VOIDED" : "POSTED"));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if ("VOIDED".equalsIgnoreCase(status)) {
                        setStyle("-fx-text-fill: #991B1B; -fx-font-weight: bold; -fx-background-color: #FEE2E2; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else {
                        setStyle("-fx-text-fill: #166534; -fx-font-weight: bold; -fx-alignment: center; -fx-padding: 3 8;");
                    }
                }
            }
        });

        // Allocation details columns
        colAllocType.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().saleId() != null ? "Sale Invoice" : "Purchase Bill"));
        colAllocRef.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().saleId() != null ? data.getValue().invoiceNumber() : data.getValue().purchaseNumber()));
        colAllocAmount.setCellValueFactory(data -> new SimpleStringProperty(BengaliMoneyFormatter.format(data.getValue().allocatedAmount())));
    }

    private void setupFilters() {
        cmbPartyTypeFilter.getItems().setAll("All Parties", "CUSTOMER", "SUPPLIER");
        cmbPartyTypeFilter.setValue("All Parties");

        cmbMethodFilter.getItems().setAll("All Methods", "CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER");
        cmbMethodFilter.setValue("All Methods");
    }

    public void loadPayments() {
        if (!SessionContext.isAuthenticated()) {
            return;
        }
        long businessId = SessionContext.getRequiredBusinessId();
        String query = txtSearch.getText();
        String partyType = cmbPartyTypeFilter.getValue();
        if ("All Parties".equalsIgnoreCase(partyType)) partyType = null;

        String method = cmbMethodFilter.getValue();
        if ("All Methods".equalsIgnoreCase(method)) method = null;

        final String pType = partyType;
        final String pMethod = method;

        CompletableFuture.supplyAsync(() -> paymentService.searchPayments(businessId, query, pType, null, pMethod, null, null))
                .thenAccept(results -> Platform.runLater(() -> {
                    paymentList.setAll(results);
                    updateMetrics(results);
                    if (!paymentList.isEmpty()) {
                        tblPayments.getSelectionModel().selectFirst();
                    } else {
                        showNoSelection();
                    }
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load payments: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> AlertHelper.showInfo(null, "Data Error", "Could not load payment records: " + ex.getMessage()));
                    return null;
                });
    }

    private void updateMetrics(List<PaymentListItemDto> items) {
        Money totalCollections = Money.ZERO;
        Money totalDisbursements = Money.ZERO;

        for (PaymentListItemDto p : items) {
            if (!p.isReversed()) {
                if ("CUSTOMER".equalsIgnoreCase(p.partyType())) {
                    totalCollections = totalCollections.add(p.amount());
                } else if ("SUPPLIER".equalsIgnoreCase(p.partyType())) {
                    totalDisbursements = totalDisbursements.add(p.amount());
                }
            }
        }

        lblTotalCollections.setText(BengaliMoneyFormatter.format(totalCollections));
        lblTotalDisbursements.setText(BengaliMoneyFormatter.format(totalDisbursements));
    }

    private void loadPaymentDetails(long paymentId) {
        CompletableFuture.supplyAsync(() -> paymentService.getPaymentDetail(paymentId))
                .thenAccept(opt -> Platform.runLater(() -> {
                    if (opt.isPresent()) {
                        showPaymentDetails(opt.get());
                    } else {
                        showNoSelection();
                    }
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load payment detail: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    private void showPaymentDetails(PaymentDetailDto detail) {
        vboxNoSelection.setVisible(false);
        vboxNoSelection.setManaged(false);

        vboxPaymentDetails.setVisible(true);
        vboxPaymentDetails.setManaged(true);

        Payment p = detail.payment();
        selectedDetailPayment = p;
        lblDetailPaymentNo.setText(p.paymentNumber());
        lblDetailPartyInfo.setText((p.partyType().equals("CUSTOMER") ? "Customer: " : "Supplier: ") +
                detail.partyName() + " | Date: " + p.paymentDate().format(DATE_FMT));
        if (lblDetailNotes != null) {
            boolean hasNote = p.notes() != null && !p.notes().isBlank();
            lblDetailNotes.setText(hasNote ? "Note: " + p.notes().trim() : "");
            lblDetailNotes.setVisible(hasNote);
            lblDetailNotes.setManaged(hasNote);
        }

        lblDetailAmount.setText(BengaliMoneyFormatter.format(p.amount()));
        lblDetailMethod.setText(p.paymentMethod());
        lblDetailTrxRef.setText(p.referenceTrxId() != null && !p.referenceTrxId().isBlank() ? p.referenceTrxId() : "—");

        detailAllocList.setAll(detail.allocations());

        if (p.isReversed()) {
            lblVoidStatus.setText("VOIDED: " + (p.reversalReason() != null ? p.reversalReason() : "Reversed"));
            lblVoidStatus.setVisible(true);
            lblVoidStatus.setManaged(true);
            btnVoidPayment.setDisable(true);
            if (btnEditPayment != null) btnEditPayment.setDisable(true);
        } else {
            lblVoidStatus.setVisible(false);
            lblVoidStatus.setManaged(false);
            btnVoidPayment.setDisable(false);
            if (btnEditPayment != null) btnEditPayment.setDisable(false);
        }

        boolean canDeleteVoided = p.isReversed() && SessionContext.isOwner();
        if (btnDeleteVoidedPayment != null) {
            btnDeleteVoidedPayment.setVisible(canDeleteVoided);
            btnDeleteVoidedPayment.setManaged(canDeleteVoided);
        }
    }

    private void showNoSelection() {
        selectedDetailPayment = null;
        if (lblDetailNotes != null) {
            lblDetailNotes.setVisible(false);
            lblDetailNotes.setManaged(false);
        }
        if (btnDeleteVoidedPayment != null) {
            btnDeleteVoidedPayment.setVisible(false);
            btnDeleteVoidedPayment.setManaged(false);
        }
        vboxPaymentDetails.setVisible(false);
        vboxPaymentDetails.setManaged(false);
        vboxNoSelection.setVisible(true);
        vboxNoSelection.setManaged(true);
    }

    @FXML
    public void onSearchKeyReleased() {
        loadPayments();
    }

    @FXML
    public void onFilterChanged() {
        loadPayments();
    }

    @FXML
    public void onRefresh() {
        loadPayments();
    }

    @FXML
    public void onEditPayment() {
        Payment current = selectedDetailPayment;
        PaymentListItemDto selected = tblPayments.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (selected.isReversed()) {
            AlertHelper.showInfo(null, "Voided Voucher", "Cannot edit a voided/reversed payment voucher.");
            return;
        }
        if (current == null || current.id() == null || !current.id().equals(selected.id())) {
            current = paymentService.getPaymentDetail(selected.id()).map(PaymentDetailDto::payment).orElse(null);
        }
        if (current == null) return;

        Window owner = tblPayments != null && tblPayments.getScene() != null ? tblPayments.getScene().getWindow() : null;
        ComboBox<String> cmbMethod = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(
                "CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER")));
        cmbMethod.setValue(current.paymentMethod());

        TextField txtRef = new TextField(current.referenceTrxId() != null ? current.referenceTrxId() : "");
        txtRef.setPromptText("Transaction ID / Check #");

        TextField txtNotes = new TextField(current.notes() != null ? current.notes() : "");
        txtNotes.setPromptText("Optional remarks");

        VBox lockBanner = DialogComponents.tintedSectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_LOCK, "Locked Amount: " + BengaliMoneyFormatter.format(current.amount()),
                        "Payment amount remains immutable to preserve FIFO allocations and party ledger integrity.")
        );

        VBox formCard = DialogComponents.sectionCard(
                DialogComponents.formField("Payment Method", true, null, cmbMethod),
                DialogComponents.formField("Transaction Reference", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_RECEIPT, txtRef)),
                DialogComponents.formField("Remarks / Notes", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
        );

        VBox body = new VBox(14.0, lockBanner, formCard);

        SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Edit Payment Metadata (" + current.paymentNumber() + ")")
                .subtitle("Update payment method, transaction reference, or remarks.")
                .icon(DialogComponents.SVG_EDIT, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(cmbMethod)
                .primaryAction("Update Voucher", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    paymentService.updatePaymentDetails(selected.id(), cmbMethod.getValue(), txtRef.getText(), txtNotes.getText());
                    return Boolean.TRUE;
                })
                .showAndWait()
                .ifPresent(ok -> {
                    loadPayments();
                    loadPaymentDetails(selected.id());
                    AlertHelper.showInfo(owner, "Payment Updated", "Metadata updated for " + selected.paymentNumber() + ".");
                });
    }

    @FXML
    public void onDeleteVoidedPayment() {
        PaymentListItemDto selected = tblPayments.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblPayments != null && tblPayments.getScene() != null ? tblPayments.getScene().getWindow() : null;
        if (!SessionContext.isOwner()) {
            AlertHelper.showWarning(owner, "Permission Denied", "Only the Business Owner can delete voided payments.");
            return;
        }
        if (!selected.isReversed()) {
            AlertHelper.showWarning(owner, "Active Voucher", "Void this payment voucher first before deleting it.");
            return;
        }

        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Voided Payment (OWNER)",
                "Permanently delete voided payment " + selected.paymentNumber() + "?",
                "This removes the voided voucher, FIFO allocations, and balanced ledger adjustment entries permanently.",
                "Delete Permanently"
        );
        if (confirmed) {
            try {
                paymentService.deleteVoidedPayment(selected.id());
                loadPayments();
                DashboardController dc = ViewRouter.getDashboardController();
                if (dc != null) {
                    dc.refreshDashboard();
                }
                AlertHelper.showInfo(owner, "Voided Payment Deleted",
                        "Voided payment " + selected.paymentNumber() + " deleted permanently.");
            } catch (Exception ex) {
                log.error("Failed to delete voided payment: {}", ex.getMessage(), ex);
                AlertHelper.showWarning(owner, "Delete Failed", ex.getMessage());
            }
        }
    }

    @FXML
    public void onReceivePayment() {
        openPaymentModal("CUSTOMER", "Customer Due Collection (গ্রাহক বাকি আদায়)", "#008A32");
    }

    @FXML
    public void onMakePayment() {
        openPaymentModal("SUPPLIER", "Supplier Disbursement (মহাজন দেনা পরিশোধ)", "#008A32");
    }

    private void openPaymentModal(String partyType, String title, String themeColor) {
        long businessId = SessionContext.getRequiredBusinessId();
        Window owner = tblPayments != null && tblPayments.getScene() != null ? tblPayments.getScene().getWindow() : null;

        ComboBox<PartyOption> cmbParty = DialogComponents.styleCombo(new ComboBox<>());
        cmbParty.setPromptText(partyType.equals("CUSTOMER") ? "Select customer with outstanding due..." : "Select supplier with outstanding payable...");

        if ("CUSTOMER".equalsIgnoreCase(partyType)) {
            for (CustomerListItemDto c : customerService.searchCustomers(businessId, null, false)) {
                if (c.currentDue().isPositive()) {
                    cmbParty.getItems().add(new PartyOption(c.id(), c.name(), c.currentDue()));
                }
            }
        } else {
            for (SupplierListItemDto s : supplierService.searchSuppliers(businessId, null, false)) {
                if (s.currentPayable().isPositive()) {
                    cmbParty.getItems().add(new PartyOption(s.id(), s.name(), s.currentPayable()));
                }
            }
        }

        if (cmbParty.getItems().isEmpty()) {
            AlertHelper.showInfo(owner, "No Outstanding Balances",
                    "CUSTOMER".equalsIgnoreCase(partyType)
                            ? "All customers currently have zero outstanding dues."
                            : "All suppliers currently have zero outstanding payables.");
            return;
        }

        Label lblDueBadge = DialogComponents.statusBadge("Outstanding Due: BDT 0.00", DialogComponents.Tone.WARNING);
        TextField txtAmount = new TextField();
        txtAmount.setPromptText("Enter payment amount (BDT)");

        Button btnPayFull = new Button("Pay Full Due");
        btnPayFull.getStyleClass().add("sk-dlg-btn-subtle-green");
        btnPayFull.setPrefHeight(40.0);
        btnPayFull.setOnAction(e -> {
            if (cmbParty.getValue() != null) {
                txtAmount.setText(cmbParty.getValue().currentBalance().toBdt().toPlainString());
            }
        });

        cmbParty.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                lblDueBadge.setText("Due: " + BengaliMoneyFormatter.format(newVal.currentBalance()));
                txtAmount.setText(newVal.currentBalance().toBdt().toPlainString());
            }
        });
        cmbParty.getSelectionModel().selectFirst();

        ComboBox<String> cmbMethod = DialogComponents.styleCombo(
                new ComboBox<>(FXCollections.observableArrayList("CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER")));
        cmbMethod.setValue("CASH");

        TextField txtTrxRef = new TextField();
        txtTrxRef.setPromptText("Optional bank check # / transaction ID");

        TextField txtNotes = new TextField();
        txtNotes.setPromptText("Optional settlement notes");

        HBox partyHeader = new HBox(10.0,
                DialogComponents.sectionHeader(DialogComponents.SVG_WALLET,
                        partyType.equals("CUSTOMER") ? "Customer Selection" : "Supplier Selection",
                        "Standalone settlement applies automatically using FIFO invoice/bill matching."),
                new Region(),
                lblDueBadge
        );
        HBox.setHgrow(partyHeader.getChildren().get(1), Priority.ALWAYS);
        partyHeader.setAlignment(Pos.CENTER_LEFT);

        VBox partyCard = DialogComponents.sectionCard(
                partyHeader,
                DialogComponents.formField(partyType.equals("CUSTOMER") ? "Customer (গ্রাহক)" : "Supplier (মহাজন)", true, null, cmbParty)
        );

        HBox amountRow = new HBox(10.0, DialogComponents.currencyFieldWrap(txtAmount), btnPayFull);
        HBox.setHgrow(amountRow.getChildren().get(0), Priority.ALWAYS);
        amountRow.setAlignment(Pos.CENTER_LEFT);

        VBox detailsCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Payment & Reference Details",
                        "Partial or full balance disbursement recorded cleanly to ledger."),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Payment Amount (BDT)", true, null, amountRow),
                        DialogComponents.formField("Payment Method", true, null, cmbMethod),
                        DialogComponents.formField("Trx Reference", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_RECEIPT, txtTrxRef)),
                        DialogComponents.formField("Notes", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
                )
        );

        VBox body = new VBox(14.0, partyCard, detailsCard);

        Optional<Payment> result = SmartKhataDialog.<Payment>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title(title)
                .subtitle("Record payment voucher, perform FIFO settlement, and update party ledger.")
                .icon(DialogComponents.SVG_WALLET, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(txtAmount)
                .primaryAction("Confirm Payment", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    PartyOption selectedParty = cmbParty.getValue();
                    if (selectedParty == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Please select a party with an outstanding balance.", cmbParty);
                    }
                    Money amt = DialogComponents.parsePositiveMoney(txtAmount.getText(), "Payment Amount");
                    if (amt.compareTo(selectedParty.currentBalance()) > 0) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Payment amount (" + BengaliMoneyFormatter.format(amt) +
                                        ") exceeds outstanding due (" + BengaliMoneyFormatter.format(selectedParty.currentBalance()) + ").",
                                txtAmount
                        );
                    }
                    long actingUserId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                    PaymentCreateDto dto = new PaymentCreateDto(
                            businessId,
                            partyType,
                            selectedParty.id(),
                            amt,
                            cmbMethod.getValue(),
                            txtTrxRef.getText(),
                            txtNotes.getText(),
                            UUID.randomUUID().toString(),
                            actingUserId
                    );
                    return "CUSTOMER".equalsIgnoreCase(partyType)
                            ? paymentService.receiveCustomerPayment(dto)
                            : paymentService.makeSupplierPayment(dto);
                })
                .showAndWait();

        result.ifPresent(p -> {
            AlertHelper.showInfo(owner, "Payment Posted",
                    "Voucher " + p.paymentNumber() + " recorded successfully!\nInvoices settled and ledger updated.");
            loadPayments();
        });
    }

    @FXML
    public void onPrintVoucher() {
        PaymentListItemDto selected = tblPayments.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblPayments != null && tblPayments.getScene() != null ? tblPayments.getScene().getWindow() : null;

        Optional<PaymentDetailDto> opt = paymentService.getPaymentDetail(selected.id());
        if (opt.isEmpty()) return;
        PaymentDetailDto detail = opt.get();
        Payment p = detail.payment();

        StringBuilder sb = new StringBuilder();
        sb.append("=========================================\n");
        sb.append("         SMARTKHATA PAYMENT VOUCHER      \n");
        sb.append("=========================================\n");
        sb.append("Voucher No  : ").append(p.paymentNumber()).append("\n");
        sb.append("Date        : ").append(p.paymentDate().format(DATE_FMT)).append("\n");
        sb.append("Party Type  : ").append(p.partyType()).append("\n");
        sb.append("Party Name  : ").append(detail.partyName()).append("\n");
        sb.append("Phone       : ").append(detail.partyPhone()).append("\n");
        sb.append("Method      : ").append(p.paymentMethod()).append("\n");
        if (p.referenceTrxId() != null && !p.referenceTrxId().isBlank()) {
            sb.append("Trx Ref     : ").append(p.referenceTrxId()).append("\n");
        }
        sb.append("Amount      : ").append(BengaliMoneyFormatter.format(p.amount())).append("\n");
        sb.append("-----------------------------------------\n");
        sb.append("Allocated Documents (FIFO Settlement):\n");
        sb.append("-----------------------------------------\n");
        for (PaymentAllocationDto alloc : detail.allocations()) {
            String doc = alloc.saleId() != null ? "Invoice " + alloc.invoiceNumber() : "Bill " + alloc.purchaseNumber();
            sb.append(String.format("%-26s %14s\n", doc, BengaliMoneyFormatter.format(alloc.allocatedAmount())));
        }
        sb.append("-----------------------------------------\n");
        sb.append("Issued By   : ").append(detail.createdByName()).append("\n");
        sb.append("=========================================\n");

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        textArea.setPrefRowCount(16);
        textArea.setPrefColumnCount(42);

        SmartKhataDialog.<Void>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Payment Voucher Preview (টাকা রসিদ)")
                .subtitle("Payment Voucher: " + p.paymentNumber())
                .icon(DialogComponents.SVG_PRINT, DialogComponents.Tone.SOFT_GREEN)
                .content(DialogComponents.sectionCard(textArea))
                .infoOnly(true)
                .primaryAction("Close", DialogComponents.SVG_CLOSE, SmartKhataDialog.ActionTone.PRIMARY)
                .showAndWait();
    }

    @FXML
    public void onVoidPayment() {
        PaymentListItemDto selected = tblPayments.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblPayments != null && tblPayments.getScene() != null ? tblPayments.getScene().getWindow() : null;

        AlertHelper.promptInput(
                owner,
                "Void Payment Voucher (পেমেন্ট বাতিল)",
                "Voiding " + selected.paymentNumber() + " reverses FIFO invoice allocations and restores party dues.",
                "Mandatory Reversal Reason",
                "",
                "Enter audit reason for reversal...",
                true,
                "Void Voucher"
        ).ifPresent(reason -> {
            try {
                long actingUserId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                paymentService.voidPayment(selected.id(), reason.trim(), actingUserId);
                AlertHelper.showInfo(owner, "Payment Voided", "Payment " + selected.paymentNumber() + " has been voided successfully.");
                loadPayments();
            } catch (Exception ex) {
                log.error("Failed to void payment: {}", ex.getMessage(), ex);
                AlertHelper.showStartupError(owner, "Void Failed", "Failed to void payment: " + ex.getMessage(), ex);
            }
        });
    }
}

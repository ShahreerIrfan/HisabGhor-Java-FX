package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Expense;
import com.smartkhata.model.entity.OtherIncome;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.ExpenseService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteExpenseService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class ExpensesController {

    private static final Logger log = LoggerFactory.getLogger(ExpensesController.class);
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ExpenseService expenseService;

    // Header Summary
    @FXML private Label lblTotalExpenses;
    @FXML private Label lblTotalOtherIncome;
    @FXML private Label lblNetFlow;
    @FXML private Button btnRecordExpense;
    @FXML private Button btnRecordOtherIncome;

    @FXML private TabPane tabPane;

    // Tab 1: Expenses
    @FXML private TextField txtExpenseSearch;
    @FXML private ComboBox<String> cmbExpenseCategory;
    @FXML private ComboBox<String> cmbExpenseMethod;
    @FXML private DatePicker dpExpenseStartDate;
    @FXML private DatePicker dpExpenseEndDate;
    @FXML private Button btnResetExpenseFilter;

    @FXML private TableView<Expense> tblExpenses;
    @FXML private TableColumn<Expense, String> colExpDate;
    @FXML private TableColumn<Expense, String> colExpCategory;
    @FXML private TableColumn<Expense, String> colExpPayee;
    @FXML private TableColumn<Expense, String> colExpMethod;
    @FXML private TableColumn<Expense, String> colExpAmount;
    @FXML private TableColumn<Expense, String> colExpNotes;
    @FXML private TableColumn<Expense, String> colExpStatus;
    @FXML private TableColumn<Expense, Void> colExpActions;

    // Tab 2: Other Income
    @FXML private TextField txtIncomeSearch;
    @FXML private DatePicker dpIncomeStartDate;
    @FXML private DatePicker dpIncomeEndDate;
    @FXML private Button btnResetIncomeFilter;

    @FXML private TableView<OtherIncome> tblOtherIncomes;
    @FXML private TableColumn<OtherIncome, String> colIncDate;
    @FXML private TableColumn<OtherIncome, String> colIncSource;
    @FXML private TableColumn<OtherIncome, String> colIncMethod;
    @FXML private TableColumn<OtherIncome, String> colIncAmount;
    @FXML private TableColumn<OtherIncome, String> colIncNotes;
    @FXML private TableColumn<OtherIncome, String> colIncStatus;
    @FXML private TableColumn<OtherIncome, Void> colIncActions;

    // Tab 3: Breakdown
    @FXML private TableView<ExpenseCategorySummaryDto> tblCategoryBreakdown;
    @FXML private TableColumn<ExpenseCategorySummaryDto, String> colBreakdownCategory;
    @FXML private TableColumn<ExpenseCategorySummaryDto, String> colBreakdownCount;
    @FXML private TableColumn<ExpenseCategorySummaryDto, String> colBreakdownTotal;

    private final ObservableList<Expense> expenseList = FXCollections.observableArrayList();
    private final ObservableList<OtherIncome> otherIncomeList = FXCollections.observableArrayList();
    private final ObservableList<ExpenseCategorySummaryDto> breakdownList = FXCollections.observableArrayList();

    public ExpensesController() {
        this(new SqliteExpenseService());
    }

    public ExpensesController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @FXML
    public void initialize() {
        log.info("Initializing ExpensesController...");

        setupExpenseTable();
        setupOtherIncomeTable();
        setupBreakdownTable();
        populateFilterCombos();

        refreshAll();
    }

    private void setupExpenseTable() {
        colExpDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().expenseDate().format(DATE_TIME_FMT)));
        colExpCategory.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().category()));
        colExpPayee.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().payeeName() != null ? d.getValue().payeeName() : "—"));
        colExpMethod.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().paymentMethod()));
        colExpAmount.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().amount())));
        colExpNotes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().notes() != null ? d.getValue().notes() : "—"));

        colExpStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().isReversed() ? "VOIDED" : "POSTED"));
        colExpStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if ("POSTED".equalsIgnoreCase(status)) {
                        setStyle("-fx-text-fill: #15803D; -fx-font-weight: bold; -fx-background-color: #DCFCE7; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else {
                        setStyle("-fx-text-fill: #B91C1C; -fx-font-weight: bold; -fx-background-color: #FEE2E2; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    }
                }
            }
        });

        colExpActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnEdit = new Button("Edit");
            private final Button btnVoid = new Button("Void");
            private final Button btnDelete = new Button("Delete");
            private final HBox activeBox = new HBox(6.0, btnEdit, btnVoid);
            private final HBox voidedBox = new HBox(6.0, btnDelete);
            {
                activeBox.setAlignment(Pos.CENTER);
                voidedBox.setAlignment(Pos.CENTER);
                btnEdit.setStyle("-fx-background-color: #DBEAFE; -fx-text-fill: #1D4ED8; -fx-font-size: 11px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4; -fx-padding: 3 8;");
                btnVoid.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #B45309; -fx-font-size: 11px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4; -fx-padding: 3 8;");
                btnDelete.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #DC2626; -fx-font-size: 11px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4; -fx-padding: 3 8;");

                btnEdit.setOnAction(e -> {
                    Expense item = getTableView().getItems().get(getIndex());
                    onEditExpense(item);
                });
                btnVoid.setOnAction(e -> {
                    Expense item = getTableView().getItems().get(getIndex());
                    onVoidExpense(item);
                });
                btnDelete.setOnAction(e -> {
                    Expense item = getTableView().getItems().get(getIndex());
                    onDeleteVoidedExpense(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    Expense exp = getTableView().getItems().get(getIndex());
                    if (exp.isReversed()) {
                        User currentUser = SessionContext.getCurrentUser();
                        boolean isOwner = currentUser == null || currentUser.role().isOwner();
                        btnDelete.setDisable(!isOwner);
                        btnDelete.setTooltip(new Tooltip(isOwner
                                ? "Delete voided voucher"
                                : "Owner permission required to delete voided expenses"));
                        setGraphic(voidedBox);
                    } else {
                        setGraphic(activeBox);
                    }
                    setAlignment(Pos.CENTER);
                }
            }
        });

        tblExpenses.setItems(expenseList);
    }

    private void setupOtherIncomeTable() {
        colIncDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().incomeDate().format(DATE_TIME_FMT)));
        colIncSource.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().sourceName()));
        colIncMethod.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().paymentMethod()));
        colIncAmount.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().amount())));
        colIncNotes.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().notes() != null ? d.getValue().notes() : "—"));

        colIncStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().isReversed() ? "VOIDED" : "POSTED"));
        colIncStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if ("POSTED".equalsIgnoreCase(status)) {
                        setStyle("-fx-text-fill: #15803D; -fx-font-weight: bold; -fx-background-color: #DCFCE7; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    } else {
                        setStyle("-fx-text-fill: #B91C1C; -fx-font-weight: bold; -fx-background-color: #FEE2E2; -fx-alignment: center; -fx-background-radius: 4; -fx-padding: 3 8;");
                    }
                }
            }
        });

        colIncActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnEdit = new Button("Edit");
            private final Button btnVoid = new Button("Void");
            private final Button btnDelete = new Button("Delete");
            private final HBox activeBox = new HBox(6.0, btnEdit, btnVoid);
            private final HBox voidedBox = new HBox(6.0, btnDelete);
            {
                activeBox.setAlignment(Pos.CENTER);
                voidedBox.setAlignment(Pos.CENTER);
                btnEdit.setStyle("-fx-background-color: #DBEAFE; -fx-text-fill: #1D4ED8; -fx-font-size: 11px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4; -fx-padding: 3 8;");
                btnVoid.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #B45309; -fx-font-size: 11px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4; -fx-padding: 3 8;");
                btnDelete.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #DC2626; -fx-font-size: 11px; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4; -fx-padding: 3 8;");

                btnEdit.setOnAction(e -> {
                    OtherIncome item = getTableView().getItems().get(getIndex());
                    onEditOtherIncome(item);
                });
                btnVoid.setOnAction(e -> {
                    OtherIncome item = getTableView().getItems().get(getIndex());
                    onVoidOtherIncome(item);
                });
                btnDelete.setOnAction(e -> {
                    OtherIncome item = getTableView().getItems().get(getIndex());
                    onDeleteVoidedOtherIncome(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    OtherIncome inc = getTableView().getItems().get(getIndex());
                    if (inc.isReversed()) {
                        User currentUser = SessionContext.getCurrentUser();
                        boolean isOwner = currentUser == null || currentUser.role().isOwner();
                        btnDelete.setDisable(!isOwner);
                        btnDelete.setTooltip(new Tooltip(isOwner
                                ? "Delete voided voucher"
                                : "Owner permission required to delete voided income entries"));
                        setGraphic(voidedBox);
                    } else {
                        setGraphic(activeBox);
                    }
                    setAlignment(Pos.CENTER);
                }
            }
        });

        tblOtherIncomes.setItems(otherIncomeList);
    }

    private void setupBreakdownTable() {
        colBreakdownCategory.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().category()));
        colBreakdownCount.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().count())));
        colBreakdownTotal.setCellValueFactory(d -> new SimpleStringProperty(BengaliMoneyFormatter.format(d.getValue().totalAmount())));

        tblCategoryBreakdown.setItems(breakdownList);
    }

    private void populateFilterCombos() {
        cmbExpenseCategory.setItems(FXCollections.observableArrayList(
                "All Categories", "RENT", "UTILITIES", "SALARY", "TRANSPORT", "ENTERTAINMENT", "MAINTENANCE", "OTHER"
        ));
        cmbExpenseCategory.setValue("All Categories");

        cmbExpenseMethod.setItems(FXCollections.observableArrayList(
                "All Methods", "CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER"
        ));
        cmbExpenseMethod.setValue("All Methods");
    }

    public void refreshAll() {
        if (!SessionContext.isAuthenticated()) {
            return;
        }
        loadExpenses();
        loadOtherIncomes();
        loadSummary();
    }

    private void loadExpenses() {
        long businessId = SessionContext.getRequiredBusinessId();
        String query = txtExpenseSearch != null ? txtExpenseSearch.getText() : null;
        String cat = (cmbExpenseCategory != null && !"All Categories".equalsIgnoreCase(cmbExpenseCategory.getValue()))
                ? cmbExpenseCategory.getValue() : null;
        String method = (cmbExpenseMethod != null && !"All Methods".equalsIgnoreCase(cmbExpenseMethod.getValue()))
                ? cmbExpenseMethod.getValue() : null;
        LocalDate start = dpExpenseStartDate != null ? dpExpenseStartDate.getValue() : null;
        LocalDate end = dpExpenseEndDate != null ? dpExpenseEndDate.getValue() : null;

        List<Expense> expenses = expenseService.searchExpenses(businessId, query, cat, method, start, end);
        expenseList.setAll(expenses);
    }

    private void loadOtherIncomes() {
        long businessId = SessionContext.getRequiredBusinessId();
        String query = txtIncomeSearch != null ? txtIncomeSearch.getText() : null;
        LocalDate start = dpIncomeStartDate != null ? dpIncomeStartDate.getValue() : null;
        LocalDate end = dpIncomeEndDate != null ? dpIncomeEndDate.getValue() : null;

        List<OtherIncome> incomes = expenseService.searchOtherIncomes(businessId, query, start, end);
        otherIncomeList.setAll(incomes);
    }

    private void loadSummary() {
        long businessId = SessionContext.getRequiredBusinessId();
        CashFlowSummaryDto summary = expenseService.getCashFlowSummary(businessId, null, null);

        lblTotalExpenses.setText(BengaliMoneyFormatter.format(summary.totalExpenses()));
        lblTotalOtherIncome.setText(BengaliMoneyFormatter.format(summary.totalOtherIncome()));
        lblNetFlow.setText(BengaliMoneyFormatter.format(summary.netAuxiliaryFlow()));

        List<ExpenseCategorySummaryDto> breakdown = expenseService.getExpenseBreakdownByCategory(businessId, null, null);
        breakdownList.setAll(breakdown);
    }

    @FXML
    public void onRecordExpense() {
        showExpenseModal(null);
    }

    @FXML
    public void onRecordOtherIncome() {
        showOtherIncomeModal(null);
    }

    private void onEditExpense(Expense existing) {
        showExpenseModal(existing);
    }

    private void onEditOtherIncome(OtherIncome existing) {
        showOtherIncomeModal(existing);
    }

    private void showExpenseModal(Expense existing) {
        boolean isEdit = existing != null;
        Window owner = tblExpenses != null && tblExpenses.getScene() != null ? tblExpenses.getScene().getWindow() : null;

        TextField txtAmount = new TextField(isEdit ? existing.amount().toBdt().toPlainString() : "");
        txtAmount.setPromptText("e.g. 500.00");

        ComboBox<String> cmbCat = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(
                "RENT", "UTILITIES", "SALARY", "TRANSPORT", "ENTERTAINMENT", "MAINTENANCE", "OTHER")));
        cmbCat.setValue(isEdit ? existing.category() : "UTILITIES");

        ComboBox<String> cmbMethod = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(
                "CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER")));
        cmbMethod.setValue(isEdit ? existing.paymentMethod() : "CASH");

        DatePicker dpDate = DialogComponents.styleDatePicker(new DatePicker(
                isEdit && existing.expenseDate() != null ? existing.expenseDate().toLocalDate() : LocalDate.now()));

        TextField txtPayee = new TextField(isEdit && existing.payeeName() != null ? existing.payeeName() : "");
        txtPayee.setPromptText("Optional payee or vendor name");

        TextField txtNotes = new TextField(isEdit && existing.notes() != null ? existing.notes() : "");
        txtNotes.setPromptText("Optional expense description / notes");

        VBox coreCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Expense Classification & Amount",
                        "Categorize operating expense for accurate net Profit & Loss reports."),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Amount (BDT)", true, null, DialogComponents.currencyFieldWrap(txtAmount)),
                        DialogComponents.formField("Category", true, null, cmbCat),
                        DialogComponents.formField("Payment Method", true, null, cmbMethod),
                        DialogComponents.formField("Expense Date", true, null, dpDate)
                )
        );

        VBox notesCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_NOTE, "Payee & Remarks",
                        "Optional payee reference and audit description."),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Payee Name", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_USER, txtPayee)),
                        DialogComponents.formField("Notes", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
                )
        );

        VBox body = new VBox(14.0, coreCard, notesCard);

        SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title(isEdit ? "Edit Expense (খরচ সংশোধন)" : "Record Expense (ব্যবসায়িক খরচ)")
                .subtitle(isEdit ? "Update expense voucher details (#" + existing.id() + ")" : "Log an operational cost with category and payment method.")
                .icon(DialogComponents.SVG_COINS, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(txtAmount)
                .primaryAction(isEdit ? "Update Expense" : "Save Expense", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    Money amount = DialogComponents.parsePositiveMoney(txtAmount.getText(), "Amount");
                    LocalDate selDate = dpDate.getValue() != null ? dpDate.getValue() : LocalDate.now();
                    if (isEdit) {
                        expenseService.updateExpense(new ExpenseUpdateDto(
                                existing.id(),
                                cmbCat.getValue(),
                                amount,
                                LocalDateTime.of(selDate, existing.expenseDate().toLocalTime()),
                                cmbMethod.getValue(),
                                txtPayee.getText(),
                                txtNotes.getText()
                        ));
                    } else {
                        long businessId = SessionContext.getRequiredBusinessId();
                        long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                        expenseService.recordExpense(new ExpenseCreateDto(
                                businessId, cmbCat.getValue(), amount, selDate.atStartOfDay(),
                                cmbMethod.getValue(), txtPayee.getText(), txtNotes.getText(), userId
                        ));
                    }
                    return Boolean.TRUE;
                })
                .showAndWait()
                .ifPresent(ok -> refreshAll());
    }

    private void showOtherIncomeModal(OtherIncome existing) {
        boolean isEdit = existing != null;
        Window owner = tblOtherIncomes != null && tblOtherIncomes.getScene() != null ? tblOtherIncomes.getScene().getWindow() : null;

        TextField txtSource = new TextField(isEdit ? existing.sourceName() : "");
        txtSource.setPromptText("e.g. Scrap Carton Sale, Service Fee");

        TextField txtAmount = new TextField(isEdit ? existing.amount().toBdt().toPlainString() : "");
        txtAmount.setPromptText("e.g. 1000.00");

        ComboBox<String> cmbMethod = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(
                "CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER")));
        cmbMethod.setValue(isEdit ? existing.paymentMethod() : "CASH");

        DatePicker dpDate = DialogComponents.styleDatePicker(new DatePicker(
                isEdit && existing.incomeDate() != null ? existing.incomeDate().toLocalDate() : LocalDate.now()));

        TextField txtNotes = new TextField(isEdit && existing.notes() != null ? existing.notes() : "");
        txtNotes.setPromptText("Optional receipt details");

        VBox incomeCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_WALLET, "Other Income Details",
                        "Log non-sale auxiliary business receipts separately from POS revenue."),
                DialogComponents.formField("Source Name", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, txtSource)),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Amount (BDT)", true, null, DialogComponents.currencyFieldWrap(txtAmount)),
                        DialogComponents.formField("Payment Method", true, null, cmbMethod),
                        DialogComponents.formField("Receipt Date", true, null, dpDate),
                        DialogComponents.formField("Notes", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
                )
        );

        SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title(isEdit ? "Edit Other Income (অন্যান্য আয় সংশোধন)" : "Record Other Income (অন্যান্য আয়)")
                .subtitle(isEdit ? "Update other income voucher details (#" + existing.id() + ")" : "Log non-sale business income cleanly.")
                .icon(DialogComponents.SVG_WALLET, DialogComponents.Tone.SOFT_GREEN)
                .content(incomeCard)
                .initialFocus(txtSource)
                .primaryAction(isEdit ? "Update Income" : "Save Income", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String src = txtSource.getText() != null ? txtSource.getText().trim() : "";
                    if (src.isBlank()) {
                        throw new SmartKhataDialog.ValidationFocusException("Source Name is required.", txtSource);
                    }
                    Money amount = DialogComponents.parsePositiveMoney(txtAmount.getText(), "Amount");
                    LocalDate selDate = dpDate.getValue() != null ? dpDate.getValue() : LocalDate.now();
                    if (isEdit) {
                        expenseService.updateOtherIncome(new OtherIncomeUpdateDto(
                                existing.id(), src, amount,
                                LocalDateTime.of(selDate, existing.incomeDate().toLocalTime()),
                                cmbMethod.getValue(), txtNotes.getText()
                        ));
                    } else {
                        long businessId = SessionContext.getRequiredBusinessId();
                        long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                        expenseService.recordOtherIncome(new OtherIncomeCreateDto(
                                businessId, src, amount, selDate.atStartOfDay(),
                                cmbMethod.getValue(), txtNotes.getText(), userId
                        ));
                    }
                    return Boolean.TRUE;
                })
                .showAndWait()
                .ifPresent(ok -> refreshAll());
    }

    private void onVoidExpense(Expense expense) {
        Window owner = tblExpenses != null && tblExpenses.getScene() != null ? tblExpenses.getScene().getWindow() : null;
        AlertHelper.promptInput(
                owner,
                "Void Expense (খরচ বাতিল)",
                "Voiding expense: " + expense.category() + " — " + BengaliMoneyFormatter.format(expense.amount()),
                "Audit Reason for Cancellation",
                "",
                "Enter mandatory reversal reason...",
                true,
                "Void Expense"
        ).ifPresent(reason -> {
            try {
                long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                expenseService.voidExpense(expense.id(), reason.trim(), userId);
                refreshAll();
            } catch (Exception ex) {
                log.error("Failed to void expense: {}", ex.getMessage(), ex);
                showErrorAlert("Void Error", "Could not void expense: " + ex.getMessage());
            }
        });
    }

    private void onVoidOtherIncome(OtherIncome income) {
        Window owner = tblOtherIncomes != null && tblOtherIncomes.getScene() != null ? tblOtherIncomes.getScene().getWindow() : null;
        AlertHelper.promptInput(
                owner,
                "Void Other Income (আয় বাতিল)",
                "Voiding receipt: " + income.sourceName() + " — " + BengaliMoneyFormatter.format(income.amount()),
                "Audit Reason for Cancellation",
                "",
                "Enter mandatory reversal reason...",
                true,
                "Void Income"
        ).ifPresent(reason -> {
            try {
                long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                expenseService.voidOtherIncome(income.id(), reason.trim(), userId);
                refreshAll();
            } catch (Exception ex) {
                log.error("Failed to void other income: {}", ex.getMessage(), ex);
                showErrorAlert("Void Error", "Could not void other income: " + ex.getMessage());
            }
        });
    }

    private void onDeleteVoidedExpense(Expense expense) {
        Window owner = tblExpenses != null && tblExpenses.getScene() != null ? tblExpenses.getScene().getWindow() : null;
        boolean ok = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Voided Expense",
                "Permanently delete voided expense (" + expense.category() + " — " + BengaliMoneyFormatter.format(expense.amount()) + ")?",
                "This permanently removes the voided voucher record.",
                "Delete Permanently"
        );
        if (ok) {
            try {
                expenseService.deleteVoidedExpense(expense.id());
                refreshAll();
            } catch (Exception ex) {
                showErrorAlert("Delete Error", ex.getMessage());
            }
        }
    }

    private void onDeleteVoidedOtherIncome(OtherIncome income) {
        Window owner = tblOtherIncomes != null && tblOtherIncomes.getScene() != null ? tblOtherIncomes.getScene().getWindow() : null;
        boolean ok = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Voided Income",
                "Permanently delete voided income (" + income.sourceName() + " — " + BengaliMoneyFormatter.format(income.amount()) + ")?",
                "This permanently removes the voided income record.",
                "Delete Permanently"
        );
        if (ok) {
            try {
                expenseService.deleteVoidedOtherIncome(income.id());
                refreshAll();
            } catch (Exception ex) {
                showErrorAlert("Delete Error", ex.getMessage());
            }
        }
    }

    @FXML
    public void onExpenseSearchKeyReleased() {
        loadExpenses();
    }

    @FXML
    public void onExpenseFilterChanged() {
        loadExpenses();
    }

    @FXML
    public void onResetExpenseFilter() {
        if (txtExpenseSearch != null) txtExpenseSearch.clear();
        if (cmbExpenseCategory != null) cmbExpenseCategory.setValue("All Categories");
        if (cmbExpenseMethod != null) cmbExpenseMethod.setValue("All Methods");
        if (dpExpenseStartDate != null) dpExpenseStartDate.setValue(null);
        if (dpExpenseEndDate != null) dpExpenseEndDate.setValue(null);
        loadExpenses();
    }

    @FXML
    public void onIncomeSearchKeyReleased() {
        loadOtherIncomes();
    }

    @FXML
    public void onIncomeFilterChanged() {
        loadOtherIncomes();
    }

    @FXML
    public void onResetIncomeFilter() {
        if (txtIncomeSearch != null) txtIncomeSearch.clear();
        if (dpIncomeStartDate != null) dpIncomeStartDate.setValue(null);
        if (dpIncomeEndDate != null) dpIncomeEndDate.setValue(null);
        loadOtherIncomes();
    }

    private void showErrorAlert(String title, String message) {
        Window owner = tblExpenses != null && tblExpenses.getScene() != null ? tblExpenses.getScene().getWindow() : null;
        AlertHelper.showError(owner, title, message);
    }
}

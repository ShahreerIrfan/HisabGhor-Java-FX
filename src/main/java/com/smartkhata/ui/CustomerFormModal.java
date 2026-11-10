package com.smartkhata.ui;

import com.smartkhata.model.dto.CustomerCreateDto;
import com.smartkhata.model.dto.CustomerUpdateDto;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.vo.Money;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.function.Function;

/**
 * Unified floating modal dialog for adding, quick-adding (from POS), and editing Customers.
 * Built on the SmartKhata Global Dialog Design System (Medium size, organized section cards,
 * inline validation, keyboard navigation, and locked historical opening balance on edit).
 */
public final class CustomerFormModal {

    private CustomerFormModal() {}

    public static Optional<Customer> showAddDialog(
            Window owner,
            long businessId,
            Function<CustomerCreateDto, Customer> onSave
    ) {
        return showCreateInternal(
                owner,
                businessId,
                "Add New Customer (নতুন গ্রাহক)",
                "Register a customer with contact details, credit limit, and verified opening due.",
                "Save Customer",
                true,
                "",
                onSave
        );
    }

    public static Optional<Customer> showQuickAddDialog(
            Window owner,
            long businessId,
            Function<CustomerCreateDto, Customer> onSave
    ) {
        return showCreateInternal(
                owner,
                businessId,
                "Quick Add Customer (নতুন গ্রাহক)",
                "Add a registered customer for immediate POS billing or Baki (credit) sales.",
                "Save & Select",
                false,
                "Added from POS Counter",
                onSave
        );
    }

    public static Optional<Customer> showEditDialog(
            Window owner,
            Customer existing,
            Function<CustomerUpdateDto, Customer> onUpdate
    ) {
        return buildEditDialog(owner, existing, onUpdate).showAndWait();
    }

    static SmartKhataDialog.Builder<Customer> buildEditDialog(
            Window owner,
            Customer existing,
            Function<CustomerUpdateDto, Customer> onUpdate
    ) {
        FormControls c = createControls(existing, true);
        VBox body = buildFormBody(c, true, true);

        return SmartKhataDialog.<Customer>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Edit Customer (গ্রাহক তথ্য পরিবর্তন)")
                .subtitle("Update contact details and credit limits for " + existing.name() + ".")
                .icon(DialogComponents.SVG_USER, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(c.txtName())
                .primaryAction("Update Customer", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .asyncSubmit(true, "Updating...")
                .onSubmit(() -> {
                    String name = validateName(c.txtName());
                    Money creditLimit = parseMoneyField(c.txtCreditLimit(), "Credit Limit");
                    return onUpdate.apply(new CustomerUpdateDto(
                            existing.id(),
                            name,
                            DialogComponents.nonNull(c.txtPhone().getText()).trim(),
                            DialogComponents.nonNull(c.txtAddress().getText()).trim(),
                            creditLimit,
                            DialogComponents.nonNull(c.txtNotes().getText()).trim()
                    ));
                });
    }

    private static Optional<Customer> showCreateInternal(
            Window owner,
            long businessId,
            String title,
            String subtitle,
            String submitLabel,
            boolean showOpeningDue,
            String defaultNotes,
            Function<CustomerCreateDto, Customer> onSave
    ) {
        return buildCreateDialog(
                owner, businessId, title, subtitle, submitLabel, showOpeningDue, defaultNotes, onSave
        ).showAndWait();
    }

    static SmartKhataDialog.Builder<Customer> buildCreateDialog(
            Window owner,
            long businessId,
            String title,
            String subtitle,
            String submitLabel,
            boolean showOpeningDue,
            String defaultNotes,
            Function<CustomerCreateDto, Customer> onSave
    ) {
        FormControls c = createControls(null, showOpeningDue);
        if (defaultNotes != null && !defaultNotes.isBlank()) {
            c.txtNotes().setText(defaultNotes);
        }
        VBox body = buildFormBody(c, false, showOpeningDue);

        return SmartKhataDialog.<Customer>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title(title)
                .subtitle(subtitle)
                .icon(DialogComponents.SVG_USER, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(c.txtName())
                .primaryAction(submitLabel, DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .asyncSubmit(true, "Saving...")
                .onSubmit(() -> {
                    String name = validateName(c.txtName());
                    Money creditLimit = parseMoneyField(c.txtCreditLimit(), "Credit Limit");
                    Money openingDue = showOpeningDue ? parseMoneyField(c.txtOpeningDue(), "Opening Due") : Money.ZERO;
                    return onSave.apply(new CustomerCreateDto(
                            businessId,
                            name,
                            DialogComponents.nonNull(c.txtPhone().getText()).trim(),
                            DialogComponents.nonNull(c.txtAddress().getText()).trim(),
                            creditLimit,
                            openingDue,
                            DialogComponents.nonNull(c.txtNotes().getText()).trim()
                    ));
                });
    }

    static FormControls createControls(Customer existing, boolean includeOpening) {
        boolean isEdit = existing != null;
        TextField txtName = new TextField(isEdit ? DialogComponents.nonNull(existing.name()) : "");
        txtName.setPromptText("e.g. Rahim Traders");

        TextField txtPhone = new TextField(isEdit ? DialogComponents.nonNull(existing.phone()) : "");
        txtPhone.setPromptText("e.g. 017XXXXXXXX");

        TextField txtAddress = new TextField(isEdit ? DialogComponents.nonNull(existing.address()) : "");
        txtAddress.setPromptText("e.g. Shop 12, New Market, Dhaka");

        TextField txtLimit = new TextField(isEdit && existing.creditLimit().isPositive()
                ? existing.creditLimit().toBdt().toPlainString() : "");
        txtLimit.setPromptText("e.g. 50000 (optional)");

        TextField txtOpening = new TextField(isEdit
                ? BengaliMoneyFormatter.format(existing.openingBalance()) : "");
        txtOpening.setPromptText("e.g. 15000 (past due)");
        if (isEdit) {
            txtOpening.setEditable(false);
            txtOpening.setFocusTraversable(false);
        }

        TextArea txtNotes = new TextArea(isEdit ? DialogComponents.nonNull(existing.notes()) : "");
        txtNotes.setPromptText("Optional credit terms, delivery preferences, or notes...");
        txtNotes.setPrefRowCount(2);

        return new FormControls(txtName, txtPhone, txtAddress, txtLimit, txtOpening, txtNotes, includeOpening);
    }

    static VBox buildFormBody(FormControls c, boolean isEdit, boolean showOpening) {
        VBox infoCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_USER, "Customer & Contact Information",
                        "Primary identification and communication details."),
                DialogComponents.formField("Customer Name", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_USER, c.txtName())),
                DialogComponents.responsiveTwoColGrid(
                        520.0,
                        DialogComponents.formField("Phone Number", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_PHONE, c.txtPhone())),
                        DialogComponents.formField("Address", false, null,
                                DialogComponents.textFieldWrap(DialogComponents.SVG_PIN, c.txtAddress()))
                )
        );

        Node creditField = DialogComponents.formField(
                "Credit Limit (BDT)", false, "Maximum allowed unpaid balance (0 = unlimited).",
                DialogComponents.currencyFieldWrap(c.txtCreditLimit())
        );

        VBox creditCard;
        if (showOpening) {
            String pillText = isEdit
                    ? "Opening balance is permanently locked in the customer ledger."
                    : "Existing past due carried forward into customer ledger.";
            HBox infoPill = new HBox(7.0,
                    DialogComponents.iconBox(DialogComponents.SVG_INFO, "#008A32", 14.0),
                    styledNote(pillText));
            infoPill.getStyleClass().add("sk-modal-info-pill");
            infoPill.setAlignment(Pos.CENTER_LEFT);

            VBox openingField = new VBox(6.0,
                    DialogComponents.formField("Opening Due (BDT)", false, null,
                            DialogComponents.currencyFieldWrap(c.txtOpeningDue())),
                    infoPill
            );

            creditCard = DialogComponents.sectionCard(
                    DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Credit & Opening Balance",
                            "Control Baki limits and verified opening receivable."),
                    DialogComponents.responsiveTwoColGrid(520.0, creditField, openingField)
            );
        } else {
            creditCard = DialogComponents.sectionCard(
                    DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Credit Settings",
                            "Set optional Baki limit for this customer."),
                    creditField
            );
        }

        // Smooth Tab navigation out of Notes textarea
        c.txtNotes().addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.TAB) {
                ev.consume();
                if (ev.isShiftDown()) {
                    c.txtCreditLimit().requestFocus();
                } else {
                    c.txtNotes().getParent().requestFocus();
                }
            }
        });

        VBox notesCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_NOTE, "Notes",
                        "Internal billing remarks or payment preferences (optional)."),
                DialogComponents.textAreaWrap(c.txtNotes(), 72.0)
        );

        return new VBox(14.0, infoCard, creditCard, notesCard);
    }

    private static Label styledNote(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: #075C32;");
        return l;
    }

    private static String validateName(TextField field) {
        String name = field.getText() != null ? field.getText().trim() : "";
        if (name.isBlank()) {
            throw new SmartKhataDialog.ValidationFocusException("Customer Name is required.", field);
        }
        return name;
    }

    private static Money parseMoneyField(TextField field, String label) {
        try {
            return DialogComponents.parseNonNegativeMoney(field.getText(), label);
        } catch (IllegalArgumentException ex) {
            throw new SmartKhataDialog.ValidationFocusException(ex.getMessage(), field);
        }
    }

    record FormControls(
            TextField txtName,
            TextField txtPhone,
            TextField txtAddress,
            TextField txtCreditLimit,
            TextField txtOpeningDue,
            TextArea txtNotes,
            boolean includeOpening
    ) {}
}

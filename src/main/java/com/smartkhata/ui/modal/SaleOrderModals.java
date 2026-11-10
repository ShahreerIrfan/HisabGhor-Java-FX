package com.smartkhata.ui.modal;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.Sale;
import com.smartkhata.model.entity.SaleItem;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SaleService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.ViewRouter;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shared POS Order Details, Edit Order, Return Items, Void, Delete, and Receipt modals
 * reused cleanly across both Sales &amp; POS and Reports screens.
 */
public final class SaleOrderModals {

    private static final Logger log = LoggerFactory.getLogger(SaleOrderModals.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private enum DetailModalAction {
        NONE, PRINT, EDIT, RETURN, VOID, DELETE
    }

    private record CustomerChoice(Long id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class EditableOrderLine {
        final long productId;
        final String productName;
        final Money unitCost;
        int qty;
        Money unitPrice;

        EditableOrderLine(long productId, String productName, Money unitCost, int qty, Money unitPrice) {
            this.productId = productId;
            this.productName = productName;
            this.unitCost = unitCost != null ? unitCost : Money.ZERO;
            this.qty = Math.max(1, qty);
            this.unitPrice = unitPrice != null ? unitPrice : Money.ZERO;
        }

        Money lineTotal() {
            return unitPrice.multiply(qty);
        }
    }

    private SaleOrderModals() {}

    public static void showOrderDetailsModal(Window owner, SaleListItemDto item,
                                             SaleService saleService,
                                             CustomerService customerService,
                                             InventoryService inventoryService,
                                             Runnable onAfterMutation) {
        if (item == null) return;

        Optional<SaleDetailDto> opt = saleService.getSaleDetail(item.id());
        if (opt.isEmpty()) {
            AlertHelper.showError(owner, "Order Not Found", "Could not load details for invoice " + item.invoiceNumber() + ".");
            return;
        }
        SaleDetailDto detail = opt.get();
        Sale sale = detail.sale();
        String payMethod = saleService.findPrimaryPaymentMethod(sale.id())
                .orElse(sale.paidAmount().isPositive() ? "CASH" : "UNPAID");

        final DetailModalAction[] requestedAction = { DetailModalAction.PRINT };

        Label lblCustName = new Label(detail.customerName());
        lblCustName.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        Label lblCustMeta = new Label("Phone: " + detail.customerPhone() + "  |  Address: " + detail.customerAddress());
        lblCustMeta.setWrapText(true);
        lblCustMeta.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #475569;");
        Label lblNote = new Label("Note: " + (sale.notes() != null && !sale.notes().isBlank() ? sale.notes().trim() : "—"));
        lblNote.setWrapText(true);
        lblNote.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B; -fx-font-style: italic;");

        VBox customerCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_USER, "Customer & Invoice Info",
                        "Payment Method: " + payMethod + "  •  Cashier: " + detail.cashierName()),
                lblCustName, lblCustMeta, lblNote
        );
        HBox.setHgrow(customerCard, Priority.ALWAYS);

        Label lblNetVal = new Label(formatPosBdt(sale.netAmount()));
        lblNetVal.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #008A32;");
        Label lblSubDisc = new Label("Subtotal: " + formatPosBdt(sale.totalAmount())
                + (sale.discount().isPositive() ? "   |   Discount: -" + formatPosBdt(sale.discount()) : ""));
        lblSubDisc.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #475569;");
        Label lblPaidDue = new Label("Paid: " + formatPosBdt(sale.paidAmount())
                + "   |   Due: " + formatPosBdt(sale.dueAmount()));
        lblPaidDue.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: "
                + (sale.dueAmount().isPositive() ? "#DC2626;" : "#15803D;"));
        Label statusBadge = DialogComponents.statusBadge(
                sale.isReversed() ? "VOIDED / REVERSED" : "STATUS: " + sale.paymentStatus(),
                sale.isReversed() ? DialogComponents.Tone.DANGER
                        : (sale.dueAmount().isPositive() ? DialogComponents.Tone.WARNING : DialogComponents.Tone.SUCCESS)
        );

        VBox summaryCard = DialogComponents.tintedSectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Settlement Breakdown",
                        "Net bill, payment received & outstanding due"),
                lblNetVal, lblSubDisc, lblPaidDue, statusBadge
        );
        HBox.setHgrow(summaryCard, Priority.ALWAYS);

        HBox topRow = new HBox(12.0, customerCard, summaryCard);

        TableView<SaleItemDto> detailTable = new TableView<>(FXCollections.observableArrayList(detail.items()));
        detailTable.setPrefHeight(200.0);
        detailTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        detailTable.getStyleClass().addAll("table-view", "compact-table");

        TableColumn<SaleItemDto, String> cProd = new TableColumn<>("Product");
        cProd.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().productName()));
        cProd.setPrefWidth(220.0);

        TableColumn<SaleItemDto, String> cQty = new TableColumn<>("Qty");
        cQty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().quantity())));
        cQty.setPrefWidth(70.0);

        TableColumn<SaleItemDto, String> cPrice = new TableColumn<>("Unit Price");
        cPrice.setCellValueFactory(c -> new SimpleStringProperty(BengaliMoneyFormatter.format(c.getValue().unitPrice())));
        cPrice.setPrefWidth(100.0);

        TableColumn<SaleItemDto, String> cDisc = new TableColumn<>("Discount");
        cDisc.setCellValueFactory(c -> new SimpleStringProperty(BengaliMoneyFormatter.format(c.getValue().discount())));
        cDisc.setPrefWidth(90.0);

        TableColumn<SaleItemDto, String> cTot = new TableColumn<>("Line Total");
        cTot.setCellValueFactory(c -> new SimpleStringProperty(BengaliMoneyFormatter.format(c.getValue().lineTotal())));
        cTot.setPrefWidth(110.0);

        detailTable.getColumns().addAll(cProd, cQty, cPrice, cDisc, cTot);

        VBox itemsCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE,
                        "Ordered Items (" + detail.items().size() + ")",
                        "Itemized bill quantities, unit prices & net line totals"),
                detailTable
        );

        Button btnModalEdit = new Button("Edit Order");
        btnModalEdit.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_EDIT, "#1D4ED8", 13.0));
        btnModalEdit.getStyleClass().add("pos-row-action-edit");
        btnModalEdit.setStyle("-fx-padding: 7px 14px; -fx-font-size: 12px;");
        btnModalEdit.setDisable(sale.isReversed());
        btnModalEdit.setOnAction(e -> {
            requestedAction[0] = DetailModalAction.EDIT;
            ((Stage) btnModalEdit.getScene().getWindow()).close();
        });

        Button btnModalReturn = new Button("Return Items");
        btnModalReturn.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_RETURN, "#92400E", 13.0));
        btnModalReturn.setStyle("-fx-background-color: #FEF3C7; -fx-border-color: #FDE68A; -fx-border-width: 1px; -fx-border-radius: 7px; -fx-background-radius: 7px; -fx-text-fill: #92400E; -fx-font-weight: 700; -fx-padding: 7px 14px; -fx-font-size: 12px; -fx-cursor: hand;");
        btnModalReturn.setDisable(sale.isReversed());
        btnModalReturn.setOnAction(e -> {
            requestedAction[0] = DetailModalAction.RETURN;
            ((Stage) btnModalReturn.getScene().getWindow()).close();
        });

        Button btnModalVoid = new Button("Void Sale");
        btnModalVoid.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_ARCHIVE, "#475569", 13.0));
        btnModalVoid.getStyleClass().add("btn-archive-soft");
        btnModalVoid.setStyle("-fx-padding: 7px 14px; -fx-font-size: 12px;");
        btnModalVoid.setDisable(sale.isReversed());
        btnModalVoid.setOnAction(e -> {
            requestedAction[0] = DetailModalAction.VOID;
            ((Stage) btnModalVoid.getScene().getWindow()).close();
        });

        Button btnModalDelete = new Button("Delete Order");
        btnModalDelete.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_TRASH, "#DC2626", 13.0));
        btnModalDelete.getStyleClass().add("pos-row-action-delete");
        btnModalDelete.setStyle("-fx-padding: 7px 14px; -fx-font-size: 12px;");
        btnModalDelete.setOnAction(e -> {
            requestedAction[0] = DetailModalAction.DELETE;
            ((Stage) btnModalDelete.getScene().getWindow()).close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionBar = new HBox(8.0, btnModalEdit, btnModalReturn, btnModalVoid, spacer, btnModalDelete);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.setStyle("-fx-padding: 4px 0 0 0;");

        VBox content = new VBox(12.0, topRow, itemsCard, actionBar);
        if (sale.isReversed()) {
            Label voidBanner = new Label("VOIDED REASON: "
                    + (sale.reversalReason() != null && !sale.reversalReason().isBlank() ? sale.reversalReason() : "Reversed invoice"));
            voidBanner.setWrapText(true);
            voidBanner.setMaxWidth(Double.MAX_VALUE);
            voidBanner.setStyle("-fx-background-color: #FEF2F2; -fx-text-fill: #991B1B; -fx-font-weight: 700; -fx-padding: 8 12; -fx-background-radius: 8; -fx-border-color: #FCA5A5; -fx-border-radius: 8;");
            content.getChildren().add(0, voidBanner);
        }

        Optional<DetailModalAction> result = SmartKhataDialog.<DetailModalAction>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title("POS Order Details — " + sale.invoiceNumber())
                .subtitle("Created on " + sale.invoiceDate().format(DATE_FMT) + "  •  Cashier: " + detail.cashierName())
                .icon(DialogComponents.SVG_RECEIPT, DialogComponents.Tone.PRIMARY)
                .greenHeaderAccent(true)
                .content(content)
                .cancelLabel("Close")
                .primaryAction("Print Receipt", DialogComponents.SVG_PRINT, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> DetailModalAction.PRINT)
                .showAndWait();

        DetailModalAction finalAction = result.orElse(requestedAction[0] == DetailModalAction.PRINT ? DetailModalAction.NONE : requestedAction[0]);
        switch (finalAction) {
            case PRINT -> showReceiptPreviewModal(owner, buildPersistedReceiptText(detail), "Invoice " + sale.invoiceNumber());
            case EDIT -> showEditOrderModal(owner, item, saleService, customerService, inventoryService, onAfterMutation);
            case RETURN -> showReturnSaleItemsModal(owner, item, saleService, inventoryService, onAfterMutation);
            case VOID -> showVoidSaleModal(owner, item, saleService, onAfterMutation);
            case DELETE -> confirmAndDeleteOrder(owner, item, saleService, onAfterMutation);
            default -> {}
        }
    }

    public static void showEditOrderModal(Window owner, SaleListItemDto item,
                                          SaleService saleService,
                                          CustomerService customerService,
                                          InventoryService inventoryService,
                                          Runnable onAfterMutation) {
        if (item == null) return;
        if (item.isReversed()) {
            AlertHelper.showInfo(owner, "Voided Invoice", "Voided invoices cannot be edited. Use View/Details or Delete Order instead.");
            return;
        }

        Optional<SaleDetailDto> opt = saleService.getSaleDetail(item.id());
        if (opt.isEmpty()) {
            AlertHelper.showError(owner, "Order Not Found", "Could not load order " + item.invoiceNumber() + ".");
            return;
        }
        SaleDetailDto detail = opt.get();
        Sale sale = detail.sale();
        String existingMethod = saleService.findPrimaryPaymentMethod(sale.id()).orElse("CASH");

        long businessId = SessionContext.getCurrentBusiness() != null
                ? SessionContext.getCurrentBusiness().id() : sale.businessId();
        List<CustomerListItemDto> customers = customerService.searchCustomers(businessId, null, false);
        List<ProductListItemDto> catalog = inventoryService.searchProducts(businessId, null, null, false);

        List<EditableOrderLine> editLines = new ArrayList<>();
        for (SaleItemDto si : detail.items()) {
            editLines.add(new EditableOrderLine(
                    si.productId(), si.productName(), si.unitCost(), si.quantity(), si.unitPrice()
            ));
        }

        ComboBox<CustomerChoice> cmbCustomer = DialogComponents.styleCombo(new ComboBox<>());
        CustomerChoice walkIn = new CustomerChoice(null, "Walk-in Cash Customer");
        cmbCustomer.getItems().add(walkIn);
        CustomerChoice initialCust = walkIn;
        for (CustomerListItemDto c : customers) {
            String display = c.name() + (c.phone() != null && !c.phone().isBlank() ? " (" + c.phone() + ")" : "");
            CustomerChoice co = new CustomerChoice(c.id(), display);
            cmbCustomer.getItems().add(co);
            if (sale.customerId() != null && sale.customerId().equals(c.id())) {
                initialCust = co;
            }
        }
        cmbCustomer.getSelectionModel().select(initialCust);

        ToggleGroup payGroup = new ToggleGroup();
        HBox payMethodsRow = new HBox(8.0);
        for (String method : List.of("CASH", "BKASH", "NAGAD", "BANK")) {
            ToggleButton tb = new ToggleButton(method);
            tb.setToggleGroup(payGroup);
            tb.getStyleClass().add("sk-dlg-pill-toggle");
            if (method.equalsIgnoreCase(existingMethod)) {
                tb.setSelected(true);
            }
            payMethodsRow.getChildren().add(tb);
        }
        if (payGroup.getSelectedToggle() == null && !payMethodsRow.getChildren().isEmpty()) {
            ((ToggleButton) payMethodsRow.getChildren().get(0)).setSelected(true);
        }
        payGroup.selectedToggleProperty().addListener((o, prev, next) -> {
            if (next == null && prev != null) prev.setSelected(true);
        });

        TextField txtDiscount = new TextField(formatPlainDecimal(sale.discount()));
        TextField txtPaid = new TextField(formatPlainDecimal(sale.paidAmount()));
        TextField txtNotes = new TextField(sale.notes() != null ? sale.notes() : "");
        txtNotes.setPromptText("Optional order notes...");

        Label lblSubtotal = new Label();
        lblSubtotal.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #1E293B;");
        Label lblNetPayable = new Label();
        lblNetPayable.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: #008A32;");
        Label lblDueStatus = new Label();
        lblDueStatus.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700;");

        VBox linesRowsBox = new VBox(8.0);
        ScrollPane linesScroll = new ScrollPane(linesRowsBox);
        linesScroll.setFitToWidth(true);
        linesScroll.setPrefHeight(200.0);
        linesScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        Runnable[] recalcRef = new Runnable[1];
        Runnable[] rebuildRowsRef = new Runnable[1];

        recalcRef[0] = () -> {
            Money sub = Money.ZERO;
            for (EditableOrderLine l : editLines) {
                sub = sub.add(l.lineTotal());
            }
            Money disc = parseNonNegativeMoney(txtDiscount.getText(), Money.ZERO);
            if (disc.compareTo(sub) > 0) disc = sub;
            Money net = sub.subtract(disc);
            Money paid = parseNonNegativeMoney(txtPaid.getText(), Money.ZERO);
            if (paid.compareTo(net) > 0) paid = net;
            Money due = net.subtract(paid);

            lblSubtotal.setText(formatPosBdt(sub));
            lblNetPayable.setText(formatPosBdt(net));
            if (due.isZero()) {
                lblDueStatus.setText("Status: PAID IN FULL");
                lblDueStatus.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #15803D;");
            } else if (paid.isZero()) {
                lblDueStatus.setText("Status: FULL BAKI DUE (" + formatPosBdt(due) + ")");
                lblDueStatus.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
            } else {
                lblDueStatus.setText("Status: PARTIAL  •  Due: " + formatPosBdt(due));
                lblDueStatus.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #D97706;");
            }
        };

        rebuildRowsRef[0] = () -> {
            linesRowsBox.getChildren().clear();
            for (EditableOrderLine line : editLines) {
                Label lblName = new Label(line.productName);
                lblName.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #0F172A;");
                lblName.setMaxWidth(Double.MAX_VALUE);

                Button btnMinus = new Button("-");
                btnMinus.getStyleClass().add("pos-qty-btn");
                TextField txtQty = new TextField(String.valueOf(line.qty));
                txtQty.setPrefWidth(48.0);
                txtQty.setAlignment(Pos.CENTER);
                txtQty.setStyle("-fx-font-weight: 700; -fx-font-size: 12px;");
                Button btnPlus = new Button("+");
                btnPlus.getStyleClass().add("pos-qty-btn");

                TextField txtPrice = new TextField(formatPlainDecimal(line.unitPrice));
                txtPrice.setPrefWidth(88.0);
                txtPrice.setAlignment(Pos.CENTER_RIGHT);

                Label lblTotal = new Label(formatPosBdt(line.lineTotal()));
                lblTotal.setPrefWidth(104.0);
                lblTotal.setAlignment(Pos.CENTER_RIGHT);
                lblTotal.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");

                btnMinus.setOnAction(e -> {
                    if (line.qty > 1) {
                        line.qty--;
                        txtQty.setText(String.valueOf(line.qty));
                        lblTotal.setText(formatPosBdt(line.lineTotal()));
                        recalcRef[0].run();
                    }
                });
                btnPlus.setOnAction(e -> {
                    line.qty++;
                    txtQty.setText(String.valueOf(line.qty));
                    lblTotal.setText(formatPosBdt(line.lineTotal()));
                    recalcRef[0].run();
                });
                txtQty.textProperty().addListener((o, a, b) -> {
                    try {
                        int q = Integer.parseInt(b != null ? b.trim() : "");
                        if (q > 0) {
                            line.qty = q;
                            lblTotal.setText(formatPosBdt(line.lineTotal()));
                            recalcRef[0].run();
                        }
                    } catch (NumberFormatException ignored) {}
                });
                txtPrice.textProperty().addListener((o, a, b) -> {
                    Money p = parseNonNegativeMoney(b, null);
                    if (p != null) {
                        line.unitPrice = p;
                        lblTotal.setText(formatPosBdt(line.lineTotal()));
                        recalcRef[0].run();
                    }
                });

                Button btnRemoveLine = new Button();
                btnRemoveLine.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_TRASH, "#DC2626", 13.0));
                btnRemoveLine.getStyleClass().add("pos-row-delete-btn");
                btnRemoveLine.setDisable(editLines.size() <= 1);
                btnRemoveLine.setOnAction(e -> {
                    if (editLines.size() > 1) {
                        editLines.remove(line);
                        rebuildRowsRef[0].run();
                        recalcRef[0].run();
                    }
                });

                VBox nameCol = new VBox(2.0, lblName, new Label("Cost: " + formatPosBdt(line.unitCost)));
                HBox.setHgrow(nameCol, Priority.ALWAYS);
                HBox rowBox = new HBox(10.0,
                        nameCol,
                        new HBox(4.0, btnMinus, txtQty, btnPlus),
                        txtPrice,
                        lblTotal,
                        btnRemoveLine
                );
                rowBox.setAlignment(Pos.CENTER_LEFT);
                rowBox.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #E2E8F0; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-padding: 8px 10px;");
                linesRowsBox.getChildren().add(rowBox);
            }
        };

        ComboBox<ProductListItemDto> cmbAddProduct = DialogComponents.styleCombo(new ComboBox<>());
        cmbAddProduct.setPromptText("Select product to add to this order...");
        cmbAddProduct.getItems().addAll(catalog);
        cmbAddProduct.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ProductListItemDto p) {
                return p == null ? "" : p.name() + " (" + BengaliMoneyFormatter.format(p.sellingPrice()) + ", Stock: " + p.currentStock() + ")";
            }
            @Override public ProductListItemDto fromString(String s) { return null; }
        });
        HBox.setHgrow(cmbAddProduct, Priority.ALWAYS);
        Button btnAddLine = new Button("Add Product");
        btnAddLine.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_PLUS, "#075C32", 13.0));
        btnAddLine.getStyleClass().add("pos-row-action-details");
        btnAddLine.setStyle("-fx-padding: 7px 12px; -fx-font-size: 12px;");
        btnAddLine.setOnAction(e -> {
            ProductListItemDto chosen = cmbAddProduct.getValue();
            if (chosen == null) return;
            EditableOrderLine existingLine = editLines.stream()
                    .filter(l -> l.productId == chosen.id())
                    .findFirst().orElse(null);
            if (existingLine != null) {
                existingLine.qty++;
            } else {
                editLines.add(new EditableOrderLine(
                        chosen.id(), chosen.name(), chosen.costPrice(), 1, chosen.sellingPrice()
                ));
            }
            rebuildRowsRef[0].run();
            recalcRef[0].run();
        });

        txtDiscount.textProperty().addListener((o, a, b) -> recalcRef[0].run());
        txtPaid.textProperty().addListener((o, a, b) -> recalcRef[0].run());

        Button btnExactPaid = new Button("Exact Full Paid");
        btnExactPaid.getStyleClass().add("sk-dlg-btn-subtle-green");
        btnExactPaid.setOnAction(e -> {
            Money sub = Money.ZERO;
            for (EditableOrderLine l : editLines) sub = sub.add(l.lineTotal());
            Money disc = parseNonNegativeMoney(txtDiscount.getText(), Money.ZERO);
            if (disc.compareTo(sub) > 0) disc = sub;
            txtPaid.setText(formatPlainDecimal(sub.subtract(disc)));
        });

        rebuildRowsRef[0].run();
        recalcRef[0].run();

        VBox custFieldBox = DialogComponents.formField("Customer", false, null, cmbCustomer);
        HBox.setHgrow(custFieldBox, Priority.ALWAYS);
        VBox payMethodFieldBox = DialogComponents.formField("Payment Method", true, null, payMethodsRow);
        HBox.setHgrow(payMethodFieldBox, Priority.ALWAYS);

        VBox headerCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_USER, "Customer, Payment Method & Notes",
                        "Adjust customer assignment, settlement method, or order notes"),
                new HBox(14.0, custFieldBox, payMethodFieldBox),
                DialogComponents.formField("Invoice Note", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
        );

        VBox itemsCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "Order Line Items",
                        "Edit quantities or selling unit prices, or add more products"),
                linesScroll,
                new HBox(8.0, cmbAddProduct, btnAddLine)
        );

        Region summarySpacer = new Region();
        HBox.setHgrow(summarySpacer, Priority.ALWAYS);
        HBox settlementSummaryRow = new HBox(10.0,
                new Label("Subtotal:"), lblSubtotal,
                summarySpacer,
                new Label("Net Payable:"), lblNetPayable,
                btnExactPaid
        );
        settlementSummaryRow.setAlignment(Pos.CENTER_LEFT);

        VBox discountFieldBox = DialogComponents.formField("Invoice Discount (BDT)", false, null,
                DialogComponents.unitFieldWrap(DialogComponents.SVG_TAG, txtDiscount, "BDT"));
        HBox.setHgrow(discountFieldBox, Priority.ALWAYS);
        VBox paidFieldBox = DialogComponents.formField("Paid Amount (BDT)", true, null,
                DialogComponents.unitFieldWrap(DialogComponents.SVG_WALLET, txtPaid, "BDT"));
        HBox.setHgrow(paidFieldBox, Priority.ALWAYS);

        VBox settlementCard = DialogComponents.tintedSectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_COINS, "Totals & Payment Settlement",
                        "Recalculates net bill, customer ledger balance & inventory stock automatically"),
                new HBox(14.0, discountFieldBox, paidFieldBox),
                settlementSummaryRow,
                lblDueStatus
        );

        VBox formRoot = new VBox(12.0, headerCard, itemsCard, settlementCard);

        Optional<Sale> updatedOpt = SmartKhataDialog.<Sale>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title("Edit POS Order — " + sale.invoiceNumber())
                .subtitle("Update items, customer, payment & notes with atomic stock and ledger adjustment.")
                .icon(DialogComponents.SVG_EDIT, DialogComponents.Tone.PRIMARY)
                .greenHeaderAccent(true)
                .content(formRoot)
                .primaryAction("Save Order Changes", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    Money sub = Money.ZERO;
                    List<SaleItemDto> dtoItems = new ArrayList<>();
                    for (EditableOrderLine l : editLines) {
                        Money lineTot = l.unitPrice.multiply(l.qty);
                        sub = sub.add(lineTot);
                        dtoItems.add(new SaleItemDto(
                                l.productId, l.productName, l.qty, l.unitPrice, l.unitCost, Money.ZERO, lineTot
                        ));
                    }
                    Money disc = parseNonNegativeMoney(txtDiscount.getText(), null);
                    if (disc == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Enter a valid non-negative discount.", txtDiscount);
                    }
                    if (disc.compareTo(sub) > 0) {
                        throw new SmartKhataDialog.ValidationFocusException("Discount cannot exceed subtotal (" + formatPosBdt(sub) + ").", txtDiscount);
                    }
                    Money net = sub.subtract(disc);
                    Money paid = parseNonNegativeMoney(txtPaid.getText(), null);
                    if (paid == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Enter a valid non-negative paid amount.", txtPaid);
                    }
                    if (paid.compareTo(net) > 0) {
                        paid = net;
                    }
                    CustomerChoice chosenCust = cmbCustomer.getValue();
                    Long custId = chosenCust != null ? chosenCust.id() : null;
                    if (custId == null && net.subtract(paid).isPositive()) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Credit balance (Baki) requires selecting a registered customer.", cmbCustomer);
                    }
                    String method = payGroup.getSelectedToggle() instanceof ToggleButton tb ? tb.getText() : "CASH";
                    long actingUser = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : sale.createdByUserId();
                    try {
                        return saleService.updateSaleOrder(new SaleUpdateDto(
                                sale.id(), businessId, custId, dtoItems, disc, paid, method,
                                txtNotes.getText() != null ? txtNotes.getText().trim() : "", actingUser
                        ));
                    } catch (RuntimeException ex) {
                        if (ex instanceof SmartKhataDialog.ValidationFocusException vex) throw vex;
                        throw new SmartKhataDialog.ValidationFocusException(
                                ex.getMessage() != null ? ex.getMessage() : "Could not update POS order.", txtPaid);
                    }
                })
                .showAndWait();

        updatedOpt.ifPresent(updated -> {
            if (onAfterMutation != null) {
                onAfterMutation.run();
            }
            ViewRouter.refreshSalesImpactViews();
            AlertHelper.showInfo(owner, "POS Order Updated",
                    "Invoice " + updated.invoiceNumber() + " updated cleanly (Net: " + formatPosBdt(updated.netAmount()) + ").");
        });
    }

    public static void confirmAndDeleteOrder(Window owner, SaleListItemDto item,
                                             SaleService saleService,
                                             Runnable onAfterMutation) {
        if (item == null) return;
        if (!SessionContext.isOwner()) {
            AlertHelper.showError(owner, "Permission Denied", "Only the Business Owner can permanently delete POS orders.");
            return;
        }
        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete POS Order — " + item.invoiceNumber(),
                "Permanently delete order " + item.invoiceNumber() + " (" + BengaliMoneyFormatter.format(item.netAmount()) + ")?",
                "This atomically restores sold product stock, removes auto-created POS payment receipts, and recalculates customer ledger balances.",
                "Delete Order"
        );
        if (!confirmed) return;

        try {
            saleService.deleteSaleOrder(item.id());
            if (onAfterMutation != null) {
                onAfterMutation.run();
            }
            ViewRouter.refreshSalesImpactViews();
            AlertHelper.showInfo(owner, "POS Order Deleted",
                    "Order " + item.invoiceNumber() + " has been deleted and stock/balances restored.");
        } catch (Exception ex) {
            log.error("Failed to delete sale order [{}]: {}", item.invoiceNumber(), ex.getMessage(), ex);
            AlertHelper.showError(owner, "Delete Failed", ex.getMessage() != null ? ex.getMessage() : "Unable to delete order.");
        }
    }

    public static void showReturnSaleItemsModal(Window owner, SaleListItemDto selected,
                                                SaleService saleService,
                                                InventoryService inventoryService,
                                                Runnable onAfterMutation) {
        if (selected == null || selected.isReversed()) return;

        List<SaleItem> items;
        try (Connection conn = com.smartkhata.database.DatabaseManager.getConnection()) {
            items = new com.smartkhata.dao.SqliteSaleDao().findItemsBySaleId(conn, selected.id());
        } catch (Exception ex) {
            AlertHelper.showError(owner, "Return Error", ex.getMessage());
            return;
        }
        List<SaleItem> returnable = items.stream().filter(i -> i.quantity() - i.returnedQty() > 0).toList();
        if (returnable.isEmpty()) {
            AlertHelper.showInfo(owner, "Nothing Returnable", "All quantities on this invoice have already been returned.");
            return;
        }

        ComboBox<SaleItem> cmbItem = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(returnable)));
        cmbItem.getSelectionModel().selectFirst();
        cmbItem.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SaleItem si) {
                if (si == null) return "";
                String name = inventoryService.getProductById(si.productId()).map(Product::name).orElse("Product #" + si.productId());
                return name + " (Remaining: " + (si.quantity() - si.returnedQty()) + ")";
            }
            @Override public SaleItem fromString(String s) { return null; }
        });
        TextField txtQty = new TextField("1");
        TextField txtReason = new TextField("Customer returned undamaged product");

        VBox returnCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_RETURN, "Return Item & Reason",
                        "Restores inventory via RETURN_IN and adjusts COGS without altering invoice history."),
                DialogComponents.formField("Sold Line Item", true, null, cmbItem),
                DialogComponents.formField("Return Quantity", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_CUBE, txtQty)),
                DialogComponents.formField("Return Reason", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtReason))
        );

        SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Sales Return — " + selected.invoiceNumber())
                .subtitle("Return sold item quantities to stock and update customer dues/COGS.")
                .icon(DialogComponents.SVG_RETURN, DialogComponents.Tone.WARNING)
                .content(returnCard)
                .initialFocus(txtQty)
                .primaryAction("Process Sales Return", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.WARNING)
                .onSubmit(() -> {
                    SaleItem si = cmbItem.getValue();
                    if (si == null) {
                        throw new IllegalArgumentException("Select a line item to return.");
                    }
                    int maxRem = si.quantity() - si.returnedQty();
                    int qty = DialogComponents.parseIntRange(txtQty.getText(), 0, 1, "Return Quantity");
                    if (qty > maxRem) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Cannot return " + qty + ". Maximum returnable remaining is " + maxRem + ".", txtQty);
                    }
                    String reason = txtReason.getText() != null ? txtReason.getText().trim() : "";
                    if (reason.isBlank()) {
                        throw new SmartKhataDialog.ValidationFocusException("Return Reason is required.", txtReason);
                    }
                    long uid = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                    saleService.createSaleReturn(new SaleReturnCreateDto(
                            SessionContext.getRequiredBusinessId(), selected.id(), reason, uid,
                            List.of(new SaleReturnItemCreateDto(si.id(), qty))
                    ));
                    return Boolean.TRUE;
                })
                .showAndWait()
                .ifPresent(ok -> {
                    if (onAfterMutation != null) onAfterMutation.run();
                    ViewRouter.refreshSalesImpactViews();
                    AlertHelper.showInfo(owner, "Sales Return Processed", "Stock restored via RETURN_IN and COGS adjusted.");
                });
    }

    public static void showVoidSaleModal(Window owner, SaleListItemDto selected,
                                         SaleService saleService,
                                         Runnable onAfterMutation) {
        if (selected == null) return;
        if (selected.isReversed()) {
            AlertHelper.showInfo(owner, "Already Voided", "This invoice has already been reversed.");
            return;
        }

        AlertHelper.promptInput(
                owner,
                "Void / Reverse Sale (চালান বাতিল)",
                "Voiding " + selected.invoiceNumber() + " returns all sold stock and reverses customer dues.",
                "Mandatory Reversal Reason",
                "",
                "Enter audit reason for voiding invoice...",
                true,
                "Void Invoice"
        ).ifPresent(reason -> {
            try {
                long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                saleService.voidSale(selected.id(), reason, userId);
                if (onAfterMutation != null) onAfterMutation.run();
                ViewRouter.refreshSalesImpactViews();
                AlertHelper.showInfo(owner, "Sale Voided",
                        "Invoice " + selected.invoiceNumber() + " has been voided. Stock and dues reversed.");
            } catch (Exception ex) {
                log.error("Failed to void sale: {}", ex.getMessage(), ex);
                AlertHelper.showError(owner, "Void Failed", ex.getMessage());
            }
        });
    }

    public static String buildPersistedReceiptText(SaleDetailDto detail) {
        Sale s = detail.sale();
        String shopName = SessionContext.getCurrentBusiness() != null
                ? SessionContext.getCurrentBusiness().name() : "SMARTKHATA POS";

        StringBuilder r = new StringBuilder();
        r.append("==================================================\n");
        r.append(centerLine(shopName.toUpperCase(Locale.ROOT), 50)).append("\n");
        r.append(centerLine("SmartKhata Retail POS Receipt", 50)).append("\n");
        r.append("==================================================\n");
        r.append("Invoice:   ").append(s.invoiceNumber()).append("\n");
        r.append("Date:      ").append(s.invoiceDate().format(DATE_FMT)).append("\n");
        r.append("Cashier:   ").append(detail.cashierName()).append("\n");
        r.append("Customer:  ").append(detail.customerName()).append("\n");
        if (detail.customerPhone() != null && !"—".equals(detail.customerPhone()) && !detail.customerPhone().isBlank()) {
            r.append("Phone:     ").append(detail.customerPhone()).append("\n");
        }
        r.append("--------------------------------------------------\n");
        r.append(String.format("%-24s %5s %9s %9s\n", "Item", "Qty", "Price", "Total"));
        r.append("--------------------------------------------------\n");

        for (SaleItemDto item : detail.items()) {
            String name = item.productName();
            if (name.length() > 24) name = name.substring(0, 21) + "...";
            r.append(String.format("%-24s %5d %9s %9s\n",
                    name, item.quantity(),
                    formatPlainDecimal(item.unitPrice()),
                    formatPlainDecimal(item.lineTotal())));
        }

        r.append("--------------------------------------------------\n");
        r.append(String.format("%-35s %14s\n", "Subtotal:", formatPosBdt(s.totalAmount())));
        if (s.discount().isPositive()) {
            r.append(String.format("%-35s %14s\n", "Discount:", "-" + formatPosBdt(s.discount())));
        }
        r.append(String.format("%-35s %14s\n", "Net Payable:", formatPosBdt(s.netAmount())));
        r.append(String.format("%-35s %14s\n", "Paid Amount:", formatPosBdt(s.paidAmount())));
        r.append(String.format("%-35s %14s\n", "Due (বাকি):", formatPosBdt(s.dueAmount())));
        r.append(String.format("%-35s %14s\n", "Status:", s.paymentStatus()));
        r.append("==================================================\n");
        r.append(centerLine("Thank You For Shopping With Us!", 50)).append("\n");
        r.append("==================================================\n");
        return r.toString();
    }

    public static void showReceiptPreviewModal(Window owner, String receiptText, String subtitle) {
        TextArea receiptArea = new TextArea(receiptText);
        receiptArea.setEditable(false);
        receiptArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        receiptArea.setPrefRowCount(20);
        receiptArea.setPrefColumnCount(48);

        Button btnCopy = new Button("Copy Receipt Text");
        btnCopy.getStyleClass().add("sk-dlg-btn-subtle-green");
        btnCopy.setOnAction(ev -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(receiptText);
            Clipboard.getSystemClipboard().setContent(content);
            btnCopy.setText("Copied!");
        });

        VBox box = DialogComponents.sectionCard(receiptArea, new HBox(btnCopy));
        SmartKhataDialog.<Void>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Thermal Receipt Preview (বিক্রয় রসিদ)")
                .subtitle(subtitle + " — 80mm / 58mm Thermal POS Format")
                .icon(DialogComponents.SVG_PRINT, DialogComponents.Tone.SOFT_GREEN)
                .content(box)
                .infoOnly(true)
                .primaryAction("Close", DialogComponents.SVG_CLOSE, SmartKhataDialog.ActionTone.PRIMARY)
                .showAndWait();
    }

    private static String centerLine(String text, int width) {
        if (text == null) return "";
        if (text.length() >= width) return text.substring(0, width);
        int leftPad = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, leftPad)) + text;
    }

    private static String formatPlainDecimal(Money money) {
        if (money == null) return "0.00";
        long absPaisha = Math.abs(money.paisha());
        long whole = absPaisha / 100;
        long frac = absPaisha % 100;
        return String.format(Locale.US, "%s%d.%02d", money.paisha() < 0 ? "-" : "", whole, frac);
    }

    private static String formatPosBdt(Money money) {
        if (money == null) return "BDT 0.00";
        long absPaisha = Math.abs(money.paisha());
        long whole = absPaisha / 100;
        long frac = absPaisha % 100;
        String prefix = money.paisha() < 0 ? "-BDT " : "BDT ";
        return prefix + BengaliMoneyFormatter.formatSouthAsianInteger(whole) + String.format(Locale.US, ".%02d", frac);
    }

    private static Money parseNonNegativeMoney(String input, Money fallback) {
        if (input == null || input.trim().isBlank()) return fallback;
        try {
            BigDecimal bd = new BigDecimal(input.trim().replaceAll("[, ]", ""));
            if (bd.signum() < 0) return fallback;
            return Money.ofBdt(bd);
        } catch (Exception e) {
            return fallback;
        }
    }
}


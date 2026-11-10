package com.smartkhata.ui;

import com.smartkhata.model.dto.SupplierCreateDto;
import com.smartkhata.model.dto.SupplierUpdateDto;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.vo.Money;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.icon.AppIconManager;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Custom floating grocery-green modal dialog for adding and editing suppliers.
 * Uses vector SVG iconography, two-column form layout, inline validation, and translucent
 * backdrop overlay aligned with SmartKhata's Suppliers screen design.
 */
public final class SupplierFormModal {

    private static final String SVG_SHOP = "M20 4H4v2h16V4zm1 10v-2l-1-5H4l-1 5v2h1v6h10v-6h4v6h2v-6h1zm-9 4H6v-4h6v4z";
    private static final String SVG_USER = "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z";
    private static final String SVG_PHONE = "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z";
    private static final String SVG_COINS = "M12 3C7.58 3 4 4.79 4 7v10c0 2.21 3.58 4 8 4s8-1.79 8-4V7c0-2.21-3.58-4-8-4zm0 2c3.87 0 6 1.5 6 2s-2.13 2-6 2-6-1.5-6-2 2.13-2 6-2zm6 12c0 .5-2.13 2-6 2s-6-1.5-6-2v-2.23c1.61.78 3.72 1.23 6 1.23s4.39-.45 6-1.23V17zm0-5c0 .5-2.13 2-6 2s-6-1.5-6-2V9.77C7.61 10.55 9.72 11 12 11s4.39-.45 6-1.23V12z";
    private static final String SVG_PIN = "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z";
    private static final String SVG_NOTE = "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z";
    private static final String SVG_INFO = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z";
    private static final String SVG_CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String SVG_CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";

    private SupplierFormModal() {}

    public static Optional<Supplier> showAddDialog(
            Window owner,
            long businessId,
            Function<SupplierCreateDto, Supplier> onSave
    ) {
        return showInternal(
                owner,
                null,
                "Add New Supplier (নতুন সরবরাহকারী)",
                "Register a vendor with contact details and verified opening payable balance.",
                "Save Supplier",
                inputs -> {
                    Money opening = parseNonNegativeMoney(inputs.openingPayable());
                    int creditDays = parseNonNegativeInt(inputs.creditPeriodDays(), "Credit Period (Days)");
                    return onSave.apply(new SupplierCreateDto(
                            businessId,
                            inputs.name(),
                            inputs.contactPerson(),
                            inputs.phone(),
                            inputs.address(),
                            opening,
                            inputs.notes(),
                            inputs.supplierCode(),
                            inputs.email(),
                            inputs.paymentTerms(),
                            creditDays,
                            inputs.isActive()
                    ));
                }
        );
    }

    public static Optional<Supplier> showEditDialog(
            Window owner,
            Supplier existing,
            Function<SupplierUpdateDto, Supplier> onSave
    ) {
        return showInternal(
                owner,
                existing,
                "Edit Supplier (সরবরাহকারী সম্পাদনা)",
                "Update vendor contact details and notes for " + existing.name() + ".",
                "Update Supplier",
                inputs -> {
                    int creditDays = parseNonNegativeInt(inputs.creditPeriodDays(), "Credit Period (Days)");
                    return onSave.apply(new SupplierUpdateDto(
                            existing.id(),
                            inputs.name(),
                            inputs.contactPerson(),
                            inputs.phone(),
                            inputs.address(),
                            inputs.notes(),
                            inputs.supplierCode(),
                            inputs.email(),
                            inputs.paymentTerms(),
                            creditDays,
                            inputs.isActive()
                    ));
                }
        );
    }

    private static Optional<Supplier> showInternal(
            Window owner,
            Supplier existing,
            String title,
            String subtitle,
            String submitLabel,
            Function<FormValues, Supplier> submitHandler
    ) {
        AtomicReference<Supplier> savedResult = new AtomicReference<>();

        Stage stage = createModalStage(owner, title);

        ModalParts parts = buildModalParts(
                existing,
                title,
                subtitle,
                submitLabel,
                stage::close,
                values -> {
                    Supplier result = submitHandler.apply(values);
                    savedResult.set(result);
                    stage.close();
                }
        );

        // Draggable header
        final double[] dragOffset = new double[2];
        parts.headerDragArea().setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        parts.headerDragArea().setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffset[0]);
            stage.setY(e.getScreenY() - dragOffset[1]);
        });

        Scene scene = buildStyledScene(parts.rootPane(), parts.btnSave(), stage::close);
        stage.setScene(scene);

        if (owner != null && owner.getWidth() > 0 && owner.getHeight() > 0) {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            stage.setOnShown(ev -> {
                double x = Math.max(bounds.getMinX(), Math.min(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2.0, bounds.getMaxX() - stage.getWidth()));
                double y = Math.max(bounds.getMinY(), Math.min(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2.0, bounds.getMaxY() - stage.getHeight()));
                stage.setX(x);
                stage.setY(y);
                parts.txtName().requestFocus();
            });
        } else {
            stage.setOnShown(ev -> parts.txtName().requestFocus());
        }

        Parent ownerRoot = (owner != null && owner.getScene() != null) ? owner.getScene().getRoot() : null;
        Effect priorEffect = ownerRoot != null ? ownerRoot.getEffect() : null;
        try {
            if (ownerRoot != null && priorEffect == null) {
                ColorAdjust dim = new ColorAdjust(0, -0.04, -0.07, 0);
                GaussianBlur blur = new GaussianBlur(4.0);
                blur.setInput(dim);
                ownerRoot.setEffect(blur);
            }
            stage.showAndWait();
        } finally {
            if (ownerRoot != null && priorEffect == null) {
                ownerRoot.setEffect(null);
            }
        }

        return Optional.ofNullable(savedResult.get());
    }

    static Stage createModalStage(Window owner, String title) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(title);
        AppIconManager.applyToStage(stage);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }
        return stage;
    }

    static Scene buildStyledScene(StackPane rootPane, Button btnSave, Runnable onClose) {
        Scene scene = new Scene(rootPane);
        scene.setFill(Color.TRANSPARENT);
        URL cssUrl = SupplierFormModal.class.getResource("/com/smartkhata/ui/css/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                onClose.run();
            } else if (e.getCode() == KeyCode.ENTER && e.isControlDown()) {
                btnSave.fire();
            }
        });
        return scene;
    }

    static ModalParts buildModalParts(
            Supplier existing,
            String title,
            String subtitle,
            String submitLabel,
            Runnable onClose,
            java.util.function.Consumer<FormValues> onValidSubmit
    ) {
        boolean isEdit = existing != null;

        // Inline error banner
        Label lblError = new Label();
        lblError.setWrapText(true);
        lblError.setStyle("-fx-text-fill: #B91C1C; -fx-font-size: 12px; -fx-font-weight: 600;");
        HBox errorBanner = new HBox(8.0, iconBox(SVG_INFO, "#DC2626", 15.0), lblError);
        errorBanner.getStyleClass().add("sk-modal-error-banner");
        errorBanner.setAlignment(Pos.CENTER_LEFT);
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);

        // Form fields
        TextField txtName = new TextField(isEdit ? nonNull(existing.name()) : "");
        txtName.setPromptText("e.g. Meghna Oil Mills Ltd.");
        txtName.getStyleClass().add("sk-modal-input");

        TextField txtContact = new TextField(isEdit ? nonNull(existing.contactPerson()) : "");
        txtContact.setPromptText("e.g. Md. Shahidul Islam");
        txtContact.getStyleClass().add("sk-modal-input");

        TextField txtPhone = new TextField(isEdit ? nonNull(existing.phone()) : "");
        txtPhone.setPromptText("e.g. 018XXXXXXXX");
        txtPhone.getStyleClass().add("sk-modal-input");

        TextField txtOpening = new TextField(isEdit ? BengaliMoneyFormatter.format(existing.openingBalance()) : "");
        txtOpening.setPromptText("e.g. 25000");
        txtOpening.getStyleClass().add("sk-modal-input");
        if (isEdit) {
            txtOpening.setEditable(false);
            txtOpening.setFocusTraversable(false);
        }

        TextField txtAddress = new TextField(isEdit ? nonNull(existing.address()) : "");
        txtAddress.setPromptText("e.g. Postogola, Dhaka");
        txtAddress.getStyleClass().add("sk-modal-input");

        TextField txtSupplierCode = new TextField(isEdit ? nonNull(existing.supplierCode()) : "");
        txtSupplierCode.setPromptText("Auto-generated if blank (e.g. SUP-00012)");
        txtSupplierCode.getStyleClass().add("sk-modal-input");

        TextField txtEmail = new TextField(isEdit ? nonNull(existing.email()) : "");
        txtEmail.setPromptText("e.g. orders@vendor.com (optional)");
        txtEmail.getStyleClass().add("sk-modal-input");

        TextField txtPaymentTerms = new TextField(isEdit ? nonNull(existing.paymentTerms()) : "");
        txtPaymentTerms.setPromptText("e.g. Net 15 / Cash on Delivery");
        txtPaymentTerms.getStyleClass().add("sk-modal-input");

        TextField txtCreditDays = new TextField(isEdit ? String.valueOf(existing.creditPeriodDays()) : "0");
        txtCreditDays.setPromptText("e.g. 15");
        txtCreditDays.getStyleClass().add("sk-modal-input");

        CheckBox chkIsActive = new CheckBox("Active Supplier for Procurement");
        chkIsActive.setSelected(!isEdit || existing.isActive());
        chkIsActive.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #15803D;");

        TextArea txtNotes = new TextArea(isEdit ? nonNull(existing.notes()) : "");
        txtNotes.setPromptText("Optional payment terms or notes...");
        txtNotes.setWrapText(true);
        txtNotes.setPrefRowCount(2);
        txtNotes.getStyleClass().add("sk-modal-textarea");

        // Header
        StackPane headerBadge = new StackPane(iconBox(SVG_SHOP, "#15803D", 22.0));
        headerBadge.getStyleClass().add("sk-modal-badge");

        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");

        Label lblSubtitle = new Label(subtitle);
        lblSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569;");
        lblSubtitle.setWrapText(true);

        VBox titleGroup = new VBox(2.0, lblTitle, lblSubtitle);
        titleGroup.setAlignment(Pos.CENTER_LEFT);

        Button btnClose = new Button();
        btnClose.setGraphic(iconBox(SVG_CLOSE, "#64748B", 16.0));
        btnClose.getStyleClass().add("sk-modal-close-btn");
        btnClose.setOnAction(e -> onClose.run());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox headerRow = new HBox(12.0, headerBadge, titleGroup, headerSpacer, btnClose);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(18.0, 22.0, 15.0, 24.0));

        SVGPath topWaveBack = new SVGPath();
        topWaveBack.setContent("M 0,0 L 240,0 L 240,74 C 155,64 78,34 0,0 Z");
        topWaveBack.setFill(Color.web("#DCFCE7", 0.55));
        topWaveBack.setMouseTransparent(true);
        StackPane.setAlignment(topWaveBack, Pos.TOP_RIGHT);

        SVGPath topWaveFront = new SVGPath();
        topWaveFront.setContent("M 50,0 L 240,0 L 240,50 C 175,44 115,24 50,0 Z");
        topWaveFront.setFill(Color.web("#BBF7D0", 0.36));
        topWaveFront.setMouseTransparent(true);
        StackPane.setAlignment(topWaveFront, Pos.TOP_RIGHT);

        StackPane headerStack = new StackPane(topWaveBack, topWaveFront, headerRow);
        headerStack.setStyle("-fx-border-color: transparent transparent #EBF1ED transparent; -fx-border-width: 0 0 1px 0;");

        // 2-Column Top Grid
        GridPane twoColGrid = new GridPane();
        twoColGrid.setHgap(20.0);
        twoColGrid.setVgap(14.0);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50.0);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50.0);
        twoColGrid.getColumnConstraints().addAll(col1, col2);

        VBox nameFieldBlock = buildInputBlock(
                SVG_SHOP, "Supplier Name", true,
                "Enter the supplier / vendor business name.",
                wrapTextField(SVG_SHOP, txtName, false)
        );
        VBox contactFieldBlock = buildInputBlock(
                SVG_USER, "Contact Person", false,
                "Enter the main contact person's name.",
                wrapTextField(SVG_USER, txtContact, false)
        );
        VBox phoneFieldBlock = buildInputBlock(
                SVG_PHONE, "Phone Number", false,
                "Enter a valid mobile number for communication.",
                wrapTextField(SVG_PHONE, txtPhone, false)
        );

        String openingPillText = isEdit
                ? "Opening payable is locked in ledger after vendor registration."
                : "This is the past payable amount, recorded into the ledger.";
        HBox openingInfoPill = new HBox(7.0,
                iconBox(SVG_INFO, "#15803D", 14.0),
                createStyledLabel(openingPillText, "-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #166534;"));
        openingInfoPill.getStyleClass().add("sk-modal-info-pill");
        openingInfoPill.setAlignment(Pos.CENTER_LEFT);

        VBox openingFieldBlock = buildInputBlock(
                SVG_COINS, "Opening Payable", false,
                "Enter the past payable amount (existing balance).",
                wrapTextField(SVG_COINS, txtOpening, isEdit),
                openingInfoPill
        );

        twoColGrid.add(nameFieldBlock, 0, 0);
        twoColGrid.add(contactFieldBlock, 1, 0);
        twoColGrid.add(phoneFieldBlock, 0, 1);
        twoColGrid.add(openingFieldBlock, 1, 1);

        VBox codeFieldBlock = buildInputBlock(
                SVG_NOTE, "Supplier Code", false,
                "Unique internal code (auto-assigned if omitted).",
                wrapTextField(SVG_NOTE, txtSupplierCode, false)
        );
        VBox emailFieldBlock = buildInputBlock(
                SVG_INFO, "Email (Optional)", false,
                "Vendor email address for PO/billing correspondence.",
                wrapTextField(SVG_INFO, txtEmail, false)
        );
        VBox termsFieldBlock = buildInputBlock(
                SVG_NOTE, "Payment Terms", false,
                "Standard agreed settlement terms (e.g. Net 15, COD).",
                wrapTextField(SVG_NOTE, txtPaymentTerms, false)
        );
        VBox creditFieldBlock = buildInputBlock(
                SVG_COINS, "Credit Period (Days)", false,
                "Allowed credit days after invoice.",
                wrapTextField(SVG_COINS, txtCreditDays, false)
        );

        twoColGrid.add(codeFieldBlock, 0, 2);
        twoColGrid.add(emailFieldBlock, 1, 2);
        twoColGrid.add(termsFieldBlock, 0, 3);
        twoColGrid.add(creditFieldBlock, 1, 3);

        VBox addressFieldBlock = buildInputBlock(
                SVG_PIN, "Address", false,
                "Enter the supplier's business address.",
                wrapTextField(SVG_PIN, txtAddress, false)
        );

        HBox notesWrap = new HBox(9.0, alignTop(iconBox(SVG_NOTE, "#64748B", 16.0), 2.0), txtNotes);
        HBox.setHgrow(txtNotes, Priority.ALWAYS);
        notesWrap.getStyleClass().add("sk-modal-textarea-wrap");
        notesWrap.setAlignment(Pos.TOP_LEFT);

        VBox notesFieldBlock = buildInputBlock(
                SVG_NOTE, "Notes", false,
                "Add any additional information, payment terms, or internal notes (optional).",
                notesWrap
        );

        HBox activeRow = new HBox(10.0, chkIsActive);
        activeRow.setAlignment(Pos.CENTER_LEFT);

        VBox infoSection = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(SVG_SHOP, "Supplier & Contact Information",
                        "Business identity, vendor code, and primary representative."),
                twoColGrid
        );

        VBox addressNotesSection = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(SVG_PIN, "Address & Procurement Notes",
                        "Storefront/warehouse address, notes, and active status."),
                addressFieldBlock,
                notesFieldBlock,
                activeRow
        );

        VBox formBody = new VBox(14.0, errorBanner, infoSection, addressNotesSection);
        formBody.setPadding(new Insets(16.0, 24.0, 16.0, 24.0));

        ScrollPane bodyScroll = new ScrollPane(formBody);
        bodyScroll.getStyleClass().add("sk-dlg-scroll");
        bodyScroll.setFitToWidth(true);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        bodyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);

        // Footer decorative green waves + grocery bag artwork on bottom-left
        SVGPath bottomWaveBack = new SVGPath();
        bottomWaveBack.setContent("M 0,68 L 0,20 C 65,12 135,38 225,44 C 280,47 315,58 345,68 Z");
        bottomWaveBack.setFill(Color.web("#86EFAC", 0.38));
        bottomWaveBack.setMouseTransparent(true);
        StackPane.setAlignment(bottomWaveBack, Pos.BOTTOM_LEFT);

        SVGPath bottomWaveFront = new SVGPath();
        bottomWaveFront.setContent("M 0,68 L 0,34 C 52,28 108,46 176,54 C 225,59 258,64 285,68 Z");
        bottomWaveFront.setFill(Color.web("#4ADE80", 0.25));
        bottomWaveFront.setMouseTransparent(true);
        StackPane.setAlignment(bottomWaveFront, Pos.BOTTOM_LEFT);

        ImageView bagArtwork = loadGroceryBagArtwork();

        Button btnCancel = new Button("Cancel");
        btnCancel.getStyleClass().add("sk-modal-btn-cancel");
        btnCancel.setCancelButton(true);
        btnCancel.setOnAction(e -> onClose.run());

        // Move focus cleanly out of TextArea with Tab / Shift+Tab
        txtNotes.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB) {
                Node target = event.isShiftDown() ? txtAddress : btnCancel;
                target.requestFocus();
                event.consume();
            }
        });

        Button btnSave = new Button(submitLabel);
        btnSave.setGraphic(iconBox(SVG_CHECK, "#FFFFFF", 16.0));
        btnSave.getStyleClass().add("sk-modal-btn-save");
        btnSave.setOnAction(e -> {
            String name = txtName.getText() != null ? txtName.getText().trim() : "";
            if (name.isBlank()) {
                showInlineError(errorBanner, lblError, "Supplier Name is required.", txtName);
                return;
            }
            String openingRaw = isEdit ? "0" : (txtOpening.getText() != null ? txtOpening.getText().trim() : "");
            if (!isEdit) {
                try {
                    parseNonNegativeMoney(openingRaw);
                } catch (IllegalArgumentException ex) {
                    showInlineError(errorBanner, lblError, ex.getMessage(), txtOpening);
                    return;
                }
            }

            try {
                parseNonNegativeInt(txtCreditDays.getText(), "Credit Period (Days)");
            } catch (IllegalArgumentException ex) {
                showInlineError(errorBanner, lblError, ex.getMessage(), txtCreditDays);
                return;
            }

            try {
                onValidSubmit.accept(new FormValues(
                        name,
                        txtContact.getText(),
                        txtPhone.getText(),
                        txtAddress.getText(),
                        openingRaw,
                        txtNotes.getText(),
                        txtSupplierCode.getText(),
                        txtEmail.getText(),
                        txtPaymentTerms.getText(),
                        txtCreditDays.getText(),
                        chkIsActive.isSelected()
                ));
            } catch (Exception ex) {
                showInlineError(errorBanner, lblError, ex.getMessage(), txtName);
            }
        });

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        HBox footerButtons = new HBox(12.0, footerSpacer, btnCancel, btnSave);
        footerButtons.setAlignment(Pos.CENTER_RIGHT);
        footerButtons.setPadding(new Insets(12.0, 26.0, 20.0, 26.0));

        StackPane footerStack = new StackPane(bottomWaveBack, bottomWaveFront);
        if (bagArtwork != null) {
            StackPane.setAlignment(bagArtwork, Pos.BOTTOM_LEFT);
            StackPane.setMargin(bagArtwork, new Insets(0, 0, 0, 14));
            footerStack.getChildren().add(bagArtwork);
        }
        footerStack.getChildren().add(footerButtons);
        footerStack.setMinHeight(72.0);

        VBox clippedContent = new VBox(0, headerStack, bodyScroll, footerStack);
        Rectangle clipRect = new Rectangle();
        clipRect.widthProperty().bind(clippedContent.widthProperty());
        clipRect.heightProperty().bind(clippedContent.heightProperty());
        clipRect.setArcWidth(28.0);
        clipRect.setArcHeight(28.0);
        clippedContent.setClip(clipRect);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double targetW = Math.min(740.0, Math.max(540.0, screenBounds.getWidth() - 48.0));
        double targetMaxH = Math.min(740.0, Math.max(480.0, screenBounds.getHeight() - 48.0));

        StackPane cardShell = new StackPane(clippedContent);
        cardShell.getStyleClass().add("sk-dlg-card");
        cardShell.setPrefWidth(targetW);
        cardShell.setMinWidth(Math.min(620.0, targetW));
        cardShell.setMaxWidth(targetW);
        cardShell.setMaxHeight(targetMaxH);

        StackPane rootPane = new StackPane(cardShell);
        rootPane.setPadding(new Insets(20.0));
        rootPane.setStyle("-fx-background-color: transparent;");

        return new ModalParts(
                rootPane, headerStack, errorBanner, lblError,
                txtName, txtContact, txtPhone, txtOpening, txtAddress, txtNotes,
                btnCancel, btnSave, bagArtwork,
                txtSupplierCode, txtEmail, txtPaymentTerms, txtCreditDays, chkIsActive
        );
    }

    private static VBox buildInputBlock(
            String svgContent,
            String labelText,
            boolean required,
            String helperText,
            Node... controls
    ) {
        Label titleLabel = new Label(labelText);
        titleLabel.getStyleClass().add("sk-modal-field-title");

        HBox labelRow = new HBox(7.0, iconBox(svgContent, "#16A34A", 15.0), titleLabel);
        labelRow.setAlignment(Pos.CENTER_LEFT);
        if (required) {
            Label asterisk = new Label("*");
            asterisk.setStyle("-fx-text-fill: #DC2626; -fx-font-weight: 800; -fx-font-size: 13px;");
            labelRow.getChildren().add(asterisk);
        }

        Label helperLabel = new Label(helperText);
        helperLabel.getStyleClass().add("sk-modal-field-help");

        VBox headerBox = new VBox(2.0, labelRow, helperLabel);
        VBox block = new VBox(6.0, headerBox);
        block.getChildren().addAll(controls);
        return block;
    }

    private static final PseudoClass PSEUDO_FOCUSED_WITHIN = PseudoClass.getPseudoClass("focused-within");

    private static HBox wrapTextField(String iconSvg, TextField field, boolean readOnly) {
        HBox wrapper = new HBox(9.0, iconBox(iconSvg, "#68778A", 15.0), field);
        HBox.setHgrow(field, Priority.ALWAYS);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.getStyleClass().add("sk-dlg-input-wrap");
        field.getStyleClass().setAll("text-field", "sk-dlg-input");
        if (readOnly) {
            wrapper.getStyleClass().add("sk-dlg-input-wrap-disabled");
        }
        field.focusedProperty().addListener((o, a, hasFocus) ->
                wrapper.pseudoClassStateChanged(PSEUDO_FOCUSED_WITHIN, hasFocus));
        return wrapper;
    }

    private static StackPane alignTop(Node node, double topPadding) {
        StackPane holder = new StackPane(node);
        holder.setAlignment(Pos.TOP_CENTER);
        holder.setPadding(new Insets(topPadding, 0, 0, 0));
        return holder;
    }

    private static StackPane iconBox(String pathData, String colorHex, double sizePx) {
        SVGPath svg = new SVGPath();
        svg.setContent(pathData);
        svg.setFill(Color.web(colorHex));
        double scale = sizePx / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        StackPane box = new StackPane(new Group(svg));
        box.setPrefSize(sizePx, sizePx);
        box.setMinSize(sizePx, sizePx);
        box.setMaxSize(sizePx, sizePx);
        box.setMouseTransparent(true);
        return box;
    }

    private static Label createStyledLabel(String text, String style) {
        Label label = new Label(text);
        label.setStyle(style);
        return label;
    }

    private static ImageView loadGroceryBagArtwork() {
        try (InputStream is = SupplierFormModal.class.getResourceAsStream("/com/smartkhata/ui/images/grocery/sidebar_grocery_bag_2x.png")) {
            if (is == null) return null;
            ImageView iv = new ImageView(new Image(is));
            iv.setFitHeight(62.0);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setMouseTransparent(true);
            return iv;
        } catch (Exception e) {
            return null;
        }
    }

    private static void showInlineError(HBox banner, Label lblError, String message, Node focusTarget) {
        lblError.setText(message != null && !message.isBlank() ? message : "Please check your inputs.");
        banner.setVisible(true);
        banner.setManaged(true);
        if (focusTarget != null) {
            focusTarget.requestFocus();
        }
    }

    static Money parseNonNegativeMoney(String input) {
        if (input == null || input.trim().isBlank()) {
            return Money.ZERO;
        }
        String normalized = input.trim().replaceAll("[, ]", "");
        BigDecimal bd;
        try {
            bd = new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Opening Payable must be a valid amount in BDT (e.g. 25000).");
        }
        if (bd.signum() < 0) {
            throw new IllegalArgumentException("Opening Payable cannot be negative.");
        }
        return Money.ofBdt(bd);
    }

    static int parseNonNegativeInt(String input, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            return 0;
        }
        try {
            int val = Integer.parseInt(input.trim());
            if (val < 0) {
                throw new IllegalArgumentException(fieldLabel + " cannot be negative.");
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldLabel + " must be a whole number.");
        }
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    record FormValues(
            String name,
            String contactPerson,
            String phone,
            String address,
            String openingPayable,
            String notes,
            String supplierCode,
            String email,
            String paymentTerms,
            String creditPeriodDays,
            boolean isActive
    ) {
        FormValues(
                String name,
                String contactPerson,
                String phone,
                String address,
                String openingPayable,
                String notes
        ) {
            this(name, contactPerson, phone, address, openingPayable, notes, null, null, null, "0", true);
        }
    }

    record ModalParts(
            StackPane rootPane,
            StackPane headerDragArea,
            HBox errorBanner,
            Label lblError,
            TextField txtName,
            TextField txtContact,
            TextField txtPhone,
            TextField txtOpening,
            TextField txtAddress,
            TextArea txtNotes,
            Button btnCancel,
            Button btnSave,
            ImageView bagArtwork,
            TextField txtSupplierCode,
            TextField txtEmail,
            TextField txtPaymentTerms,
            TextField txtCreditDays,
            CheckBox chkIsActive
    ) {}
}

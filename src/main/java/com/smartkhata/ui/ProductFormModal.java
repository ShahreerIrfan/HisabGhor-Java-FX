package com.smartkhata.ui;

import com.smartkhata.app.AppConfig;
import com.smartkhata.model.dto.ProductCreateDto;
import com.smartkhata.model.dto.ProductSupplierUpsertDto;
import com.smartkhata.model.dto.ProductSupplierViewDto;
import com.smartkhata.model.dto.ProductUpdateDto;
import com.smartkhata.model.dto.SupplierCreateDto;
import com.smartkhata.model.entity.Category;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.vo.Money;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.ui.icon.AppIconManager;
import com.smartkhata.util.BengaliMoneyFormatter;
import com.smartkhata.util.PricingCalculator;
import com.smartkhata.util.ProductImageResolver;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
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
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Redesigned floating grocery-green modal dialog for creating and editing products.
 * Features a 2-tab workspace (Details & Pricing + Suppliers & Trade Prices),
 * live profit/margin/markup analytics, smart image preview, inline category/supplier
 * creation, responsive column collapse, and strict monetary/duplicate validation.
 */
public final class ProductFormModal {

    // Vector SVG iconography (no emoji placeholders)
    private static final String SVG_CUBE = "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.27 6.96L12 12.01l8.73-5.05M12 22.08V12";
    private static final String SVG_CUBE_FILL = "M12 2L3 7v10l9 5 9-5V7l-9-5zm0 2.3L18.4 8 12 11.7 5.6 8 12 4.3zM5 9.8l6 3.5v6.4l-6-3.3V9.8zm8 9.9v-6.4l6-3.5v6.6l-6 3.3z";
    private static final String SVG_TAG = "M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z";
    private static final String SVG_SUPPLIERS = "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z";
    private static final String SVG_DOC = "M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z";
    private static final String SVG_BARCODE = "M2 6h2v12H2V6zm3 0h1v12H5V6zm2 0h2v12H7V6zm3 0h1v12h-1V6zm2 0h3v12h-3V6zm4 0h1v12h-1V6zm2 0h2v12h-2V6zm3 0h1v12h-1V6z";
    private static final String SVG_COINS = "M12 3C7.58 3 4 4.79 4 7v10c0 2.21 3.58 4 8 4s8-1.79 8-4V7c0-2.21-3.58-4-8-4zm0 2c3.87 0 6 1.5 6 2s-2.13 2-6 2-6-1.5-6-2 2.13-2 6-2zm6 12c0 .5-2.13 2-6 2s-6-1.5-6-2v-2.23c1.61.78 3.72 1.23 6 1.23s4.39-.45 6-1.23V17zm0-5c0 .5-2.13 2-6 2s-6-1.5-6-2V9.77C7.61 10.55 9.72 11 12 11s4.39-.45 6-1.23V12z";
    private static final String SVG_BELL = "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z";
    private static final String SVG_CHART = "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z";
    private static final String SVG_IMAGE = "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z";
    private static final String SVG_FOLDER = "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z";
    private static final String SVG_TRASH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    private static final String SVG_SHOP = "M20 4H4v2h16V4zm1 10v-2l-1-5H4l-1 5v2h1v6h10v-6h4v6h2v-6h1zm-9 4H6v-4h6v4z";
    private static final String SVG_CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String SVG_CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
    private static final String SVG_INFO = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z";
    private static final String SVG_WARNING = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";
    private static final String SVG_ARROW_RIGHT = "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z";
    private static final String SVG_TREND_UP = "M16 6l2.29 2.29-4.88 4.88-4-4L2 16.59 3.41 18l6-6 4 4 6.3-6.29L22 12V6z";
    private static final String SVG_EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";

    static final String AUTO_IMAGE_LABEL = "Auto (Match Name)";
    static final String CUSTOM_IMAGE_LABEL = "Custom Image File";

    private ProductFormModal() {}

    /**
     * Dependencies and callbacks passed into the modal so UI stays decoupled from direct DAO wiring.
     */
    public record Dependencies(
            java.util.function.Supplier<List<Category>> categoryLoader,
            BiFunction<String, String, Category> categoryCreator,
            java.util.function.Supplier<List<Supplier>> supplierLoader,
            Function<SupplierCreateDto, Supplier> supplierCreator,
            Function<Long, List<ProductSupplierViewDto>> productSupplierLoader,
            Function<SavePayload, Product> saveExecutor
    ) {}

    /**
     * Draft row representing a product-supplier link before final save/update.
     */
    public record DraftSupplierLink(
            Long mappingId,
            long supplierId,
            String supplierName,
            Money tradePrice,
            String supplierProductCode,
            int minOrderQty,
            String purchaseUnit,
            int packSize,
            boolean isPreferred,
            int leadTimeDays,
            boolean isActive
    ) {}

    /**
     * Validated payload emitted when the user submits Save Product or Update Product.
     */
    public record SavePayload(
            Long existingProductId,
            ProductCreateDto createDto,
            ProductUpdateDto updateDto,
            List<DraftSupplierLink> supplierLinks,
            List<Long> deletedMappingIds
    ) {}

    public static Optional<Product> showAddDialog(Window owner, long businessId, Dependencies deps) {
        return showInternal(owner, businessId, null, deps);
    }

    public static Optional<Product> showEditDialog(Window owner, long businessId, Product existing, Dependencies deps) {
        return showInternal(owner, businessId, existing, deps);
    }

    private static Optional<Product> showInternal(
            Window owner,
            long businessId,
            Product existing,
            Dependencies deps
    ) {
        boolean isEdit = existing != null;
        String title = isEdit ? "Edit Product (পণ্য তথ্য পরিবর্তন)" : "Add New Product (নতুন পণ্য)";
        String subtitle = isEdit
                ? "Update product pricing (TP / MRP / selling), stock settings, and linked suppliers for " + existing.name() + "."
                : "Create a product with TP, MRP, selling price, stock settings, and linked suppliers.";
        String submitLabel = isEdit ? "Update Product" : "Save Product";

        Stage stage = createModalStage(owner, title);

        List<Category> initialCats = deps.categoryLoader().get();
        List<Supplier> initialSups = deps.supplierLoader().get();
        List<ProductSupplierViewDto> existingLinks = isEdit && deps.productSupplierLoader() != null
                ? deps.productSupplierLoader().apply(existing.id())
                : List.of();

        AtomicReference<Product> savedResult = new AtomicReference<>();
        AtomicBoolean isSaving = new AtomicBoolean(false);

        ModalParts parts = buildModalParts(
                businessId,
                existing,
                title,
                subtitle,
                submitLabel,
                initialCats,
                initialSups,
                existingLinks,
                (catBox) -> promptInlineAddCategory(stage, deps.categoryCreator(), catBox),
                (supBox) -> promptInlineAddSupplier(stage, businessId, deps.supplierLoader(), deps.supplierCreator(), supBox),
                () -> {
                    if (!isSaving.get()) {
                        stage.close();
                    }
                },
                (payload, errorConsumer, doneCallback) -> {
                    if (!isSaving.compareAndSet(false, true)) {
                        return;
                    }
                    CompletableFuture.supplyAsync(() -> deps.saveExecutor().apply(payload))
                            .whenComplete((result, throwable) -> Platform.runLater(() -> {
                                isSaving.set(false);
                                doneCallback.run();
                                if (throwable != null) {
                                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                                    errorConsumer.accept(cause.getMessage());
                                } else {
                                    savedResult.set(result);
                                    stage.close();
                                }
                            }));
                }
        );

        // Compute responsive modal dimensions inside usable desktop bounds
        Rectangle2D screenBounds = resolveUsableScreenBounds(owner);
        double usableW = Math.max(640.0, screenBounds.getWidth() - 40.0);
        double usableH = Math.max(520.0, screenBounds.getHeight() - 40.0);
        double modalW = Math.min(1040.0, Math.max(Math.min(850.0, usableW), usableW * 0.86));
        double modalH = Math.min(790.0, usableH);

        parts.cardShell().setPrefSize(modalW, modalH);
        parts.cardShell().setMinSize(Math.min(600.0, modalW), Math.min(500.0, modalH));
        parts.cardShell().setMaxSize(modalW, modalH);

        // Draggable header bar
        final double[] dragOffset = new double[2];
        parts.headerDragArea().setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        parts.headerDragArea().setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffset[0]);
            stage.setY(e.getScreenY() - dragOffset[1]);
        });

        Scene scene = buildStyledScene(parts.rootPane(), parts.btnSave(), () -> {
            if (!isSaving.get()) {
                stage.close();
            }
        });
        stage.setScene(scene);

        stage.setOnShown(ev -> {
            double x = screenBounds.getMinX() + (screenBounds.getWidth() - stage.getWidth()) / 2.0;
            double y = screenBounds.getMinY() + (screenBounds.getHeight() - stage.getHeight()) / 2.0;
            if (owner != null) {
                double ox = owner.getX() + (owner.getWidth() - stage.getWidth()) / 2.0;
                double oy = owner.getY() + (owner.getHeight() - stage.getHeight()) / 2.0;
                x = Math.max(screenBounds.getMinX(), Math.min(ox, screenBounds.getMaxX() - stage.getWidth()));
                y = Math.max(screenBounds.getMinY(), Math.min(oy, screenBounds.getMaxY() - stage.getHeight()));
            }
            stage.setX(x);
            stage.setY(y);
            parts.txtName().requestFocus();
        });

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
        URL cssUrl = ProductFormModal.class.getResource("/com/smartkhata/ui/css/styles.css");
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

    @FunctionalInterface
    interface SubmitAsyncHandler {
        void submit(SavePayload payload, Consumer<String> onFailure, Runnable resetSavingUi);
    }

    static ModalParts buildModalParts(
            long businessId,
            Product existing,
            String title,
            String subtitle,
            String submitLabel,
            List<Category> initialCategories,
            List<Supplier> initialSuppliers,
            List<ProductSupplierViewDto> existingLinks,
            Consumer<ComboBox<Category>> onQuickAddCategory,
            Consumer<ComboBox<Supplier>> onQuickAddSupplier,
            Runnable onClose,
            SubmitAsyncHandler onValidSubmit
    ) {
        boolean isEdit = existing != null;

        // Global inline validation/persistence error banner (fixed below tab bar)
        Label lblError = new Label();
        lblError.setWrapText(true);
        lblError.setStyle("-fx-text-fill: #B91C1C; -fx-font-size: 12px; -fx-font-weight: 700;");
        HBox errorBanner = new HBox(8.0, iconBox(SVG_INFO, "#DC2626", 15.0), lblError);
        errorBanner.getStyleClass().add("sk-modal-error-banner");
        errorBanner.setAlignment(Pos.CENTER_LEFT);
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
        VBox errorHolder = new VBox(errorBanner);
        errorHolder.setPadding(new Insets(10, 24, 0, 24));
        errorHolder.setVisible(false);
        errorHolder.setManaged(false);

        Runnable hideError = () -> {
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
            errorHolder.setVisible(false);
            errorHolder.setManaged(false);
        };

        Consumer<String> showError = msg -> {
            lblError.setText(msg != null && !msg.isBlank() ? msg : "Please review highlighted fields.");
            errorBanner.setVisible(true);
            errorBanner.setManaged(true);
            errorHolder.setVisible(true);
            errorHolder.setManaged(true);
        };

        // --- Header ---
        StackPane headerStack = buildHeader(title, subtitle, onClose);

        // --- Shared observable draft state ---
        ObservableList<DraftSupplierLink> draftLinks = FXCollections.observableArrayList();
        List<Long> deletedMappingIds = new ArrayList<>();
        if (existingLinks != null) {
            for (ProductSupplierViewDto v : existingLinks) {
                draftLinks.add(new DraftSupplierLink(
                        v.id(), v.supplierId(), v.supplierName(), v.tradePrice(),
                        v.supplierProductCode(), v.minOrderQty(), v.purchaseUnit(),
                        v.packSize(), v.isPreferred(), v.leadTimeDays(), v.isActive()
                ));
            }
        }

        // --- Tab Bar ---
        Button tabBtnDetails = new Button("Details & Pricing");
        tabBtnDetails.setGraphic(iconBox(SVG_TAG, "#008A32", 15.0));
        tabBtnDetails.getStyleClass().addAll("sk-prod-tab-btn", "sk-prod-tab-btn-active");

        Button tabBtnSuppliers = new Button("Suppliers & Trade Prices (" + draftLinks.size() + ")");
        tabBtnSuppliers.setGraphic(iconBox(SVG_SUPPLIERS, "#68778A", 16.0));
        tabBtnSuppliers.getStyleClass().add("sk-prod-tab-btn");

        HBox tabBar = new HBox(12.0, tabBtnDetails, tabBtnSuppliers);
        tabBar.getStyleClass().add("sk-prod-tab-bar");
        tabBar.setAlignment(Pos.CENTER_LEFT);

        // --- TAB 1 CONTROLS: Product Information ---
        TextField txtName = new TextField(isEdit ? nonNull(existing.name()) : "");
        txtName.setPromptText("e.g. Teer Soybean Oil 5L");
        txtName.getStyleClass().add("sk-prod-input");

        ComboBox<Category> cmbCategory = new ComboBox<>();
        cmbCategory.getStyleClass().add("sk-prod-combo");
        cmbCategory.setMaxWidth(Double.MAX_VALUE);
        cmbCategory.setPromptText("Select category");
        populateCategoryCombo(cmbCategory, initialCategories, isEdit ? existing.categoryId() : null);

        Button btnNewCategory = new Button("+ New");
        btnNewCategory.getStyleClass().add("sk-prod-btn-new");
        btnNewCategory.setMinWidth(Region.USE_PREF_SIZE);
        btnNewCategory.setTooltip(new Tooltip("Create a new category without leaving this form"));
        btnNewCategory.setOnAction(e -> {
            if (onQuickAddCategory != null) {
                onQuickAddCategory.accept(cmbCategory);
            }
        });

        TextField txtBarcode = new TextField(isEdit ? nonNull(existing.barcode()) : "");
        txtBarcode.setPromptText("e.g. 894110012345");
        txtBarcode.getStyleClass().add("sk-prod-input");

        TextField txtSku = new TextField(isEdit ? nonNull(existing.sku()) : "");
        txtSku.setPromptText("e.g. OIL-TEER-5L");
        txtSku.getStyleClass().add("sk-prod-input");

        ObservableList<String> unitChoices = FXCollections.observableArrayList(
                "pcs", "box", "pkt", "kg", "litre", "gm", "ml", "dozen", "carton"
        );
        ComboBox<String> cmbBaseUnit = new ComboBox<>(unitChoices);
        cmbBaseUnit.setEditable(true);
        cmbBaseUnit.getStyleClass().add("sk-prod-combo");
        cmbBaseUnit.setMaxWidth(Double.MAX_VALUE);
        cmbBaseUnit.setValue(isEdit && existing.unit() != null && !existing.unit().isBlank() ? existing.unit() : "pcs");

        ComboBox<String> cmbPurchaseUnit = new ComboBox<>(FXCollections.observableArrayList(unitChoices));
        cmbPurchaseUnit.setEditable(true);
        cmbPurchaseUnit.getStyleClass().add("sk-prod-combo");
        cmbPurchaseUnit.setMaxWidth(Double.MAX_VALUE);
        cmbPurchaseUnit.setValue(isEdit && existing.purchaseUnit() != null && !existing.purchaseUnit().isBlank()
                ? existing.purchaseUnit() : cmbBaseUnit.getValue());

        TextField txtPackSize = new TextField(isEdit ? String.valueOf(Math.max(1, existing.packSize())) : "1");
        txtPackSize.setPromptText("1");
        txtPackSize.getStyleClass().add("sk-prod-input");
        Label lblPackHint = new Label();
        lblPackHint.getStyleClass().add("sk-prod-unit-tag");

        // --- TAB 1 CONTROLS: Pricing (BDT) ---
        TextField txtTp = new TextField(isEdit ? existing.tradePrice().toBdt().toPlainString() : "");
        txtTp.setPromptText("Trade Price (TP)");
        txtTp.getStyleClass().add("sk-prod-input");

        TextField txtCost = new TextField(isEdit ? existing.costPrice().toBdt().toPlainString() : "");
        txtCost.setPromptText("Initial / Avg Cost");
        txtCost.getStyleClass().add("sk-prod-input");

        TextField txtMrp = new TextField(isEdit ? existing.mrp().toBdt().toPlainString() : "");
        txtMrp.setPromptText("MRP (Max Retail)");
        txtMrp.getStyleClass().add("sk-prod-input");

        TextField txtSelling = new TextField(isEdit ? existing.sellingPrice().toBdt().toPlainString() : "");
        txtSelling.setPromptText("Default Selling Price");
        txtSelling.getStyleClass().add("sk-prod-input");

        if (!isEdit) {
            txtTp.focusedProperty().addListener((o, wasFocused, isNowFocused) -> {
                if (!isNowFocused && txtCost.getText().isBlank() && !txtTp.getText().isBlank()) {
                    txtCost.setText(txtTp.getText().trim());
                }
            });
        }

        // --- TAB 1 CONTROLS: Stock Settings ---
        TextField txtThreshold = new TextField(isEdit ? String.valueOf(existing.lowStockThreshold()) : "5");
        txtThreshold.setPromptText("5");
        txtThreshold.getStyleClass().add("sk-prod-input");
        Label lblThresholdUnit = new Label();
        lblThresholdUnit.getStyleClass().add("sk-prod-unit-tag");

        TextField txtOpeningStock = new TextField("0");
        txtOpeningStock.setPromptText("0");
        txtOpeningStock.getStyleClass().add("sk-prod-input");
        Label lblOpeningUnit = new Label();
        lblOpeningUnit.getStyleClass().add("sk-prod-unit-tag");

        Runnable updateUnitBadges = () -> {
            String baseU = safeUnitValue(cmbBaseUnit, "pcs");
            String purU = safeUnitValue(cmbPurchaseUnit, baseU);
            int pk = safeParseIntOrDefault(txtPackSize.getText(), 1);
            lblThresholdUnit.setText(baseU);
            lblOpeningUnit.setText(baseU);
            lblPackHint.setText("1 " + purU + " = " + Math.max(1, pk) + " " + baseU);
        };
        cmbBaseUnit.valueProperty().addListener((o, a, b) -> updateUnitBadges.run());
        cmbBaseUnit.getEditor().textProperty().addListener((o, a, b) -> updateUnitBadges.run());
        cmbPurchaseUnit.valueProperty().addListener((o, a, b) -> updateUnitBadges.run());
        cmbPurchaseUnit.getEditor().textProperty().addListener((o, a, b) -> updateUnitBadges.run());
        txtPackSize.textProperty().addListener((o, a, b) -> updateUnitBadges.run());
        updateUnitBadges.run();

        // --- TAB 1 CONTROLS: Estimated Pricing Analytics ---
        Label lblEstProfit = new Label("—");
        lblEstProfit.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #008A32;");
        Label lblEstMargin = new Label("—");
        lblEstMargin.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #008A32;");
        Label lblEstMarkup = new Label("—");
        lblEstMarkup.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #008A32;");
        Label lblAnalyticsNote = new Label("Estimate based on unit cost & selling price (actual P&L uses historical COGS).");
        lblAnalyticsNote.getStyleClass().add("sk-prod-section-subtitle");
        lblAnalyticsNote.setWrapText(true);

        Label lblPriceWarning = new Label();
        lblPriceWarning.setWrapText(true);
        lblPriceWarning.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 700; -fx-text-fill: #92400E;");
        HBox warnBanner = new HBox(8.0, iconBox(SVG_WARNING, "#D97706", 15.0), lblPriceWarning);
        warnBanner.getStyleClass().add("sk-prod-warn-banner");
        warnBanner.setAlignment(Pos.CENTER_LEFT);
        warnBanner.setVisible(false);
        warnBanner.setManaged(false);

        Runnable updateAnalytics = () -> refreshAnalyticsPreview(
                txtTp.getText(), txtCost.getText(), txtMrp.getText(), txtSelling.getText(),
                lblEstProfit, lblEstMargin, lblEstMarkup, lblAnalyticsNote, warnBanner, lblPriceWarning
        );
        txtTp.textProperty().addListener((o, a, b) -> updateAnalytics.run());
        txtCost.textProperty().addListener((o, a, b) -> updateAnalytics.run());
        txtMrp.textProperty().addListener((o, a, b) -> updateAnalytics.run());
        txtSelling.textProperty().addListener((o, a, b) -> updateAnalytics.run());
        updateAnalytics.run();

        // --- TAB 1 RIGHT: Product Image Card ---
        ImageWidgetParts imgParts = buildImageCard(isEdit ? existing.imagePath() : null, txtName, cmbCategory);

        // --- TAB 1 RIGHT: Linked Suppliers Info Card ---
        Label lblSupInfoCount = new Label();
        lblSupInfoCount.getStyleClass().add("sk-prod-badge-preferred");
        Button btnJumpToSuppliers = new Button("Manage suppliers from the \"Suppliers & Trade Prices\" tab");
        btnJumpToSuppliers.setGraphic(iconBox(SVG_ARROW_RIGHT, "#008A32", 14.0));
        btnJumpToSuppliers.getStyleClass().add("sk-prod-link-action");
        btnJumpToSuppliers.setWrapText(true);
        VBox supInfoCard = buildLinkedSuppliersInfoCard(lblSupInfoCount, btnJumpToSuppliers);

        // Build Tab 1 Cards
        VBox productInfoCard = buildProductInfoCard(
                txtName, cmbCategory, btnNewCategory, txtBarcode, txtSku,
                cmbBaseUnit, cmbPurchaseUnit, txtPackSize, lblPackHint
        );
        VBox pricingCard = buildPricingCard(isEdit, txtTp, txtCost, txtMrp, txtSelling);
        VBox stockCard = buildStockSettingsCard(isEdit, txtThreshold, lblThresholdUnit, txtOpeningStock, lblOpeningUnit);
        VBox analyticsCard = buildAnalyticsCard(lblEstProfit, lblEstMargin, lblEstMarkup, lblAnalyticsNote, warnBanner);

        VBox leftCol = new VBox(14.0, productInfoCard, pricingCard, stockCard, analyticsCard);
        VBox rightCol = new VBox(14.0, imgParts.card(), supInfoCard);

        // Responsive container switches cleanly between 66%/34% 2-col and 1-col stacked on narrow windows
        GridPane detailsTwoColGrid = new GridPane();
        detailsTwoColGrid.setHgap(16.0);
        detailsTwoColGrid.setVgap(14.0);
        detailsTwoColGrid.setPadding(new Insets(16.0, 22.0, 20.0, 22.0));

        ColumnConstraints colLeft = new ColumnConstraints();
        colLeft.setPercentWidth(66.0);
        colLeft.setHgrow(Priority.ALWAYS);
        ColumnConstraints colRight = new ColumnConstraints();
        colRight.setPercentWidth(34.0);
        colRight.setHgrow(Priority.ALWAYS);
        ColumnConstraints colFull = new ColumnConstraints();
        colFull.setPercentWidth(100.0);
        colFull.setHgrow(Priority.ALWAYS);

        AtomicBoolean stackedMode = new AtomicBoolean(false);
        detailsTwoColGrid.getColumnConstraints().setAll(colLeft, colRight);
        detailsTwoColGrid.add(leftCol, 0, 0);
        detailsTwoColGrid.add(rightCol, 1, 0);

        detailsTwoColGrid.widthProperty().addListener((obs, oldW, newW) -> {
            boolean shouldStack = newW != null && newW.doubleValue() > 100.0 && newW.doubleValue() < 780.0;
            if (shouldStack != stackedMode.getAndSet(shouldStack)) {
                detailsTwoColGrid.getChildren().clear();
                if (shouldStack) {
                    detailsTwoColGrid.getColumnConstraints().setAll(colFull);
                    detailsTwoColGrid.add(leftCol, 0, 0);
                    detailsTwoColGrid.add(rightCol, 0, 1);
                } else {
                    detailsTwoColGrid.getColumnConstraints().setAll(colLeft, colRight);
                    detailsTwoColGrid.add(leftCol, 0, 0);
                    detailsTwoColGrid.add(rightCol, 1, 0);
                }
            }
        });

        // --- TAB 2: Suppliers & Trade Prices (M:N) ---
        SupplierTabParts supTab = buildSuppliersTab(
                initialSuppliers,
                draftLinks,
                deletedMappingIds,
                cmbPurchaseUnit,
                txtPackSize,
                onQuickAddSupplier,
                showError,
                hideError
        );

        // Keep tab title & right-panel badge synchronized with draftLinks count
        Runnable syncSupplierCounts = () -> {
            int count = draftLinks.size();
            tabBtnSuppliers.setText("Suppliers & Trade Prices (" + count + ")");
            lblSupInfoCount.setText(count + (count == 1 ? " supplier linked" : " suppliers linked"));
        };
        draftLinks.addListener((ListChangeListener<DraftSupplierLink>) change -> syncSupplierCounts.run());
        syncSupplierCounts.run();

        // --- Tab Switching Logic ---
        ScrollPane bodyScroll = new ScrollPane(detailsTwoColGrid);
        bodyScroll.getStyleClass().add("sk-prod-body-scroll");
        bodyScroll.setFitToWidth(true);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        bodyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);

        Runnable activateDetailsTab = () -> {
            bodyScroll.setContent(detailsTwoColGrid);
            bodyScroll.setVvalue(0);
            setActiveTabStyle(tabBtnDetails, tabBtnSuppliers, SVG_TAG, SVG_SUPPLIERS);
        };
        Runnable activateSuppliersTab = () -> {
            bodyScroll.setContent(supTab.container());
            bodyScroll.setVvalue(0);
            setActiveTabStyle(tabBtnSuppliers, tabBtnDetails, SVG_SUPPLIERS, SVG_TAG);
        };

        tabBtnDetails.setOnAction(e -> activateDetailsTab.run());
        tabBtnSuppliers.setOnAction(e -> activateSuppliersTab.run());
        btnJumpToSuppliers.setOnAction(e -> activateSuppliersTab.run());

        // Keyboard arrow navigation between the 2 tabs
        tabBtnDetails.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT) {
                activateSuppliersTab.run();
                tabBtnSuppliers.requestFocus();
            }
        });
        tabBtnSuppliers.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.LEFT) {
                activateDetailsTab.run();
                tabBtnDetails.requestFocus();
            }
        });

        // --- Fixed Footer ---
        Button btnCancel = new Button("Cancel");
        btnCancel.getStyleClass().add("sk-modal-btn-cancel");
        btnCancel.setOnAction(e -> onClose.run());

        Button btnSave = new Button(submitLabel);
        btnSave.getStyleClass().add("sk-prod-btn-primary");
        btnSave.setGraphic(iconBox(SVG_CHECK, "#FFFFFF", 16.0));

        Label lblShortcutHint = new Label("Ctrl + Enter to save  •  Esc to cancel");
        lblShortcutHint.getStyleClass().add("sk-prod-section-subtitle");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        HBox footer = new HBox(12.0, lblShortcutHint, footerSpacer, btnCancel, btnSave);
        footer.getStyleClass().add("sk-prod-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        btnSave.setOnAction(e -> {
            hideError.run();

            String name = txtName.getText() != null ? txtName.getText().trim() : "";
            if (name.isBlank()) {
                activateDetailsTab.run();
                showError.accept("Product Name is required.");
                txtName.requestFocus();
                return;
            }

            Money tp = null;
            Money cost = null;
            Money mrp = null;
            Money selling = null;
            int packSize = 1;
            int threshold = 5;
            int openingStock = 0;

            Control focusTarget = null;
            try {
                focusTarget = txtTp;
                tp = parseStrictNonNegativeMoney(txtTp.getText(), "Trade Price (TP)");
                focusTarget = txtCost;
                cost = parseStrictNonNegativeMoney(txtCost.getText(), isEdit ? "Avg Inventory Cost" : "Initial Cost Price");
                focusTarget = txtMrp;
                mrp = parseStrictNonNegativeMoney(txtMrp.getText(), "MRP");
                focusTarget = txtSelling;
                selling = parseStrictNonNegativeMoney(txtSelling.getText(), "Default Selling Price");
                focusTarget = txtPackSize;
                packSize = parseStrictIntRange(txtPackSize.getText(), 1, 1, "Pack Size");
                focusTarget = txtThreshold;
                threshold = parseStrictIntRange(txtThreshold.getText(), 5, 0, "Low Stock Threshold");
                if (!isEdit) {
                    focusTarget = txtOpeningStock;
                    openingStock = parseStrictIntRange(txtOpeningStock.getText(), 0, 0, "Opening Stock");
                }
            } catch (IllegalArgumentException ex) {
                activateDetailsTab.run();
                showError.accept(ex.getMessage());
                if (focusTarget != null) {
                    focusTarget.requestFocus();
                }
                return;
            }

            // Preserve clean business separation while honoring blank optional inputs safely
            Money resolvedCost = cost.isPositive() ? cost : (tp.isPositive() ? tp : Money.ZERO);
            Money resolvedTp = tp.isPositive() ? tp : resolvedCost;
            Money resolvedMrp = mrp.isPositive() ? mrp : selling;

            Long categoryId = cmbCategory.getValue() != null ? cmbCategory.getValue().id() : null;
            String baseUnit = safeUnitValue(cmbBaseUnit, "pcs");
            String purUnit = safeUnitValue(cmbPurchaseUnit, baseUnit);
            String barcode = trimToNull(txtBarcode.getText());
            String sku = trimToNull(txtSku.getText());
            String imgPath = trimToNull(imgParts.selectedPathRef().get());

            ProductCreateDto createDto = isEdit ? null : new ProductCreateDto(
                    businessId, categoryId, name, barcode, sku,
                    baseUnit, resolvedCost, selling, threshold, openingStock,
                    "Initial Opening Stock", imgPath, resolvedTp, resolvedMrp, purUnit, packSize
            );
            ProductUpdateDto updateDto = isEdit ? new ProductUpdateDto(
                    existing.id(), categoryId, name, barcode, sku,
                    baseUnit, resolvedCost, selling, threshold, imgPath,
                    resolvedTp, resolvedMrp, purUnit, packSize
            ) : null;

            SavePayload payload = new SavePayload(
                    isEdit ? existing.id() : null,
                    createDto,
                    updateDto,
                    new ArrayList<>(draftLinks),
                    new ArrayList<>(deletedMappingIds)
            );

            btnSave.setDisable(true);
            btnCancel.setDisable(true);
            btnSave.setText("Saving...");

            onValidSubmit.submit(
                    payload,
                    errMsg -> showError.accept(errMsg),
                    () -> {
                        btnSave.setDisable(false);
                        btnCancel.setDisable(false);
                        btnSave.setText(submitLabel);
                    }
            );
        });

        // Assemble modal vertical shell: fixed header + tabs + error bar + scrollable body + fixed footer
        VBox topArea = new VBox(0, headerStack, tabBar, errorHolder);
        topArea.setStyle("-fx-background-color: #FFFFFF;");

        BorderPane modalFrame = new BorderPane();
        modalFrame.setTop(topArea);
        modalFrame.setCenter(bodyScroll);
        modalFrame.setBottom(footer);

        Rectangle clipRect = new Rectangle();
        clipRect.widthProperty().bind(modalFrame.widthProperty());
        clipRect.heightProperty().bind(modalFrame.heightProperty());
        clipRect.setArcWidth(28.0);
        clipRect.setArcHeight(28.0);
        modalFrame.setClip(clipRect);

        StackPane cardShell = new StackPane(modalFrame);
        cardShell.getStyleClass().add("sk-prod-modal-card");
        cardShell.setPrefSize(1020.0, 740.0);

        StackPane rootPane = new StackPane(cardShell);
        rootPane.setPadding(new Insets(18.0));
        rootPane.setStyle("-fx-background-color: transparent;");

        return new ModalParts(
                rootPane, cardShell, headerStack, errorBanner, lblError,
                tabBtnDetails, tabBtnSuppliers, bodyScroll,
                txtName, cmbCategory, btnNewCategory, txtBarcode, txtSku,
                cmbBaseUnit, cmbPurchaseUnit, txtPackSize, lblPackHint,
                txtTp, txtCost, txtMrp, txtSelling,
                txtThreshold, txtOpeningStock,
                lblEstProfit, lblEstMargin, lblEstMarkup, warnBanner, lblPriceWarning,
                imgParts.preview(), imgParts.emptyBox(), imgParts.presetCombo(),
                imgParts.btnBrowse(), imgParts.btnClear(), imgParts.selectedPathRef(),
                lblSupInfoCount, btnJumpToSuppliers,
                supTab.cmbSupplier(), supTab.btnNewSupplier(),
                supTab.txtTp(), supTab.txtVendorSku(), supTab.txtUnit(), supTab.txtPack(),
                supTab.txtMoq(), supTab.txtLeadDays(), supTab.chkPreferred(),
                supTab.btnLink(), supTab.btnClearForm(), supTab.tblLinks(), draftLinks, deletedMappingIds,
                btnCancel, btnSave
        );
    }

    private static StackPane buildHeader(String title, String subtitle, Runnable onClose) {
        StackPane badge = new StackPane(iconBox(SVG_CUBE_FILL, "#FFFFFF", 22.0));
        badge.getStyleClass().add("sk-prod-header-badge");

        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-size: 19px; -fx-font-weight: 800; -fx-text-fill: #18283B;");

        Label lblSubtitle = new Label(subtitle);
        lblSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #68778A;");
        lblSubtitle.setWrapText(true);

        VBox titleBox = new VBox(2.0, lblTitle, lblSubtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button btnClose = new Button();
        btnClose.setGraphic(iconBox(SVG_CLOSE, "#68778A", 16.0));
        btnClose.getStyleClass().add("sk-modal-close-btn");
        btnClose.setTooltip(new Tooltip("Close dialog (Esc)"));
        btnClose.setOnAction(e -> onClose.run());

        HBox row = new HBox(14.0, badge, titleBox, btnClose);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16.0, 22.0, 14.0, 24.0));

        // Decorative grocery wave & subtle top-right basket artwork
        SVGPath wave = new SVGPath();
        wave.setContent("M 0,0 L 320,0 L 320,76 C 210,70 110,36 0,0 Z");
        wave.setFill(Color.web("#D4F2DB", 0.55));
        wave.setMouseTransparent(true);
        StackPane.setAlignment(wave, Pos.TOP_RIGHT);

        StackPane headerStack = new StackPane(wave);
        ImageView basketArt = loadResourceImage("/com/smartkhata/ui/images/grocery/hero_grocery_basket_2x.png", 68.0);
        if (basketArt != null) {
            basketArt.setOpacity(0.88);
            StackPane.setAlignment(basketArt, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(basketArt, new Insets(0, 62, 0, 0));
            headerStack.getChildren().add(basketArt);
        }
        headerStack.getChildren().add(row);
        headerStack.getStyleClass().add("sk-prod-header");
        return headerStack;
    }

    private static VBox buildProductInfoCard(
            TextField txtName,
            ComboBox<Category> cmbCategory,
            Button btnNewCategory,
            TextField txtBarcode,
            TextField txtSku,
            ComboBox<String> cmbBaseUnit,
            ComboBox<String> cmbPurchaseUnit,
            TextField txtPackSize,
            Label lblPackHint
    ) {
        HBox sectionHeader = buildSectionHeader(
                SVG_DOC, "Product Information", "Basic details to identify and organize the product."
        );

        VBox nameFieldBox = labeledControl("Product Name", true, wrapFieldWithIcon(SVG_TAG, "#68778A", txtName));

        HBox catRow = new HBox(8.0, cmbCategory, btnNewCategory);
        HBox.setHgrow(cmbCategory, Priority.ALWAYS);
        catRow.setAlignment(Pos.CENTER_LEFT);
        VBox catBox = labeledControl("Category", false, catRow);
        VBox barcodeBox = labeledControl("Barcode / Scan", false, wrapFieldWithIcon(SVG_BARCODE, "#68778A", txtBarcode));

        VBox skuBox = labeledControl("SKU", false, wrapFieldWithIcon(SVG_CUBE_FILL, "#68778A", txtSku));
        VBox baseUnitBox = labeledControl("Base Sale Unit", false, cmbBaseUnit);

        VBox purUnitBox = labeledControl("Purchase Unit / Pack", false, cmbPurchaseUnit);
        HBox packWrap = wrapFieldWithTrailingBadge(txtPackSize, lblPackHint);
        VBox packSizeBox = labeledControl("Pack Size (Base Units per Purchase Pack)", false, packWrap);

        GridPane grid = createTwoColGrid();
        grid.add(catBox, 0, 0);
        grid.add(barcodeBox, 1, 0);
        grid.add(skuBox, 0, 1);
        grid.add(baseUnitBox, 1, 1);
        grid.add(purUnitBox, 0, 2);
        grid.add(packSizeBox, 1, 2);

        VBox card = new VBox(12.0, sectionHeader, nameFieldBox, grid);
        card.getStyleClass().add("sk-prod-section-card");
        return card;
    }

    private static VBox buildPricingCard(
            boolean isEdit,
            TextField txtTp,
            TextField txtCost,
            TextField txtMrp,
            TextField txtSelling
    ) {
        HBox sectionHeader = buildSectionHeader(
                SVG_TAG, "Pricing (BDT)", "Set trade price, cost, MRP and selling price separately."
        );

        GridPane grid = createTwoColGrid();
        grid.add(labeledControl("Trade Price (TP BDT)", false, wrapFieldWithIcon(SVG_COINS, "#68778A", txtTp)), 0, 0);
        grid.add(labeledControl(
                isEdit ? "Avg Inventory Cost (BDT)" : "Initial Cost Price (BDT)", false,
                wrapFieldWithIcon(SVG_COINS, "#68778A", txtCost)), 1, 0);
        grid.add(labeledControl("MRP (Max Retail BDT)", false, wrapFieldWithIcon(SVG_COINS, "#68778A", txtMrp)), 0, 1);
        grid.add(labeledControl("Default Selling Price (BDT)", false, wrapFieldWithIcon(SVG_COINS, "#68778A", txtSelling)), 1, 1);

        VBox card = new VBox(12.0, sectionHeader, grid);
        card.getStyleClass().add("sk-prod-section-card");
        return card;
    }

    private static VBox buildStockSettingsCard(
            boolean isEdit,
            TextField txtThreshold,
            Label lblThresholdUnit,
            TextField txtOpeningStock,
            Label lblOpeningUnit
    ) {
        HBox sectionHeader = buildSectionHeader(
                SVG_CUBE_FILL, "Stock Settings", "Control low-stock alerts and initial opening stock."
        );

        GridPane grid = createTwoColGrid();
        VBox thresholdBox = labeledControl(
                "Low Stock Threshold", false,
                wrapFieldWithIconAndBadge(SVG_BELL, "#68778A", txtThreshold, lblThresholdUnit)
        );
        grid.add(thresholdBox, 0, 0);

        if (!isEdit) {
            VBox openingBox = labeledControl(
                    "Opening Stock (Base Unit)", false,
                    wrapFieldWithIconAndBadge(SVG_CUBE_FILL, "#68778A", txtOpeningStock, lblOpeningUnit)
            );
            grid.add(openingBox, 1, 0);
        } else {
            Label infoTitle = new Label("Stock Adjustments");
            infoTitle.getStyleClass().add("sk-prod-field-label");
            Label infoPill = new Label("Live stock is managed via GRN receipts and Stock Adjustments.");
            infoPill.getStyleClass().add("sk-prod-section-subtitle");
            infoPill.setWrapText(true);
            HBox pillWrap = new HBox(8.0, iconBox(SVG_INFO, "#008A32", 14.0), infoPill);
            pillWrap.getStyleClass().add("sk-modal-info-pill");
            pillWrap.setAlignment(Pos.CENTER_LEFT);
            VBox infoBox = new VBox(5.0, infoTitle, pillWrap);
            grid.add(infoBox, 1, 0);
        }

        VBox card = new VBox(12.0, sectionHeader, grid);
        card.getStyleClass().add("sk-prod-section-card");
        return card;
    }

    private static VBox buildAnalyticsCard(
            Label lblEstProfit,
            Label lblEstMargin,
            Label lblEstMarkup,
            Label lblAnalyticsNote,
            HBox warnBanner
    ) {
        StackPane chartIconBadge = new StackPane(iconBox(SVG_CHART, "#008A32", 18.0));
        chartIconBadge.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 8; -fx-border-color: #BCE3C6; -fx-border-radius: 8;");
        chartIconBadge.setPrefSize(32, 32);
        chartIconBadge.setMinSize(32, 32);

        Label title = new Label("Estimated Pricing Analytics");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: 800; -fx-text-fill: #075C32;");
        VBox titleArea = new VBox(1.0, title, lblAnalyticsNote);

        VBox metricProfit = buildMetricCell(SVG_COINS, "Est. Profit", lblEstProfit);
        VBox metricMargin = buildMetricCell(SVG_TAG, "Margin", lblEstMargin);
        VBox metricMarkup = buildMetricCell(SVG_TREND_UP, "Markup", lblEstMarkup);

        HBox metricsRow = new HBox(20.0, metricProfit, metricMargin, metricMarkup);
        metricsRow.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topRow = new HBox(12.0, chartIconBadge, titleArea, spacer, metricsRow);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8.0, topRow, warnBanner);
        card.getStyleClass().add("sk-prod-analytics-card");
        return card;
    }

    private static VBox buildMetricCell(String iconSvg, String labelText, Label valueLabel) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #68778A;");
        HBox top = new HBox(5.0, iconBox(iconSvg, "#008A32", 12.0), lbl);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(1.0, top, valueLabel);
        box.setMinWidth(74.0);
        return box;
    }

    private record ImageWidgetParts(
            VBox card,
            ImageView preview,
            VBox emptyBox,
            ComboBox<String> presetCombo,
            Button btnBrowse,
            Button btnClear,
            AtomicReference<String> selectedPathRef
    ) {}

    private static ImageWidgetParts buildImageCard(
            String initialPath,
            TextField nameField,
            ComboBox<Category> catCombo
    ) {
        HBox header = buildSectionHeader(SVG_IMAGE, "Product Image", "Add a product photo for quick recognition.");
        AtomicReference<String> pathRef = new AtomicReference<>(trimToNull(initialPath));

        ImageView preview = new ImageView();
        preview.setFitWidth(124.0);
        preview.setFitHeight(124.0);
        preview.setPreserveRatio(true);
        preview.setSmooth(true);

        StackPane emptyIconCircle = new StackPane(iconBox(SVG_CUBE, "#008A32", 26.0));
        emptyIconCircle.setStyle("-fx-background-color: #E2F5E6; -fx-background-radius: 26; -fx-border-color: #B4E2BE; -fx-border-radius: 26;");
        emptyIconCircle.setPrefSize(52, 52);
        emptyIconCircle.setMinSize(52, 52);
        emptyIconCircle.setMaxSize(52, 52);

        Label emptyTitle = new Label("No image selected");
        emptyTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #18283B;");
        Label emptySub = new Label("Browse or type name to auto-match");
        emptySub.setStyle("-fx-font-size: 11px; -fx-text-fill: #68778A;");
        emptySub.setWrapText(true);
        VBox emptyBox = new VBox(6.0, emptyIconCircle, emptyTitle, emptySub);
        emptyBox.setAlignment(Pos.CENTER);

        StackPane dropFrame = new StackPane(emptyBox, preview);
        dropFrame.setMinHeight(178.0);
        dropFrame.setPrefHeight(178.0);
        dropFrame.getStyleClass().add("sk-prod-image-drop");

        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().add(AUTO_IMAGE_LABEL);
        presetCombo.getItems().addAll(ProductImageResolver.PRESET_IMAGES.keySet());
        presetCombo.getStyleClass().add("sk-prod-combo");
        presetCombo.setMaxWidth(Double.MAX_VALUE);

        String matchedPreset = ProductImageResolver.findPresetLabelForPath(initialPath);
        if (initialPath == null || initialPath.isBlank()) {
            presetCombo.getSelectionModel().select(AUTO_IMAGE_LABEL);
        } else if (matchedPreset != null) {
            presetCombo.getSelectionModel().select(matchedPreset);
        } else {
            presetCombo.getItems().add(CUSTOM_IMAGE_LABEL);
            presetCombo.getSelectionModel().select(CUSTOM_IMAGE_LABEL);
        }

        Runnable refreshPreview = () -> {
            String curPath = pathRef.get();
            String catName = catCombo.getValue() != null ? catCombo.getValue().name() : "";
            String prodName = nameField.getText() != null ? nameField.getText() : "";
            boolean showEmpty = false;
            if (curPath == null) {
                String inferred = ProductImageResolver.inferPresetPath(prodName, catName);
                // Show clean empty placeholder unless product name/category matched a specific grocery item
                showEmpty = "/com/smartkhata/ui/images/grocery/inventory_cube.png".equals(inferred);
            }

            dropFrame.getStyleClass().removeAll("sk-prod-image-drop", "sk-prod-image-drop-filled");
            if (showEmpty) {
                preview.setImage(null);
                preview.setVisible(false);
                emptyBox.setVisible(true);
                dropFrame.getStyleClass().add("sk-prod-image-drop");
            } else {
                Image resolved = ProductImageResolver.resolve(curPath, prodName, catName);
                preview.setImage(resolved);
                preview.setVisible(resolved != null);
                emptyBox.setVisible(resolved == null);
                dropFrame.getStyleClass().add(resolved != null ? "sk-prod-image-drop-filled" : "sk-prod-image-drop");
            }
        };

        presetCombo.valueProperty().addListener((o, oldVal, chosen) -> {
            if (chosen == null || AUTO_IMAGE_LABEL.equals(chosen)) {
                pathRef.set(null);
            } else if (!CUSTOM_IMAGE_LABEL.equals(chosen)) {
                pathRef.set(ProductImageResolver.PRESET_IMAGES.get(chosen));
            }
            refreshPreview.run();
        });
        nameField.textProperty().addListener((o, a, b) -> {
            if (pathRef.get() == null) {
                refreshPreview.run();
            }
        });
        catCombo.valueProperty().addListener((o, a, b) -> {
            if (pathRef.get() == null) {
                refreshPreview.run();
            }
        });

        Button btnBrowse = new Button("Browse...");
        btnBrowse.setGraphic(iconBox(SVG_FOLDER, "#18283B", 14.0));
        btnBrowse.getStyleClass().add("sk-prod-btn-outline");
        btnBrowse.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnBrowse, Priority.ALWAYS);

        btnBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select Product Image");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Image Files (*.png, *.jpg, *.jpeg, *.svg)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg")
            );
            File chosen = chooser.showOpenDialog(btnBrowse.getScene() != null ? btnBrowse.getScene().getWindow() : null);
            if (chosen != null && chosen.exists()) {
                try {
                    Path imagesDir = AppConfig.getProductImagesDir();
                    Files.createDirectories(imagesDir);
                    String rawName = chosen.getName();
                    String ext = "";
                    int dot = rawName.lastIndexOf('.');
                    if (dot >= 0) ext = rawName.substring(dot).toLowerCase();
                    String safeName = "prod_" + System.currentTimeMillis() + ext;
                    Path dest = imagesDir.resolve(safeName);
                    Files.copy(chosen.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                    pathRef.set(safeName);
                    if (!presetCombo.getItems().contains(CUSTOM_IMAGE_LABEL)) {
                        presetCombo.getItems().add(CUSTOM_IMAGE_LABEL);
                    }
                    presetCombo.getSelectionModel().select(CUSTOM_IMAGE_LABEL);
                    refreshPreview.run();
                } catch (Exception ex) {
                    AlertHelper.showStartupError(null, "Image Upload Failed",
                            "Could not copy image file: " + ex.getMessage(), ex);
                }
            }
        });

        Button btnClear = new Button("Clear");
        btnClear.setGraphic(iconBox(SVG_TRASH, "#C62828", 14.0));
        btnClear.getStyleClass().add("sk-prod-btn-clear");
        btnClear.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnClear, Priority.ALWAYS);
        btnClear.setOnAction(e -> {
            pathRef.set(null);
            presetCombo.getSelectionModel().select(AUTO_IMAGE_LABEL);
            refreshPreview.run();
        });

        HBox actionRow = new HBox(10.0, btnBrowse, btnClear);
        refreshPreview.run();

        VBox card = new VBox(12.0, header, dropFrame, presetCombo, actionRow);
        card.getStyleClass().add("sk-prod-section-card");
        return new ImageWidgetParts(card, preview, emptyBox, presetCombo, btnBrowse, btnClear, pathRef);
    }

    private static VBox buildLinkedSuppliersInfoCard(Label lblCountBadge, Button btnJump) {
        HBox topTitleRow = new HBox(8.0, iconBox(SVG_SUPPLIERS, "#008A32", 18.0));
        Label title = new Label("Linked Suppliers");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #18283B;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        topTitleRow.getChildren().addAll(title, sp, lblCountBadge);
        topTitleRow.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label("Each supplier maintains its own trade price, MOQ, purchase unit, and lead time.");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569;");
        desc.setWrapText(true);

        VBox shopIllust = new VBox(iconBox(SVG_SHOP, "#6CC085", 42.0));
        shopIllust.setAlignment(Pos.BOTTOM_RIGHT);
        shopIllust.setOpacity(0.85);

        HBox midRow = new HBox(10.0, desc, shopIllust);
        HBox.setHgrow(desc, Priority.ALWAYS);
        midRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10.0, topTitleRow, midRow, btnJump);
        card.getStyleClass().add("sk-prod-sup-info-card");
        return card;
    }

    private record SupplierTabParts(
            VBox container,
            ComboBox<Supplier> cmbSupplier,
            Button btnNewSupplier,
            TextField txtTp,
            TextField txtVendorSku,
            TextField txtUnit,
            TextField txtPack,
            TextField txtMoq,
            TextField txtLeadDays,
            CheckBox chkPreferred,
            Button btnLink,
            Button btnClearForm,
            TableView<DraftSupplierLink> tblLinks
    ) {}

    private static SupplierTabParts buildSuppliersTab(
            List<Supplier> initialSuppliers,
            ObservableList<DraftSupplierLink> draftLinks,
            List<Long> deletedMappingIds,
            ComboBox<String> productPurUnitCombo,
            TextField productPackField,
            Consumer<ComboBox<Supplier>> onQuickAddSupplier,
            Consumer<String> showError,
            Runnable hideError
    ) {
        // --- Card 1: Add / Edit Supplier Link Form ---
        HBox formHeader = buildSectionHeader(
                SVG_SUPPLIERS,
                "Link Supplier & Trade Pricing",
                "Associate one or more suppliers to this product. Changing one supplier's TP never alters another supplier's price."
        );

        ComboBox<Supplier> cmbSupplier = new ComboBox<>();
        cmbSupplier.getStyleClass().add("sk-prod-combo");
        cmbSupplier.setMaxWidth(Double.MAX_VALUE);
        cmbSupplier.setPromptText("Select a supplier");
        cmbSupplier.setConverter(new StringConverter<>() {
            @Override
            public String toString(Supplier s) {
                if (s == null) return "";
                return s.name() + (s.supplierCode() != null && !s.supplierCode().isBlank() ? " (" + s.supplierCode() + ")" : "");
            }

            @Override
            public Supplier fromString(String s) {
                return null;
            }
        });
        if (initialSuppliers != null) {
            cmbSupplier.setItems(FXCollections.observableArrayList(initialSuppliers));
            if (!initialSuppliers.isEmpty()) {
                cmbSupplier.getSelectionModel().selectFirst();
            }
        }

        Button btnNewSupplier = new Button("+ Create New Supplier");
        btnNewSupplier.getStyleClass().add("sk-prod-btn-new");
        btnNewSupplier.setMinWidth(Region.USE_PREF_SIZE);
        btnNewSupplier.setOnAction(e -> {
            if (onQuickAddSupplier != null) {
                onQuickAddSupplier.accept(cmbSupplier);
            }
        });

        HBox supplierSelectRow = new HBox(10.0, cmbSupplier, btnNewSupplier);
        HBox.setHgrow(cmbSupplier, Priority.ALWAYS);
        supplierSelectRow.setAlignment(Pos.CENTER_LEFT);

        TextField txtTp = new TextField();
        txtTp.setPromptText("e.g. 720.00");
        txtTp.getStyleClass().add("sk-prod-input");

        TextField txtVendorSku = new TextField();
        txtVendorSku.setPromptText("Supplier code / SKU");
        txtVendorSku.getStyleClass().add("sk-prod-input");

        TextField txtUnit = new TextField(safeUnitValue(productPurUnitCombo, "pcs"));
        txtUnit.setPromptText("e.g. carton / pkt");
        txtUnit.getStyleClass().add("sk-prod-input");

        TextField txtPack = new TextField(productPackField.getText().isBlank() ? "1" : productPackField.getText());
        txtPack.setPromptText("1");
        txtPack.getStyleClass().add("sk-prod-input");

        TextField txtMoq = new TextField("1");
        txtMoq.setPromptText("1");
        txtMoq.getStyleClass().add("sk-prod-input");

        TextField txtLeadDays = new TextField("1");
        txtLeadDays.setPromptText("1");
        txtLeadDays.getStyleClass().add("sk-prod-input");

        CheckBox chkPreferred = new CheckBox("Set as Preferred / Default Supplier");
        chkPreferred.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #18283B;");

        Button btnClearForm = new Button("Reset Form");
        btnClearForm.getStyleClass().add("sk-prod-btn-outline");
        btnClearForm.setVisible(false);
        btnClearForm.setManaged(false);

        Button btnLink = new Button("Add Supplier Link");
        btnLink.setGraphic(iconBox(SVG_CHECK, "#FFFFFF", 15.0));
        btnLink.getStyleClass().add("sk-prod-btn-primary");
        btnLink.setMinWidth(175.0);

        AtomicInteger editingRowIndex = new AtomicInteger(-1);

        GridPane fieldsGrid = new GridPane();
        fieldsGrid.setHgap(14.0);
        fieldsGrid.setVgap(12.0);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(33.33);
            c.setHgrow(Priority.ALWAYS);
            fieldsGrid.getColumnConstraints().add(c);
        }

        fieldsGrid.add(labeledControl("Select Supplier *", false, supplierSelectRow), 0, 0, 2, 1);
        fieldsGrid.add(labeledControl("Supplier Trade Price (TP BDT) *", false, wrapFieldWithIcon(SVG_COINS, "#68778A", txtTp)), 2, 0);

        fieldsGrid.add(labeledControl("Supplier SKU / Vendor Code", false, wrapFieldWithIcon(SVG_TAG, "#68778A", txtVendorSku)), 0, 1);
        HBox unitPackRow = new HBox(8.0,
                wrapFieldWithIcon(SVG_CUBE_FILL, "#68778A", txtUnit),
                wrapFieldWithTrailingBadge(txtPack, createSmallTag("Pack"))
        );
        HBox.setHgrow(unitPackRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(unitPackRow.getChildren().get(1), Priority.ALWAYS);
        fieldsGrid.add(labeledControl("Purchase Unit & Pack Size", false, unitPackRow), 1, 1);

        HBox moqLeadRow = new HBox(8.0,
                wrapFieldWithTrailingBadge(txtMoq, createSmallTag("MOQ")),
                wrapFieldWithTrailingBadge(txtLeadDays, createSmallTag("Days"))
        );
        HBox.setHgrow(moqLeadRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(moqLeadRow.getChildren().get(1), Priority.ALWAYS);
        fieldsGrid.add(labeledControl("Min Order Qty & Lead Time", false, moqLeadRow), 2, 1);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox formActionBar = new HBox(10.0, chkPreferred, spacer, btnClearForm, btnLink);
        formActionBar.setAlignment(Pos.CENTER_LEFT);
        formActionBar.setPadding(new Insets(4, 0, 0, 0));

        VBox formCard = new VBox(12.0, formHeader, fieldsGrid, formActionBar);
        formCard.getStyleClass().add("sk-prod-section-card");

        // --- Card 2: Supplier Mapping Table ---
        TableView<DraftSupplierLink> tblLinks = new TableView<>(draftLinks);
        tblLinks.getStyleClass().add("sk-prod-table");
        tblLinks.setPrefHeight(270.0);
        tblLinks.setMinHeight(220.0);
        tblLinks.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        Runnable resetLinkEditor = () -> {
            editingRowIndex.set(-1);
            txtTp.clear();
            txtVendorSku.clear();
            txtUnit.setText(safeUnitValue(productPurUnitCombo, "pcs"));
            txtPack.setText(productPackField.getText().isBlank() ? "1" : productPackField.getText());
            txtMoq.setText("1");
            txtLeadDays.setText("1");
            chkPreferred.setSelected(false);
            btnLink.setText("Add Supplier Link");
            btnClearForm.setVisible(false);
            btnClearForm.setManaged(false);
            tblLinks.getSelectionModel().clearSelection();
        };
        btnClearForm.setOnAction(e -> {
            hideError.run();
            resetLinkEditor.run();
        });

        Runnable beginEditingLink = () -> {
            int idx = tblLinks.getSelectionModel().getSelectedIndex();
            DraftSupplierLink sel = idx >= 0 ? draftLinks.get(idx) : null;
            if (sel == null) return;
            hideError.run();
            editingRowIndex.set(idx);
            cmbSupplier.getItems().stream()
                    .filter(s -> s.id().equals(sel.supplierId()))
                    .findFirst()
                    .ifPresent(s -> cmbSupplier.getSelectionModel().select(s));
            txtTp.setText(sel.tradePrice() != null ? sel.tradePrice().toBdt().toPlainString() : "0");
            txtVendorSku.setText(nonNull(sel.supplierProductCode()));
            txtUnit.setText(sel.purchaseUnit() != null ? sel.purchaseUnit() : "pcs");
            txtPack.setText(String.valueOf(Math.max(1, sel.packSize())));
            txtMoq.setText(String.valueOf(Math.max(1, sel.minOrderQty())));
            txtLeadDays.setText(String.valueOf(Math.max(0, sel.leadTimeDays())));
            chkPreferred.setSelected(sel.isPreferred());
            btnLink.setText("Update Supplier Link");
            btnClearForm.setVisible(true);
            btnClearForm.setManaged(true);
            txtTp.requestFocus();
        };

        TableColumn<DraftSupplierLink, String> colSup = new TableColumn<>("Supplier");
        colSup.setCellValueFactory(c -> {
            String code = c.getValue().supplierProductCode();
            String label = c.getValue().supplierName()
                    + (code != null && !code.isBlank() ? " [" + code + "]" : "");
            return new SimpleStringProperty(label);
        });
        colSup.setMinWidth(160);

        TableColumn<DraftSupplierLink, String> colTp = new TableColumn<>("Trade Price");
        colTp.setCellValueFactory(c -> new SimpleStringProperty(BengaliMoneyFormatter.format(c.getValue().tradePrice())));
        colTp.setMinWidth(100);

        TableColumn<DraftSupplierLink, String> colUnit = new TableColumn<>("Unit / Pack");
        colUnit.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().purchaseUnit() + " x" + c.getValue().packSize()));
        colUnit.setMinWidth(95);

        TableColumn<DraftSupplierLink, String> colMoq = new TableColumn<>("MOQ");
        colMoq.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().minOrderQty())));
        colMoq.setMinWidth(60);

        TableColumn<DraftSupplierLink, String> colLead = new TableColumn<>("Lead Time");
        colLead.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().leadTimeDays() + " days"));
        colLead.setMinWidth(75);

        TableColumn<DraftSupplierLink, String> colPref = new TableColumn<>("Preferred");
        colPref.setMinWidth(95);
        colPref.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isPreferred() ? "Preferred" : "Standard"));
        colPref.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                DraftSupplierLink row = getTableRow().getItem();
                Label badge = new Label(row.isPreferred() ? "Preferred" : "Secondary");
                badge.getStyleClass().setAll(row.isPreferred() ? "sk-prod-badge-preferred" : "sk-prod-badge-secondary");
                setGraphic(badge);
                setText(null);
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        TableColumn<DraftSupplierLink, Void> colActions = new TableColumn<>("Actions");
        colActions.setMinWidth(138);
        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnRowEdit = new Button("Edit");
            private final Button btnRowDel = new Button("Remove");
            private final HBox box = new HBox(6.0, btnRowEdit, btnRowDel);

            {
                btnRowEdit.setGraphic(iconBox(SVG_EDIT, "#008A32", 12.0));
                btnRowEdit.getStyleClass().add("sk-prod-row-action-btn");
                btnRowDel.setGraphic(iconBox(SVG_TRASH, "#C62828", 12.0));
                btnRowDel.getStyleClass().add("sk-prod-row-delete-btn");
                box.setAlignment(Pos.CENTER_LEFT);

                btnRowEdit.setOnAction(ev -> {
                    getTableView().getSelectionModel().select(getIndex());
                    beginEditingLink.run();
                });
                btnRowDel.setOnAction(ev -> {
                    int idx = getIndex();
                    if (idx < 0 || idx >= draftLinks.size()) return;
                    DraftSupplierLink removed = draftLinks.get(idx);
                    if (removed.mappingId() != null) {
                        deletedMappingIds.add(removed.mappingId());
                    }
                    draftLinks.remove(idx);
                    if (editingRowIndex.get() == idx) {
                        resetLinkEditor.run();
                    } else if (editingRowIndex.get() > idx) {
                        editingRowIndex.decrementAndGet();
                    }
                    hideError.run();
                });
            }

            @Override
            protected void updateItem(Void unused, boolean empty) {
                super.updateItem(unused, empty);
                setGraphic(empty ? null : box);
            }
        });

        tblLinks.getColumns().addAll(colSup, colTp, colUnit, colMoq, colLead, colPref, colActions);

        // Empty State matching reference spec
        StackPane emptyStoreBadge = new StackPane(iconBox(SVG_SHOP, "#008A32", 24.0));
        emptyStoreBadge.setStyle("-fx-background-color: #E8F8EA; -fx-border-color: #B4E2BE; -fx-border-radius: 26; -fx-background-radius: 26;");
        emptyStoreBadge.setPrefSize(52, 52);
        emptyStoreBadge.setMaxSize(52, 52);
        Label empTitle = new Label("No suppliers linked yet");
        empTitle.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 800; -fx-text-fill: #18283B;");
        Label empDesc = new Label("Select a supplier and enter its trade price to link it to this product.");
        empDesc.setStyle("-fx-font-size: 12px; -fx-text-fill: #68778A;");
        VBox emptyState = new VBox(6.0, emptyStoreBadge, empTitle, empDesc);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(24));
        tblLinks.setPlaceholder(emptyState);

        btnLink.setOnAction(ev -> {
            hideError.run();
            Supplier sup = cmbSupplier.getValue();
            if (sup == null) {
                showError.accept("Please select or create a supplier before linking.");
                cmbSupplier.requestFocus();
                return;
            }
            Money linkTp;
            int packSize;
            int moq;
            int lead;
            try {
                if (txtTp.getText() == null || txtTp.getText().trim().isBlank()) {
                    throw new IllegalArgumentException("Supplier Trade Price (TP) is required.");
                }
                linkTp = parseStrictNonNegativeMoney(txtTp.getText(), "Supplier Trade Price (TP)");
                packSize = parseStrictIntRange(txtPack.getText(), 1, 1, "Supplier Pack Size");
                moq = parseStrictIntRange(txtMoq.getText(), 1, 1, "Minimum Order Qty (MOQ)");
                lead = parseStrictIntRange(txtLeadDays.getText(), 1, 0, "Lead Time (Days)");
            } catch (IllegalArgumentException ex) {
                showError.accept(ex.getMessage());
                return;
            }

            int editIdx = editingRowIndex.get();
            for (int i = 0; i < draftLinks.size(); i++) {
                if (i != editIdx && draftLinks.get(i).supplierId() == sup.id()) {
                    showError.accept("Supplier '" + sup.name() + "' is already linked to this product. Click Edit on its row below to update its trade price.");
                    return;
                }
            }

            String pUnit = txtUnit.getText() != null && !txtUnit.getText().isBlank()
                    ? txtUnit.getText().trim()
                    : safeUnitValue(productPurUnitCombo, "pcs");
            boolean pref = chkPreferred.isSelected();

            // Enforce single preferred supplier per product cleanly
            if (pref) {
                for (int i = 0; i < draftLinks.size(); i++) {
                    if (i != editIdx && draftLinks.get(i).isPreferred()) {
                        DraftSupplierLink cur = draftLinks.get(i);
                        draftLinks.set(i, new DraftSupplierLink(
                                cur.mappingId(), cur.supplierId(), cur.supplierName(), cur.tradePrice(),
                                cur.supplierProductCode(), cur.minOrderQty(), cur.purchaseUnit(), cur.packSize(),
                                false, cur.leadTimeDays(), cur.isActive()
                        ));
                    }
                }
            }

            Long mappingId = (editIdx >= 0 && editIdx < draftLinks.size()) ? draftLinks.get(editIdx).mappingId() : null;
            DraftSupplierLink newEntry = new DraftSupplierLink(
                    mappingId, sup.id(), sup.name(), linkTp, trimToNull(txtVendorSku.getText()),
                    moq, pUnit, packSize, pref, lead, true
            );

            if (editIdx >= 0 && editIdx < draftLinks.size()) {
                draftLinks.set(editIdx, newEntry);
            } else {
                draftLinks.add(newEntry);
            }
            resetLinkEditor.run();
        });

        HBox tableHeader = buildSectionHeader(
                SVG_DOC, "Linked Suppliers & Terms", "Click Edit on any row to adjust that supplier's independent Trade Price or terms."
        );
        VBox tableCard = new VBox(10.0, tableHeader, tblLinks);
        tableCard.getStyleClass().add("sk-prod-section-card");

        VBox container = new VBox(14.0, formCard, tableCard);
        container.setPadding(new Insets(16.0, 22.0, 20.0, 22.0));

        return new SupplierTabParts(
                container, cmbSupplier, btnNewSupplier, txtTp, txtVendorSku,
                txtUnit, txtPack, txtMoq, txtLeadDays, chkPreferred,
                btnLink, btnClearForm, tblLinks
        );
    }

    static void refreshAnalyticsPreview(
            String tpRaw,
            String costRaw,
            String mrpRaw,
            String sellingRaw,
            Label lblEstProfit,
            Label lblEstMargin,
            Label lblEstMarkup,
            Label lblAnalyticsNote,
            HBox warnBanner,
            Label lblPriceWarning
    ) {
        Money tp = tryParseStrictNonNegative(tpRaw);
        Money cost = tryParseStrictNonNegative(costRaw);
        Money mrp = tryParseStrictNonNegative(mrpRaw);
        Money sell = tryParseStrictNonNegative(sellingRaw);

        Money refCost = (cost != null && cost.isPositive()) ? cost : ((tp != null && tp.isPositive()) ? tp : null);

        if (tp == null || cost == null || mrp == null || sell == null) {
            setPlaceholderAnalytics(lblEstProfit, lblEstMargin, lblEstMarkup, lblAnalyticsNote,
                    "Enter valid non-negative prices to calculate estimates.");
        } else if (refCost == null || !sell.isPositive()) {
            setPlaceholderAnalytics(lblEstProfit, lblEstMargin, lblEstMarkup, lblAnalyticsNote,
                    "Enter unit cost (or TP) and default selling price to estimate profit.");
        } else {
            Money profit = PricingCalculator.calculateProfitAmount(sell, refCost);
            double margin = PricingCalculator.calculateProfitMarginPercent(sell, refCost);
            double markup = PricingCalculator.calculateMarkupPercent(sell, refCost);

            lblEstProfit.setText(BengaliMoneyFormatter.format(profit));
            lblEstMargin.setText(String.format("%.1f%%", margin));
            lblEstMarkup.setText(String.format("%.1f%%", markup));

            String color = profit.isNegative() ? "#DC2626" : "#008A32";
            lblEstProfit.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: " + color + ";");
            lblEstMargin.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: " + color + ";");
            lblEstMarkup.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: " + color + ";");
            lblAnalyticsNote.setText("Estimated vs. unit cost (actual sales reports always use historical COGS).");
        }

        List<String> warns = new ArrayList<>();
        if (sell != null && refCost != null && sell.isPositive() && PricingCalculator.isSellingBelowCost(sell, refCost)) {
            warns.add("Selling price is lower than unit cost (" + BengaliMoneyFormatter.format(refCost) + ").");
        }
        if (sell != null && mrp != null && sell.isPositive() && PricingCalculator.isSellingAboveMrp(sell, mrp)) {
            warns.add("Default selling price exceeds MRP (" + BengaliMoneyFormatter.format(mrp) + ").");
        }

        if (!warns.isEmpty()) {
            lblPriceWarning.setText(String.join("  ", warns));
            warnBanner.setVisible(true);
            warnBanner.setManaged(true);
        } else {
            warnBanner.setVisible(false);
            warnBanner.setManaged(false);
        }
    }

    private static void setPlaceholderAnalytics(
            Label lblEstProfit,
            Label lblEstMargin,
            Label lblEstMarkup,
            Label lblNote,
            String noteText
    ) {
        String neutralStyle = "-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #68778A;";
        lblEstProfit.setText("—");
        lblEstMargin.setText("—");
        lblEstMarkup.setText("—");
        lblEstProfit.setStyle(neutralStyle);
        lblEstMargin.setStyle(neutralStyle);
        lblEstMarkup.setStyle(neutralStyle);
        lblNote.setText(noteText);
    }

    private static void promptInlineAddCategory(
            Window owner,
            BiFunction<String, String, Category> creator,
            ComboBox<Category> targetBox
    ) {
        if (creator == null) return;
        TextField txtCatName = new TextField();
        txtCatName.setPromptText("e.g. Beverages, Oil, Spices");
        TextField txtCatDesc = new TextField();
        txtCatDesc.setPromptText("Optional description");

        VBox body = DialogComponents.sectionCard(
                DialogComponents.formField("Category Name", true, "Create a category and select it immediately.",
                        DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, txtCatName)),
                DialogComponents.formField("Description", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtCatDesc))
        );

        Optional<Category> res = SmartKhataDialog.<Category>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("New Category (নতুন ক্যাটাগরি)")
                .subtitle("Organize your store catalog by grouping products.")
                .icon(DialogComponents.SVG_TAG, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(txtCatName)
                .primaryAction("Add Category", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String name = txtCatName.getText() != null ? txtCatName.getText().trim() : "";
                    if (name.isBlank()) {
                        throw new SmartKhataDialog.ValidationFocusException("Category Name cannot be empty.", txtCatName);
                    }
                    return creator.apply(name, DialogComponents.trimToNull(txtCatDesc.getText()));
                })
                .showAndWait();

        res.ifPresent(created -> {
            targetBox.getItems().add(created);
            targetBox.getSelectionModel().select(created);
        });
    }

    private static void promptInlineAddSupplier(
            Window owner,
            long businessId,
            java.util.function.Supplier<List<Supplier>> supplierLoader,
            Function<SupplierCreateDto, Supplier> supplierCreator,
            ComboBox<Supplier> cmbSupplier
    ) {
        if (supplierCreator == null) return;
        Optional<Supplier> created = SupplierFormModal.showAddDialog(owner, businessId, supplierCreator);
        created.ifPresent(newSup -> {
            if (supplierLoader != null) {
                List<Supplier> latest = supplierLoader.get();
                cmbSupplier.setItems(FXCollections.observableArrayList(latest));
            } else {
                cmbSupplier.getItems().add(newSup);
            }
            cmbSupplier.getItems().stream()
                    .filter(s -> s.id().equals(newSup.id()))
                    .findFirst()
                    .ifPresent(s -> cmbSupplier.getSelectionModel().select(s));
        });
    }

    private static void populateCategoryCombo(
            ComboBox<Category> combo,
            List<Category> cats,
            Long selectId
    ) {
        ObservableList<Category> list = FXCollections.observableArrayList();
        Category toSelect = null;
        if (cats != null) {
            for (Category c : cats) {
                list.add(c);
                if (selectId != null && selectId.equals(c.id())) {
                    toSelect = c;
                }
            }
        }
        combo.setItems(list);
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select category" : item.name());
            }
        });
        if (toSelect != null) {
            combo.getSelectionModel().select(toSelect);
        }
    }

    private static void setActiveTabStyle(Button active, Button inactive, String activeSvg, String inactiveSvg) {
        active.getStyleClass().remove("sk-prod-tab-btn-active");
        active.getStyleClass().add("sk-prod-tab-btn-active");
        active.setGraphic(iconBox(activeSvg, "#008A32", 15.0));

        inactive.getStyleClass().remove("sk-prod-tab-btn-active");
        inactive.setGraphic(iconBox(inactiveSvg, "#68778A", 15.0));
    }

    private static GridPane createTwoColGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14.0);
        grid.setVgap(11.0);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50.0);
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50.0);
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);
        return grid;
    }

    private static HBox buildSectionHeader(String svgPath, String titleText, String subtitleText) {
        StackPane iconWrap = new StackPane(iconBox(svgPath, "#008A32", 16.0));
        iconWrap.setStyle("-fx-background-color: #E8F8EA; -fx-background-radius: 7;");
        iconWrap.setPrefSize(28, 28);
        iconWrap.setMinSize(28, 28);

        Label title = new Label(titleText);
        title.getStyleClass().add("sk-prod-section-title");
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("sk-prod-section-subtitle");
        subtitle.setWrapText(true);

        VBox text = new VBox(1.0, title, subtitle);
        HBox row = new HBox(10.0, iconWrap, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox labeledControl(String labelText, boolean required, Node control) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("sk-prod-field-label");
        HBox row = new HBox(4.0, lbl);
        if (required) {
            Label star = new Label("*");
            star.setStyle("-fx-text-fill: #DC2626; -fx-font-weight: 800; -fx-font-size: 12.5px;");
            row.getChildren().add(star);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(5.0, row, control);
    }

    private static final PseudoClass PSEUDO_FOCUSED_WITHIN = PseudoClass.getPseudoClass("focused-within");

    private static HBox attachFocusWithin(HBox wrapper, TextField field) {
        HBox.setHgrow(field, Priority.ALWAYS);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.getStyleClass().add("sk-prod-input-wrap");
        field.focusedProperty().addListener((obs, oldVal, hasFocus) ->
                wrapper.pseudoClassStateChanged(PSEUDO_FOCUSED_WITHIN, hasFocus));
        return wrapper;
    }

    private static HBox wrapFieldWithIcon(String iconSvg, String colorHex, TextField field) {
        return attachFocusWithin(new HBox(8.0, iconBox(iconSvg, colorHex, 14.0), field), field);
    }

    private static HBox wrapFieldWithTrailingBadge(TextField field, Label badge) {
        return attachFocusWithin(new HBox(8.0, field, badge), field);
    }

    private static HBox wrapFieldWithIconAndBadge(String iconSvg, String colorHex, TextField field, Label badge) {
        return attachFocusWithin(new HBox(8.0, iconBox(iconSvg, colorHex, 14.0), field, badge), field);
    }

    private static Label createSmallTag(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("sk-prod-unit-tag");
        return lbl;
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

    private static ImageView loadResourceImage(String path, double fitHeight) {
        try (InputStream is = ProductFormModal.class.getResourceAsStream(path)) {
            if (is == null) return null;
            ImageView iv = new ImageView(new Image(is));
            iv.setFitHeight(fitHeight);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setMouseTransparent(true);
            return iv;
        } catch (Exception e) {
            return null;
        }
    }

    private static Rectangle2D resolveUsableScreenBounds(Window owner) {
        if (owner != null) {
            List<Screen> screens = Screen.getScreensForRectangle(
                    owner.getX(), owner.getY(), Math.max(1.0, owner.getWidth()), Math.max(1.0, owner.getHeight())
            );
            if (!screens.isEmpty()) {
                return screens.get(0).getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    static Money parseStrictNonNegativeMoney(String input, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            return Money.ZERO;
        }
        String normalized = input.trim().replaceAll("[, ]", "");
        BigDecimal bd;
        try {
            bd = new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldLabel + " must be a valid amount in BDT.");
        }
        if (bd.signum() < 0) {
            throw new IllegalArgumentException(fieldLabel + " cannot be negative.");
        }
        return Money.ofBdt(bd);
    }

    static Money tryParseStrictNonNegative(String input) {
        try {
            return parseStrictNonNegativeMoney(input, "Amount");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static int parseStrictIntRange(String input, int defaultVal, int minVal, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            if (defaultVal < minVal) {
                throw new IllegalArgumentException(fieldLabel + " must be at least " + minVal + ".");
            }
            return defaultVal;
        }
        try {
            int val = Integer.parseInt(input.trim().replaceAll("[, ]", ""));
            if (val < minVal) {
                throw new IllegalArgumentException(fieldLabel + " must be at least " + minVal + ".");
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldLabel + " must be a whole number.");
        }
    }

    private static int safeParseIntOrDefault(String input, int defVal) {
        if (input == null || input.trim().isBlank()) return defVal;
        try {
            return Integer.parseInt(input.trim().replaceAll("[, ]", ""));
        } catch (Exception e) {
            return defVal;
        }
    }

    private static String safeUnitValue(ComboBox<String> combo, String fallback) {
        String val = combo.getValue();
        if ((val == null || val.isBlank()) && combo.getEditor() != null) {
            val = combo.getEditor().getText();
        }
        return val != null && !val.isBlank() ? val.trim() : fallback;
    }

    private static String nonNull(String s) {
        return s != null ? s : "";
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Exposed package-private parts record to support deterministic FX testing without blocking windows.
     */
    record ModalParts(
            StackPane rootPane,
            StackPane cardShell,
            StackPane headerDragArea,
            HBox errorBanner,
            Label lblError,
            Button tabBtnDetails,
            Button tabBtnSuppliers,
            ScrollPane bodyScroll,
            TextField txtName,
            ComboBox<Category> cmbCategory,
            Button btnNewCategory,
            TextField txtBarcode,
            TextField txtSku,
            ComboBox<String> cmbBaseUnit,
            ComboBox<String> cmbPurchaseUnit,
            TextField txtPackSize,
            Label lblPackHint,
            TextField txtTp,
            TextField txtCost,
            TextField txtMrp,
            TextField txtSelling,
            TextField txtThreshold,
            TextField txtOpeningStock,
            Label lblEstProfit,
            Label lblEstMargin,
            Label lblEstMarkup,
            HBox warnBanner,
            Label lblPriceWarning,
            ImageView imgPreview,
            VBox imgEmptyPlaceholder,
            ComboBox<String> cmbImagePreset,
            Button btnBrowseImage,
            Button btnClearImage,
            AtomicReference<String> selectedImagePathRef,
            Label lblSupInfoCount,
            Button btnJumpToSuppliers,
            ComboBox<Supplier> cmbSupplierPicker,
            Button btnNewSupplier,
            TextField txtSupTp,
            TextField txtSupVendorSku,
            TextField txtSupUnit,
            TextField txtSupPack,
            TextField txtSupMoq,
            TextField txtSupLeadDays,
            CheckBox chkSupPreferred,
            Button btnAddUpdateSupplier,
            Button btnClearSupplierForm,
            TableView<DraftSupplierLink> tblSupplierLinks,
            ObservableList<DraftSupplierLink> draftLinks,
            List<Long> deletedMappingIds,
            Button btnCancel,
            Button btnSave
    ) {}
}

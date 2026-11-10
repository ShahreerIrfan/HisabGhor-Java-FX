package com.smartkhata.controller;

import com.smartkhata.app.AppConfig;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Category;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.Sale;
import com.smartkhata.model.entity.SaleItem;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SaleService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteCustomerService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteSaleService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.CustomerFormModal;
import com.smartkhata.ui.ViewRouter;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import com.smartkhata.util.ProductImageResolver;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for Sales & POS Workstation (বিক্রয় ও পিওএস) and Sales History Inspector.
 * Implements live 3-panel cashier POS cart, F2 barcode scan / autocomplete, F4 checkout,
 * quick customer creation, persisted held drafts, thermal receipt preview, and invoice voiding.
 */
public class SalesController {

    private static final Logger log = LoggerFactory.getLogger(SalesController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DRAFT_FMT = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.ENGLISH);

    private static final String TRASH_SVG = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5l-1-1h-5l-1 1H5v2h14V4z";

    private final SaleService saleService;
    private final CustomerService customerService;
    private final InventoryService inventoryService;
    private final ExecutorService executor;

    private final ContextMenu searchSuggestionsMenu = new ContextMenu();

    // Root & Mode Containers
    @FXML private VBox rootSalesPane;
    @FXML private Button btnToggleHistory;
    @FXML private Button btnNewSale;
    @FXML private VBox panePosWorkspace;
    @FXML private VBox paneHistoryWorkspace;
    @FXML private VBox panePosOrdersStrip;
    @FXML private Label lblPosOrdersCount;
    @FXML private TextField txtPosOrdersSearch;
    @FXML private Button btnOpenFullHistory;
    @FXML private TableView<SaleListItemDto> tblPosOrders;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdInvoice;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdDate;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdCustomer;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdItems;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdNet;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdPaid;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdDue;
    @FXML private TableColumn<SaleListItemDto, String> colPosOrdStatus;
    @FXML private TableColumn<SaleListItemDto, SaleListItemDto> colPosOrdActions;

    // POS Left Panel: Search & Cart
    @FXML private Button btnBarcodeFocus;
    @FXML private TextField txtPosSearch;
    @FXML private TableView<PosCartLine> tblCart;
    @FXML private TableColumn<PosCartLine, PosCartLine> colCartProduct;
    @FXML private TableColumn<PosCartLine, PosCartLine> colCartQty;
    @FXML private TableColumn<PosCartLine, PosCartLine> colCartPrice;
    @FXML private TableColumn<PosCartLine, String> colCartTotal;
    @FXML private TableColumn<PosCartLine, PosCartLine> colCartRemove;
    @FXML private Button btnClearAll;
    @FXML private Button btnRecallDrafts;

    // POS Center Panel: Bill Summary
    @FXML private Label lblSummaryItems;
    @FXML private Label lblSummarySubtotal;
    @FXML private Button btnAddDiscount;
    @FXML private Label lblSummaryNetPayable;
    @FXML private ComboBox<CustomerOption> cmbPosCustomer;
    @FXML private Button btnQuickAddCustomer;
    @FXML private VBox vboxSelectedCustomerCard;
    @FXML private Label lblPosCustNamePhone;
    @FXML private Label lblPosCustAddress;
    @FXML private Label lblPosCustDueLimit;
    @FXML private Button btnCompleteSale;
    @FXML private Button btnSaveDraft;
    @FXML private Button btnPosPrintPreview;

    // POS Right Panel: Popular Products
    @FXML private Button btnSeeAllProducts;
    @FXML private ScrollPane scrollPopularProducts;
    @FXML private GridPane gridPopularProducts;
    @FXML private VBox vboxNoPopularProducts;

    // Sales History Workspace
    @FXML private Label lblTotalInvoices;
    @FXML private Label lblTotalDues;
    @FXML private Label lblTotalSales;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbHistoryDateRange;
    @FXML private ComboBox<String> cmbStatusFilter;
    @FXML private Button btnHistoryClearFilters;
    @FXML private HBox boxHistoryCustomDates;
    @FXML private DatePicker dpHistoryStart;
    @FXML private DatePicker dpHistoryEnd;
    @FXML private Label lblHistoryFilterHint;
    @FXML private Button btnRefresh;

    @FXML private TableView<SaleListItemDto> tblSales;
    @FXML private TableColumn<SaleListItemDto, String> colInvoiceNo;
    @FXML private TableColumn<SaleListItemDto, String> colDate;
    @FXML private TableColumn<SaleListItemDto, String> colCustomer;
    @FXML private TableColumn<SaleListItemDto, String> colAmount;
    @FXML private TableColumn<SaleListItemDto, String> colPaid;
    @FXML private TableColumn<SaleListItemDto, String> colDue;
    @FXML private TableColumn<SaleListItemDto, String> colStatus;
    @FXML private TableColumn<SaleListItemDto, SaleListItemDto> colSalesActions;

    @FXML private VBox pnlDetailContainer;
    @FXML private VBox vboxNoSelection;
    @FXML private VBox vboxInvoiceDetails;
    @FXML private Label lblDetailInvoiceNo;
    @FXML private Label lblDetailCustomerInfo;
    @FXML private Label lblDetailNotes;
    @FXML private Button btnViewOrderDetails;
    @FXML private Button btnEditSaleOrder;
    @FXML private Button btnDeleteSaleOrder;
    @FXML private Button btnEditSaleNote;
    @FXML private Button btnPrintReceipt;
    @FXML private Button btnReturnItems;
    @FXML private Button btnVoidSale;
    @FXML private Button btnDeleteVoidedSale;
    @FXML private Label lblDetailNet;
    @FXML private Label lblDetailPaid;
    @FXML private Label lblDetailDue;
    @FXML private Label lblVoidStatus;

    @FXML private TableView<SaleItemDto> tblDetailItems;
    @FXML private TableColumn<SaleItemDto, String> colItemProduct;
    @FXML private TableColumn<SaleItemDto, String> colItemQty;
    @FXML private TableColumn<SaleItemDto, String> colItemPrice;
    @FXML private TableColumn<SaleItemDto, String> colItemTotal;

    private final ObservableList<PosCartLine> cartLines = FXCollections.observableArrayList();
    private final ObservableList<SaleListItemDto> saleList = FXCollections.observableArrayList();
    private final ObservableList<SaleListItemDto> posRecentOrdersList = FXCollections.observableArrayList();
    private final ObservableList<SaleItemDto> detailItemList = FXCollections.observableArrayList();

    private volatile List<ProductListItemDto> activeCatalog = List.of();
    private volatile List<Category> activeCategories = List.of();
    private volatile Map<Long, CustomerListItemDto> activeCustomersMap = Map.of();
    private volatile List<SaleListItemDto> allLoadedPosOrders = List.of();
    private volatile int knownTotalOrdersCount = 0;
    private volatile Sale selectedDetailSale = null;
    private Money currentDiscount = Money.ZERO;
    private String activeIdempotencyKey = UUID.randomUUID().toString();
    private boolean historyModeActive = false;

    private volatile CompletableFuture<Void> pendingPosLoad = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> pendingHistoryLoad = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> pendingDetailLoad = CompletableFuture.completedFuture(null);
    private boolean suppressingHistoryFilters = false;

    public SalesController() {
        this(new SqliteSaleService(), new SqliteCustomerService(), new SqliteInventoryService());
    }

    public SalesController(SaleService saleService, CustomerService customerService, InventoryService inventoryService) {
        this.saleService = saleService;
        this.customerService = customerService;
        this.inventoryService = inventoryService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "sales-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public record CustomerOption(Long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record PosDraft(
            String id,
            long businessId,
            String createdAtIso,
            Long customerId,
            String customerName,
            long discountPaisha,
            List<PosDraftLine> lines
    ) {}

    private record PosDraftLine(long productId, String productName, int quantity, long unitPricePaisha) {}

    /**
     * Mutable observable row for live POS cart table.
     */
    public static final class PosCartLine {
        private final long productId;
        private final String productName;
        private final String categoryName;
        private final String imagePath;
        private final String barcode;
        private final String sku;
        private final String unit;
        private int availableStock;
        private final Money unitCost;
        private final IntegerProperty quantity = new SimpleIntegerProperty(1);
        private final ObjectProperty<Money> unitPrice = new SimpleObjectProperty<>(Money.ZERO);
        private final StringProperty formattedTotal = new SimpleStringProperty("0.00");

        public PosCartLine(ProductListItemDto dto, int qty, Money unitPrice) {
            this.productId = dto.id();
            this.productName = dto.name();
            this.categoryName = dto.categoryName();
            this.imagePath = dto.imagePath();
            this.barcode = dto.barcode();
            this.sku = dto.sku();
            this.unit = dto.unit();
            this.availableStock = dto.currentStock();
            this.unitCost = dto.costPrice() != null ? dto.costPrice() : Money.ZERO;
            this.quantity.set(Math.max(1, qty));
            this.unitPrice.set(unitPrice != null ? unitPrice : Money.ZERO);
            recalculateTotal();
        }

        public long productId() { return productId; }
        public String productName() { return productName; }
        public String categoryName() { return categoryName; }
        public String imagePath() { return imagePath; }
        public String barcode() { return barcode; }
        public String sku() { return sku; }
        public String unit() { return unit; }
        public int availableStock() { return availableStock; }
        public void setAvailableStock(int availableStock) { this.availableStock = availableStock; }
        public Money unitCost() { return unitCost; }
        public int getQuantity() { return quantity.get(); }
        public IntegerProperty quantityProperty() { return quantity; }
        public Money getUnitPrice() { return unitPrice.get(); }
        public void setUnitPrice(Money price) {
            this.unitPrice.set(price != null ? price : Money.ZERO);
            recalculateTotal();
        }
        public void setQuantity(int qty) {
            this.quantity.set(Math.max(1, qty));
            recalculateTotal();
        }
        public StringProperty formattedTotalProperty() { return formattedTotal; }
        public Money lineTotal() { return getUnitPrice().multiply(getQuantity()); }

        private void recalculateTotal() {
            formattedTotal.set(formatPlainDecimal(lineTotal()));
        }
    }

    @FXML
    public void initialize() {
        log.info("Initializing SalesController (3-Panel POS + History)...");

        setupPosCartTable();
        setupPosOrdersTable();
        setupPopularGridColumns();
        setupSalesTable();
        setupDetailItemsTable();
        setupStatusFilter();
        setupKeyboardShortcuts();
        applyModeVisibility(false);

        if (cmbPosCustomer != null) {
            cmbPosCustomer.valueProperty().addListener((obs, oldVal, newVal) -> updateSelectedCustomerInfoCard());
        }

        tblSales.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                showSaleDetails(newVal);
            } else {
                showNoSelection();
            }
        });

        refreshData();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Refreshes POS catalog, customers, drafts badge, and sales history without losing current active cart items.
     */
    public void refreshData() {
        loadPosContextAsync();
        loadSalesHistoryAsync();
        refreshDraftsBadge();
    }

    public CompletableFuture<Void> pendingLoadsFuture() {
        return CompletableFuture.allOf(pendingPosLoad, pendingHistoryLoad, pendingDetailLoad);
    }

    private void setupKeyboardShortcuts() {
        if (rootSalesPane == null) return;
        rootSalesPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (rootSalesPane.getParent() == null || !panePosWorkspace.isVisible()) {
                        return;
                    }
                    if (event.getCode() == KeyCode.F2) {
                        onFocusPosSearch();
                        event.consume();
                    } else if (event.getCode() == KeyCode.F4) {
                        onCompleteSale();
                        event.consume();
                    }
                });
            }
        });
    }

    private void setupPopularGridColumns() {
        if (gridPopularProducts == null) return;
        gridPopularProducts.getColumnConstraints().clear();
        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(33.3333);
            cc.setFillWidth(true);
            cc.setHgrow(Priority.ALWAYS);
            gridPopularProducts.getColumnConstraints().add(cc);
        }
    }

    private void setupPosCartTable() {
        tblCart.setItems(cartLines);

        // Product thumbnail + bold name
        colCartProduct.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colCartProduct.setCellFactory(col -> new TableCell<>() {
            private final ImageView iconView = new ImageView();
            private final StackPane thumbBox = new StackPane(iconView);
            private final Label lblName = new Label();
            private final HBox box = new HBox(9.0, thumbBox, lblName);

            {
                iconView.setFitWidth(24.0);
                iconView.setFitHeight(24.0);
                iconView.setPreserveRatio(true);
                iconView.setSmooth(true);
                thumbBox.setPrefSize(30.0, 30.0);
                thumbBox.setMinSize(30.0, 30.0);
                thumbBox.setMaxSize(30.0, 30.0);
                thumbBox.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #EEF2F6; -fx-border-radius: 7px; -fx-background-radius: 7px;");
                lblName.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #0F172A;");
                lblName.setWrapText(false);
                lblName.setMaxWidth(155.0);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(PosCartLine item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    iconView.setImage(ProductImageResolver.resolve(item.imagePath(), item.productName(), item.categoryName()));
                    lblName.setText(item.productName());
                    setTooltip(new Tooltip(item.productName() + " (Available Stock: " + item.availableStock() + ")"));
                    setGraphic(box);
                    setText(null);
                }
            }
        });

        // Qty stepper (- [qty] +)
        colCartQty.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colCartQty.setCellFactory(col -> new TableCell<>() {
            private final Button btnMinus = new Button("-");
            private final Label lblQty = new Label();
            private final Button btnPlus = new Button("+");
            private final HBox stepper = new HBox(2.0, btnMinus, lblQty, btnPlus);
            private final StackPane wrapper = new StackPane(stepper);
            private PosCartLine currentLine;

            {
                btnMinus.getStyleClass().add("pos-qty-btn");
                btnPlus.getStyleClass().add("pos-qty-btn");
                lblQty.getStyleClass().add("pos-qty-value");
                stepper.getStyleClass().add("pos-qty-stepper");
                stepper.setAlignment(Pos.CENTER);
                stepper.setMaxWidth(Region.USE_PREF_SIZE);
                wrapper.setAlignment(Pos.CENTER);

                btnMinus.setOnAction(e -> {
                    if (currentLine != null) {
                        adjustCartQuantity(currentLine, -1);
                    }
                });
                btnPlus.setOnAction(e -> {
                    if (currentLine != null) {
                        adjustCartQuantity(currentLine, +1);
                    }
                });
            }

            @Override
            protected void updateItem(PosCartLine item, boolean empty) {
                super.updateItem(item, empty);
                if (currentLine != null && !empty && item == currentLine) {
                    return;
                }
                lblQty.textProperty().unbind();
                currentLine = empty ? null : item;
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    lblQty.textProperty().bind(item.quantityProperty().asString());
                    setGraphic(wrapper);
                    setText(null);
                }
            }
        });

        // Editable Price (BDT) field
        colCartPrice.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colCartPrice.setCellFactory(col -> new TableCell<>() {
            private final TextField txtPrice = new TextField();
            private final StackPane wrapper = new StackPane(txtPrice);
            private PosCartLine currentLine;

            {
                txtPrice.getStyleClass().add("pos-price-field");
                txtPrice.setMaxWidth(76.0);
                wrapper.setAlignment(Pos.CENTER);

                Runnable commitPrice = () -> {
                    if (currentLine == null) return;
                    Money parsed = parseNonNegativeMoney(txtPrice.getText(), currentLine.getUnitPrice());
                    currentLine.setUnitPrice(parsed);
                    txtPrice.setText(formatPlainDecimal(parsed));
                    recalculateBillSummary();
                };

                txtPrice.setOnAction(e -> {
                    commitPrice.run();
                    tblCart.requestFocus();
                });
                txtPrice.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (wasFocused && !isFocused) {
                        commitPrice.run();
                    }
                });
            }

            @Override
            protected void updateItem(PosCartLine item, boolean empty) {
                super.updateItem(item, empty);
                currentLine = empty ? null : item;
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    if (!txtPrice.isFocused()) {
                        txtPrice.setText(formatPlainDecimal(item.getUnitPrice()));
                    }
                    setGraphic(wrapper);
                    setText(null);
                }
            }
        });

        // Total (BDT) bold value
        colCartTotal.setCellValueFactory(cell -> cell.getValue().formattedTotalProperty());
        colCartTotal.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setAlignment(Pos.CENTER_RIGHT);
                    setStyle("-fx-font-size: 12.5px; -fx-font-weight: 800; -fx-text-fill: #0F172A; -fx-padding: 0 6px 0 0;");
                }
            }
        });

        // Remove action (red trash vector icon)
        colCartRemove.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colCartRemove.setCellFactory(col -> new TableCell<>() {
            private final SVGPath trashIcon = new SVGPath();
            private final Button btnDel = new Button();
            private final StackPane wrapper = new StackPane(btnDel);
            private PosCartLine currentLine;

            {
                trashIcon.setContent(TRASH_SVG);
                trashIcon.setStyle("-fx-fill: #EF4444;");
                trashIcon.setScaleX(0.66);
                trashIcon.setScaleY(0.66);
                btnDel.setGraphic(trashIcon);
                btnDel.getStyleClass().add("pos-row-delete-btn");
                btnDel.setTooltip(new Tooltip("Remove item from cart"));
                btnDel.setOnAction(e -> {
                    if (currentLine != null) {
                        cartLines.remove(currentLine);
                        recalculateBillSummary();
                    }
                });
                wrapper.setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(PosCartLine item, boolean empty) {
                super.updateItem(item, empty);
                currentLine = empty ? null : item;
                setGraphic(empty || item == null ? null : wrapper);
                setText(null);
            }
        });
    }

    private void loadPosContextAsync() {
        long businessId = getActiveBusinessId();
        pendingPosLoad.cancel(false);

        pendingPosLoad = CompletableFuture.runAsync(() -> {
            List<ProductListItemDto> products = inventoryService.searchProducts(businessId, null, null, false);
            List<Category> categories = inventoryService.getCategories(businessId);
            List<CustomerListItemDto> customers = customerService.searchCustomers(businessId, null, false);

            Platform.runLater(() -> {
                this.activeCatalog = products;
                this.activeCategories = categories;
                syncCartAvailableStocks(products);
                populatePopularProducts(products);
                populateCustomerCombo(customers, null);
                recalculateBillSummary();
            });
        }, executor).exceptionally(ex -> {
            log.error("Failed to load POS catalog/customers: {}", ex.getMessage(), ex);
            return null;
        });
    }

    private void syncCartAvailableStocks(List<ProductListItemDto> products) {
        Map<Long, ProductListItemDto> byId = new HashMap<>();
        for (ProductListItemDto p : products) {
            byId.put(p.id(), p);
        }
        for (PosCartLine line : cartLines) {
            ProductListItemDto live = byId.get(line.productId());
            if (live != null) {
                line.setAvailableStock(live.currentStock());
            }
        }
    }

    private void populateCustomerCombo(List<CustomerListItemDto> customers, Long preferredCustomerId) {
        if (cmbPosCustomer == null) return;
        Long previousId = preferredCustomerId != null
                ? preferredCustomerId
                : (cmbPosCustomer.getValue() != null ? cmbPosCustomer.getValue().id() : null);

        Map<Long, CustomerListItemDto> map = new LinkedHashMap<>();
        ObservableList<CustomerOption> options = FXCollections.observableArrayList();
        CustomerOption walkIn = new CustomerOption(null, "Walk-in Customer");
        options.add(walkIn);

        CustomerOption toSelect = walkIn;
        for (CustomerListItemDto c : customers) {
            map.put(c.id(), c);
            String display = c.name() + (c.phone() != null && !c.phone().isBlank() ? " (" + c.phone() + ")" : "");
            CustomerOption opt = new CustomerOption(c.id(), display);
            options.add(opt);
            if (previousId != null && previousId.equals(c.id())) {
                toSelect = opt;
            }
        }
        this.activeCustomersMap = Collections.unmodifiableMap(map);

        cmbPosCustomer.setItems(options);
        cmbPosCustomer.getSelectionModel().select(toSelect);
        updateSelectedCustomerInfoCard();
    }

    private void updateSelectedCustomerInfoCard() {
        if (lblPosCustNamePhone == null || lblPosCustAddress == null || lblPosCustDueLimit == null) return;
        CustomerOption chosen = cmbPosCustomer != null ? cmbPosCustomer.getValue() : null;
        CustomerListItemDto info = (chosen != null && chosen.id() != null)
                ? activeCustomersMap.get(chosen.id()) : null;

        if (info == null) {
            lblPosCustNamePhone.setText("Walk-in Cash Customer");
            lblPosCustAddress.setText("Select saved customer or enter details at Confirm Order");
            lblPosCustDueLimit.setVisible(false);
            lblPosCustDueLimit.setManaged(false);
            return;
        }

        String phone = info.phone() != null && !info.phone().isBlank() ? info.phone().trim() : "No phone";
        lblPosCustNamePhone.setText(info.name() + "  •  " + phone);

        String addr = info.address() != null && !info.address().isBlank() ? info.address().trim() : "Address not provided";
        lblPosCustAddress.setText("Address: " + addr);

        String dueStr = BengaliMoneyFormatter.format(info.currentDue());
        String limitStr = BengaliMoneyFormatter.format(info.creditLimit());
        lblPosCustDueLimit.setText("Prev Due: " + dueStr + "  |  Limit: " + limitStr);
        lblPosCustDueLimit.setStyle("-fx-font-size: 10.5px; -fx-font-weight: 700; -fx-text-fill: "
                + (info.currentDue().isPositive() ? "#DC2626;" : "#075C32;"));
        lblPosCustDueLimit.setVisible(true);
        lblPosCustDueLimit.setManaged(true);
    }

    private void populatePopularProducts(List<ProductListItemDto> products) {
        if (gridPopularProducts == null || vboxNoPopularProducts == null) return;
        gridPopularProducts.getChildren().clear();

        if (products == null || products.isEmpty()) {
            gridPopularProducts.setVisible(false);
            gridPopularProducts.setManaged(false);
            vboxNoPopularProducts.setVisible(true);
            vboxNoPopularProducts.setManaged(true);
            return;
        }

        gridPopularProducts.setVisible(true);
        gridPopularProducts.setManaged(true);
        vboxNoPopularProducts.setVisible(false);
        vboxNoPopularProducts.setManaged(false);

        // Sort active products: stocked items first, preserving catalog order
        List<ProductListItemDto> ordered = new ArrayList<>(products);
        ordered.sort(Comparator.comparing((ProductListItemDto p) -> p.currentStock() <= 0));

        int limit = Math.min(ordered.size(), 9);
        for (int i = 0; i < limit; i++) {
            ProductListItemDto p = ordered.get(i);
            VBox tile = buildPopularProductTile(p);
            int col = i % 3;
            int row = i / 3;
            GridPane.setHgrow(tile, Priority.ALWAYS);
            gridPopularProducts.add(tile, col, row);
        }
    }

    private VBox buildPopularProductTile(ProductListItemDto product) {
        ImageView iv = new ImageView(ProductImageResolver.resolve(product.imagePath(), product.name(), product.categoryName()));
        iv.setFitWidth(50.0);
        iv.setFitHeight(50.0);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        Label lblName = new Label(shortenTileTitle(product.name()));
        lblName.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #0F172A;");
        lblName.setWrapText(true);
        lblName.setTextAlignment(TextAlignment.CENTER);
        lblName.setMaxWidth(Double.MAX_VALUE);
        lblName.setAlignment(Pos.CENTER);

        String subText = product.currentStock() <= 0
                ? "Out of stock"
                : BengaliMoneyFormatter.format(product.sellingPrice());
        Label lblPrice = new Label(subText);
        lblPrice.setStyle(product.currentStock() <= 0
                ? "-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: #DC2626;"
                : "-fx-font-size: 10.5px; -fx-font-weight: 600; -fx-text-fill: #15803D;");

        VBox tile = new VBox(3.0, iv, lblName, lblPrice);
        tile.setAlignment(Pos.CENTER);
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setMinHeight(98.0);
        tile.getStyleClass().add("pos-product-tile");

        if (product.currentStock() <= 0) {
            tile.getStyleClass().add("pos-product-tile-oos");
        }

        String stockUnit = product.unit() != null && !product.unit().isBlank() ? product.unit() : "pcs";
        Tooltip.install(tile, new Tooltip(
                product.name() + "\nPrice: " + BengaliMoneyFormatter.format(product.sellingPrice())
                        + "\nAvailable Stock: " + product.currentStock() + " " + stockUnit
        ));

        tile.setOnMouseClicked(e -> addProductToCart(product, 1));
        return tile;
    }

    private String shortenTileTitle(String name) {
        if (name == null) return "Product";
        String clean = name.trim();
        int parenIdx = clean.indexOf('(');
        if (parenIdx > 2) {
            clean = clean.substring(0, parenIdx).trim();
        }
        return clean;
    }

    private void addProductToCart(ProductListItemDto product, int addQty) {
        if (product == null || addQty <= 0) return;

        if (product.currentStock() <= 0) {
            AlertHelper.showInfo(null, "Out of Stock (স্টক শেষ)",
                    "'" + product.name() + "' currently has 0 stock in inventory.\nPurchase or adjust stock before billing.");
            return;
        }

        Optional<PosCartLine> existing = cartLines.stream()
                .filter(l -> l.productId() == product.id())
                .findFirst();

        if (existing.isPresent()) {
            PosCartLine line = existing.get();
            int nextQty = line.getQuantity() + addQty;
            if (nextQty > product.currentStock()) {
                AlertHelper.showInfo(null, "Stock Limit Reached",
                        "Cannot sell " + nextQty + " of '" + product.name() + "'. Only "
                                + product.currentStock() + " available in stock.");
                return;
            }
            line.setQuantity(nextQty);
            tblCart.getSelectionModel().select(line);
        } else {
            if (addQty > product.currentStock()) {
                AlertHelper.showInfo(null, "Stock Limit Reached",
                        "Only " + product.currentStock() + " unit(s) available for '" + product.name() + "'.");
                return;
            }
            PosCartLine created = new PosCartLine(product, addQty, product.sellingPrice());
            cartLines.add(created);
            tblCart.getSelectionModel().select(created);
        }

        recalculateBillSummary();
    }

    private void adjustCartQuantity(PosCartLine line, int delta) {
        int nextQty = line.getQuantity() + delta;
        if (nextQty <= 0) {
            cartLines.remove(line);
        } else if (nextQty > line.availableStock()) {
            AlertHelper.showInfo(null, "Stock Limit Reached",
                    "Only " + line.availableStock() + " unit(s) of '" + line.productName() + "' available in stock.");
            return;
        } else {
            line.setQuantity(nextQty);
        }
        recalculateBillSummary();
    }

    private void recalculateBillSummary() {
        int totalItemsCount = 0;
        Money subtotal = Money.ZERO;
        for (PosCartLine line : cartLines) {
            totalItemsCount += line.getQuantity();
            subtotal = subtotal.add(line.lineTotal());
        }

        if (currentDiscount.compareTo(subtotal) > 0) {
            currentDiscount = subtotal;
        }
        Money netPayable = subtotal.subtract(currentDiscount);
        if (netPayable.isNegative()) netPayable = Money.ZERO;

        if (lblSummaryItems != null) {
            lblSummaryItems.setText(String.valueOf(totalItemsCount));
        }
        if (lblSummarySubtotal != null) {
            lblSummarySubtotal.setText(formatPosBdt(subtotal));
        }
        if (btnAddDiscount != null) {
            if (currentDiscount.isPositive()) {
                btnAddDiscount.setText("-" + formatPosBdt(currentDiscount));
            } else {
                btnAddDiscount.setText("Add Discount");
            }
        }
        if (lblSummaryNetPayable != null) {
            lblSummaryNetPayable.setText(formatPosBdt(netPayable));
        }
    }

    private Money calculateCartSubtotal() {
        Money sub = Money.ZERO;
        for (PosCartLine l : cartLines) {
            sub = sub.add(l.lineTotal());
        }
        return sub;
    }

    private Money calculateCartNet() {
        Money sub = calculateCartSubtotal();
        Money net = sub.subtract(currentDiscount);
        return net.isNegative() ? Money.ZERO : net;
    }

    // =====================================================================
    // POS WORKSPACE EVENT HANDLERS
    // =====================================================================

    @FXML
    private void onFocusPosSearch() {
        if (txtPosSearch != null) {
            txtPosSearch.requestFocus();
            txtPosSearch.selectAll();
        }
    }

    @FXML
    private void onPosSearchKeyReleased(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            searchSuggestionsMenu.hide();
            return;
        }
        if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN) {
            return;
        }

        String raw = txtPosSearch.getText();
        if (raw == null || raw.trim().isEmpty()) {
            searchSuggestionsMenu.hide();
            return;
        }

        String q = raw.trim().toLowerCase(Locale.ROOT);
        List<ProductListItemDto> matches = activeCatalog.stream()
                .filter(p -> matchesCatalogQuery(p, q))
                .limit(8)
                .toList();

        if (matches.isEmpty()) {
            searchSuggestionsMenu.hide();
            return;
        }

        searchSuggestionsMenu.getItems().clear();
        for (ProductListItemDto p : matches) {
            MenuItem item = createSearchSuggestionMenuItem(p);
            searchSuggestionsMenu.getItems().add(item);
        }

        if (!searchSuggestionsMenu.isShowing() && txtPosSearch.getScene() != null) {
            searchSuggestionsMenu.show(txtPosSearch, Side.BOTTOM, 0, 4);
        }
    }

    @FXML
    private void onPosSearchEnter() {
        String raw = txtPosSearch.getText();
        if (raw == null || raw.trim().isEmpty()) return;
        String q = raw.trim().toLowerCase(Locale.ROOT);

        // 1. Exact barcode or SKU match for USB barcode scanners
        Optional<ProductListItemDto> exactCode = activeCatalog.stream()
                .filter(p -> (p.barcode() != null && p.barcode().trim().equalsIgnoreCase(q))
                        || (p.sku() != null && p.sku().trim().equalsIgnoreCase(q)))
                .findFirst();

        if (exactCode.isPresent()) {
            searchSuggestionsMenu.hide();
            addProductToCart(exactCode.get(), 1);
            txtPosSearch.clear();
            return;
        }

        // 2. Single matching catalog entry or exact product name match
        List<ProductListItemDto> matches = activeCatalog.stream()
                .filter(p -> matchesCatalogQuery(p, q))
                .toList();

        if (matches.size() == 1) {
            searchSuggestionsMenu.hide();
            addProductToCart(matches.get(0), 1);
            txtPosSearch.clear();
        } else {
            Optional<ProductListItemDto> exactName = matches.stream()
                    .filter(p -> p.name().trim().equalsIgnoreCase(q))
                    .findFirst();
            if (exactName.isPresent()) {
                searchSuggestionsMenu.hide();
                addProductToCart(exactName.get(), 1);
                txtPosSearch.clear();
            } else if (!matches.isEmpty()) {
                searchSuggestionsMenu.getItems().clear();
                matches.stream().limit(8).map(this::createSearchSuggestionMenuItem)
                        .forEach(searchSuggestionsMenu.getItems()::add);
                if (!searchSuggestionsMenu.isShowing() && txtPosSearch.getScene() != null) {
                    searchSuggestionsMenu.show(txtPosSearch, Side.BOTTOM, 0, 4);
                }
            }
        }
    }

    private MenuItem createSearchSuggestionMenuItem(ProductListItemDto product) {
        ImageView iv = new ImageView(ProductImageResolver.resolve(product.imagePath(), product.name(), product.categoryName()));
        iv.setFitWidth(22.0);
        iv.setFitHeight(22.0);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        Label lblName = new Label(product.name());
        lblName.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #0F172A;");

        String codeHint = product.barcode() != null && !product.barcode().isBlank() ? product.barcode()
                : (product.sku() != null && !product.sku().isBlank() ? product.sku() : "");
        Label lblMeta = new Label((codeHint.isEmpty() ? "" : codeHint + "  |  ")
                + "Stock: " + product.currentStock() + "  |  " + formatPosBdt(product.sellingPrice()));
        lblMeta.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (product.currentStock() > 0 ? "#15803D;" : "#DC2626;"));

        VBox textCol = new VBox(1.0, lblName, lblMeta);
        HBox row = new HBox(10.0, iv, textCol);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefWidth(360.0);
        row.setPadding(new Insets(3, 6, 3, 4));

        CustomMenuItem menuItem = new CustomMenuItem(row, true);
        menuItem.setOnAction(ev -> {
            addProductToCart(product, 1);
            txtPosSearch.clear();
            searchSuggestionsMenu.hide();
            txtPosSearch.requestFocus();
        });
        return menuItem;
    }

    private boolean matchesCatalogQuery(ProductListItemDto p, String lowerQuery) {
        if (p.name() != null && p.name().toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        if (p.barcode() != null && p.barcode().toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        if (p.sku() != null && p.sku().toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
        return p.categoryName() != null && p.categoryName().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    @FXML
    private void onClearAllCart() {
        if (cartLines.isEmpty() && currentDiscount.isZero()) return;
        cartLines.clear();
        currentDiscount = Money.ZERO;
        activeIdempotencyKey = UUID.randomUUID().toString();
        recalculateBillSummary();
        onFocusPosSearch();
    }

    @FXML
    private void onAddDiscount() {
        Money subtotal = calculateCartSubtotal();
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        if (subtotal.isZero()) {
            AlertHelper.showInfo(owner, "Cart Empty", "Add at least one item before applying a bill discount.");
            return;
        }

        RadioButton rbFlat = new RadioButton("Flat Amount (BDT)");
        RadioButton rbPercent = new RadioButton("Percentage (%)");
        ToggleGroup tg = new ToggleGroup();
        rbFlat.setToggleGroup(tg);
        rbPercent.setToggleGroup(tg);
        rbFlat.setSelected(true);
        HBox modeRow = new HBox(18.0, rbFlat, rbPercent);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        TextField txtValue = new TextField(currentDiscount.isPositive() ? currentDiscount.toBdt().toPlainString() : "");
        txtValue.setPromptText("Enter discount value (0 to clear)");

        Label lblPreview = new Label();
        lblPreview.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 700; -fx-text-fill: #075C32;");

        Button btnClearDisc = new Button("Remove Discount");
        btnClearDisc.getStyleClass().add("sk-prod-row-delete-btn");
        btnClearDisc.setOnAction(e -> txtValue.setText("0"));

        Runnable updatePreview = () -> {
            Money disc = computeDiscountFromInput(subtotal, txtValue.getText(), rbPercent.isSelected());
            Money net = subtotal.subtract(disc);
            if (net.isNegative()) net = Money.ZERO;
            lblPreview.setText("Discount: -" + formatPosBdt(disc) + "   •   Net Payable: " + formatPosBdt(net));
        };
        txtValue.textProperty().addListener((o, a, b) -> updatePreview.run());
        tg.selectedToggleProperty().addListener((o, a, b) -> updatePreview.run());
        updatePreview.run();

        HBox previewStrip = new HBox(10.0, lblPreview, new Region(), btnClearDisc);
        HBox.setHgrow(previewStrip.getChildren().get(1), Priority.ALWAYS);
        previewStrip.setAlignment(Pos.CENTER_LEFT);
        previewStrip.getStyleClass().add("sk-dlg-section-tint");

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_TAG, "Discount Mode & Value",
                        "Current Cart Subtotal: " + formatPosBdt(subtotal)),
                modeRow,
                DialogComponents.formField("Discount Amount / %", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, txtValue)),
                previewStrip
        );

        SmartKhataDialog.<Money>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Bill Discount (ছাড়)")
                .subtitle("Apply a fixed BDT or percentage discount to the active POS bill.")
                .icon(DialogComponents.SVG_TAG, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(txtValue)
                .primaryAction("Apply Discount", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String raw = txtValue.getText() != null ? txtValue.getText().trim() : "";
                    if (!raw.isEmpty()) {
                        try {
                            BigDecimal bd = new BigDecimal(raw.replaceAll("[, ]", ""));
                            if (bd.signum() < 0) {
                                throw new SmartKhataDialog.ValidationFocusException("Discount cannot be negative.", txtValue);
                            }
                        } catch (NumberFormatException ex) {
                            throw new SmartKhataDialog.ValidationFocusException("Enter a valid numeric discount value.", txtValue);
                        }
                    }
                    return computeDiscountFromInput(subtotal, raw, rbPercent.isSelected());
                })
                .showAndWait()
                .ifPresent(disc -> {
                    this.currentDiscount = disc;
                    recalculateBillSummary();
                });
    }

    private Money computeDiscountFromInput(Money subtotal, String rawInput, boolean percentMode) {
        if (rawInput == null || rawInput.isBlank()) return Money.ZERO;
        try {
            BigDecimal bd = new BigDecimal(rawInput.trim().replaceAll("[, ]", ""));
            if (bd.signum() <= 0) return Money.ZERO;
            Money result;
            if (percentMode) {
                BigDecimal cappedPct = bd.min(new BigDecimal("100"));
                BigDecimal discBdt = subtotal.toBdt().multiply(cappedPct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                result = Money.ofBdt(discBdt);
            } else {
                result = Money.ofBdt(bd);
            }
            return result.compareTo(subtotal) > 0 ? subtotal : result;
        } catch (Exception e) {
            return Money.ZERO;
        }
    }

    @FXML
    private void onQuickAddCustomer() {
        long businessId = getActiveBusinessId();
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        Optional<Customer> createdOpt = CustomerFormModal.showQuickAddDialog(
                owner,
                businessId,
                customerService::createCustomer
        );
        createdOpt.ifPresent(created -> CompletableFuture.supplyAsync(
                () -> customerService.searchCustomers(businessId, null, false), executor)
                .thenAccept(updated -> Platform.runLater(() -> populateCustomerCombo(updated, created.id())))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Platform.runLater(() -> showActionError("Customer Refresh Failed", cause.getMessage()));
                    return null;
                }));
    }

    @FXML
    private void onCompleteSale() {
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        if (cartLines.isEmpty()) {
            AlertHelper.showInfo(owner, "Empty Cart", "Scan or add at least one product to the POS cart before confirming an order.");
            onFocusPosSearch();
            return;
        }

        long businessId = getActiveBusinessId();
        CustomerOption initialOption = cmbPosCustomer != null ? cmbPosCustomer.getValue() : null;
        Money subtotal = calculateCartSubtotal();
        Money netPayable = calculateCartNet();

        // 1. Selected Products Summary Banner
        int totalQty = cartLines.stream().mapToInt(PosCartLine::getQuantity).sum();
        String itemsPreview = cartLines.stream()
                .limit(3)
                .map(l -> l.productName() + " x" + l.getQuantity())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        if (cartLines.size() > 3) {
            itemsPreview += " +" + (cartLines.size() - 3) + " more";
        }
        Label lblOrderItemsMeta = new Label("Items (" + totalQty + " qty): " + itemsPreview);
        lblOrderItemsMeta.setWrapText(true);
        lblOrderItemsMeta.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #18283B;");

        Label lblPayable = new Label("Net Payable: " + formatPosBdt(netPayable));
        lblPayable.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: #008A32;");
        Label lblBreakdown = new Label("Subtotal: " + formatPosBdt(subtotal)
                + (currentDiscount.isPositive() ? "   •   Discount: -" + formatPosBdt(currentDiscount) : ""));
        lblBreakdown.setStyle("-fx-font-size: 12px; -fx-text-fill: #68778A;");
        VBox summaryCard = DialogComponents.tintedSectionCard(lblOrderItemsMeta, lblPayable, lblBreakdown);

        // 2. Customer Information Section (Saved Customer / Walk-in OR Enter New Customer Details)
        ToggleGroup custModeGroup = new ToggleGroup();
        ToggleButton tbExistingCust = new ToggleButton("Saved Customer / Walk-in");
        tbExistingCust.setToggleGroup(custModeGroup);
        tbExistingCust.getStyleClass().add("sk-dlg-pill-toggle");
        tbExistingCust.setSelected(true);

        ToggleButton tbNewCust = new ToggleButton("Enter New Customer Info");
        tbNewCust.setToggleGroup(custModeGroup);
        tbNewCust.getStyleClass().add("sk-dlg-pill-toggle");

        custModeGroup.selectedToggleProperty().addListener((o, prev, next) -> {
            if (next == null && prev != null) prev.setSelected(true);
        });

        ComboBox<CustomerOption> cmbConfirmCustomer = DialogComponents.styleCombo(new ComboBox<>());
        if (cmbPosCustomer != null) {
            cmbConfirmCustomer.setItems(FXCollections.observableArrayList(cmbPosCustomer.getItems()));
            cmbConfirmCustomer.getSelectionModel().select(initialOption != null ? initialOption : cmbPosCustomer.getItems().get(0));
        }

        Label lblCustInfoName = new Label();
        lblCustInfoName.setStyle("-fx-font-size: 13px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        Label lblCustInfoPhone = new Label();
        lblCustInfoPhone.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #334155;");
        Label lblCustInfoAddress = new Label();
        lblCustInfoAddress.setWrapText(true);
        lblCustInfoAddress.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748B;");
        Label lblCustInfoDueLimit = new Label();
        lblCustInfoDueLimit.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 700; -fx-text-fill: #075C32;");

        VBox existingCustInfoBox = new VBox(3.0, lblCustInfoName, lblCustInfoPhone, lblCustInfoAddress, lblCustInfoDueLimit);
        existingCustInfoBox.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #DCE5E9; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-padding: 9px 11px;");
        VBox existingCustPane = new VBox(8.0,
                DialogComponents.formField("Select Customer", false, null, cmbConfirmCustomer),
                existingCustInfoBox
        );

        TextField txtNewName = new TextField();
        txtNewName.setPromptText("e.g. Md. Hasan Ali");
        TextField txtNewPhone = new TextField();
        txtNewPhone.setPromptText("e.g. 017XXXXXXXX");
        TextField txtNewAddress = new TextField();
        txtNewAddress.setPromptText("e.g. House 12, Mirpur-10, Dhaka");
        TextField txtNewCreditLimit = new TextField("10000");
        txtNewCreditLimit.setPromptText("0");

        VBox newNameCol = DialogComponents.formField("Customer Name", true, null,
                DialogComponents.textFieldWrap(DialogComponents.SVG_USER, txtNewName));
        HBox.setHgrow(newNameCol, Priority.ALWAYS);
        VBox newPhoneCol = DialogComponents.formField("Mobile Number", true, null,
                DialogComponents.textFieldWrap(DialogComponents.SVG_PHONE, txtNewPhone));
        HBox.setHgrow(newPhoneCol, Priority.ALWAYS);
        VBox newAddrCol = DialogComponents.formField("Address", false, null,
                DialogComponents.textFieldWrap(DialogComponents.SVG_PIN, txtNewAddress));
        HBox.setHgrow(newAddrCol, Priority.ALWAYS);
        VBox newLimitCol = DialogComponents.formField("Credit Limit (BDT)", false, null,
                DialogComponents.currencyFieldWrap(txtNewCreditLimit));
        HBox.setHgrow(newLimitCol, Priority.ALWAYS);

        VBox newCustPane = new VBox(8.0,
                new HBox(12.0, newNameCol, newPhoneCol),
                new HBox(12.0, newAddrCol, newLimitCol)
        );
        newCustPane.setVisible(false);
        newCustPane.setManaged(false);

        VBox customerSectionCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_USER, "Customer Information (ক্রেতার তথ্য)",
                        "Verify selected customer name, number & address or enter a new customer."),
                new HBox(8.0, tbExistingCust, tbNewCust),
                existingCustPane,
                newCustPane
        );

        // 3. Payment Settlement Card
        ToggleGroup payGroup = new ToggleGroup();
        HBox methodRow = new HBox(8.0);
        for (String method : List.of("CASH", "BKASH", "NAGAD", "BANK")) {
            ToggleButton tb = new ToggleButton(method);
            tb.setToggleGroup(payGroup);
            tb.getStyleClass().add("sk-dlg-pill-toggle");
            if ("CASH".equals(method)) tb.setSelected(true);
            methodRow.getChildren().add(tb);
        }
        payGroup.selectedToggleProperty().addListener((o, prev, next) -> {
            if (next == null && prev != null) prev.setSelected(true);
        });

        TextField txtPaid = new TextField(netPayable.toBdt().toPlainString());
        txtPaid.setStyle("-fx-font-size: 16px; -fx-font-weight: 700;");

        Button btnExactPaid = new Button("Exact Full Paid");
        btnExactPaid.getStyleClass().add("sk-dlg-btn-subtle-green");
        btnExactPaid.setOnAction(e -> txtPaid.setText(netPayable.toBdt().toPlainString()));

        Button btnFullBaki = new Button("Full Baki (Due)");
        btnFullBaki.getStyleClass().add("sk-prod-row-delete-btn");
        btnFullBaki.setStyle("-fx-pref-height: 36px; -fx-padding: 0 12px;");
        btnFullBaki.setOnAction(e -> txtPaid.setText("0.00"));

        HBox quickPaidBtns = new HBox(8.0, btnExactPaid, btnFullBaki);

        Label lblDueFeedback = new Label();
        lblDueFeedback.setWrapText(true);

        TextField txtNotes = new TextField();
        txtNotes.setPromptText("Optional delivery / invoice remarks...");

        Runnable syncCustomerAndDuePreview = () -> {
            boolean isNewCust = tbNewCust.isSelected();
            existingCustPane.setVisible(!isNewCust);
            existingCustPane.setManaged(!isNewCust);
            newCustPane.setVisible(isNewCust);
            newCustPane.setManaged(isNewCust);

            CustomerOption sel = cmbConfirmCustomer.getValue();
            boolean hasCustomerAccount = isNewCust || (sel != null && sel.id() != null);
            btnFullBaki.setVisible(hasCustomerAccount);
            btnFullBaki.setManaged(hasCustomerAccount);

            if (!isNewCust) {
                CustomerListItemDto info = (sel != null && sel.id() != null)
                        ? activeCustomersMap.get(sel.id()) : null;
                if (info == null) {
                    lblCustInfoName.setText("Name: Walk-in Cash Customer");
                    lblCustInfoPhone.setText("Mobile: Not required for cash counter sale");
                    lblCustInfoAddress.setText("Address: —");
                    lblCustInfoDueLimit.setText("Tip: Switch to 'Enter New Customer Info' to save customer details or sell on Baki.");
                    lblCustInfoDueLimit.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");
                } else {
                    String phone = info.phone() != null && !info.phone().isBlank() ? info.phone() : "—";
                    String addr = info.address() != null && !info.address().isBlank() ? info.address() : "—";
                    lblCustInfoName.setText("Name: " + info.name());
                    lblCustInfoPhone.setText("Mobile: " + phone);
                    lblCustInfoAddress.setText("Address: " + addr);
                    lblCustInfoDueLimit.setText("Current Due: " + BengaliMoneyFormatter.format(info.currentDue())
                            + "   •   Credit Limit: " + BengaliMoneyFormatter.format(info.creditLimit()));
                    lblCustInfoDueLimit.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 700; -fx-text-fill: "
                            + (info.currentDue().isPositive() ? "#DC2626;" : "#075C32;"));
                }
            }

            Money paidInput = parseNonNegativeMoney(txtPaid.getText(), Money.ZERO);
            Money effectivePaid = paidInput.compareTo(netPayable) > 0 ? netPayable : paidInput;
            Money due = netPayable.subtract(effectivePaid);
            Money change = paidInput.compareTo(netPayable) > 0 ? paidInput.subtract(netPayable) : Money.ZERO;

            if (due.isPositive() && !hasCustomerAccount) {
                lblDueFeedback.setText("Due: " + formatPosBdt(due) + " — Walk-in customers cannot have Baki. Select/enter a customer or pay in full.");
                lblDueFeedback.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #DC3545;");
            } else if (due.isPositive()) {
                lblDueFeedback.setText("Remaining Due (বাকি যোগ হবে): " + formatPosBdt(due));
                lblDueFeedback.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #D97706;");
            } else if (change.isPositive()) {
                lblDueFeedback.setText("Paid in full   •   Change Return (ফেরত): " + formatPosBdt(change));
                lblDueFeedback.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #008A32;");
            } else {
                lblDueFeedback.setText("Status: PAID IN FULL");
                lblDueFeedback.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #008A32;");
            }
        };

        cmbConfirmCustomer.valueProperty().addListener((o, a, b) -> syncCustomerAndDuePreview.run());
        tbExistingCust.selectedProperty().addListener((o, a, b) -> syncCustomerAndDuePreview.run());
        tbNewCust.selectedProperty().addListener((o, a, b) -> syncCustomerAndDuePreview.run());
        txtPaid.textProperty().addListener((o, a, b) -> syncCustomerAndDuePreview.run());
        syncCustomerAndDuePreview.run();

        VBox paymentCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_WALLET, "Payment Settlement",
                        "Select tender method and amount received from customer."),
                DialogComponents.formField("Payment Method", true, null, methodRow),
                DialogComponents.formField("Paid Amount (BDT)", true, null, DialogComponents.currencyFieldWrap(txtPaid)),
                quickPaidBtns,
                lblDueFeedback,
                DialogComponents.formField("Invoice / Delivery Note", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtNotes))
        );

        VBox body = new VBox(12.0, summaryCard, customerSectionCard, paymentCard);
        Platform.runLater(txtPaid::selectAll);

        AtomicBoolean saleSubmitStarted = new AtomicBoolean(false);
        List<SaleItemDto> preparedItems = new ArrayList<>(cartLines.size());
        for (PosCartLine l : cartLines) {
            preparedItems.add(new SaleItemDto(
                    l.productId(), l.productName(), l.getQuantity(), l.getUnitPrice(),
                    l.unitCost(), Money.ZERO, l.lineTotal()
            ));
        }
        long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
        Money discountSnapshot = currentDiscount;
        String idempotencySnapshot = activeIdempotencyKey;

        Optional<Sale> completedSale = SmartKhataDialog.<Sale>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title("Confirm Order & Customer Info (অর্ডার নিশ্চিত করুন)")
                .subtitle("Verify customer name, mobile number, settlement & save order.")
                .icon(DialogComponents.SVG_CART, DialogComponents.Tone.SOFT_GREEN)
                .greenHeaderAccent(true)
                .content(body)
                .initialFocus(txtPaid)
                .primaryAction("Confirm & Save Order", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    Money enteredPaid = DialogComponents.parseNonNegativeMoney(txtPaid.getText(), "Paid Amount");
                    Money recordedPaid = enteredPaid.compareTo(netPayable) > 0 ? netPayable : enteredPaid;
                    Money due = netPayable.subtract(recordedPaid);

                    Long resolvedCustomerId;
                    if (tbNewCust.isSelected()) {
                        String newName = txtNewName.getText() != null ? txtNewName.getText().trim() : "";
                        String newPhone = txtNewPhone.getText() != null ? txtNewPhone.getText().trim() : "";
                        String newAddr = txtNewAddress.getText() != null ? txtNewAddress.getText().trim() : "";
                        if (newName.isBlank()) {
                            throw new SmartKhataDialog.ValidationFocusException("Customer Name is required.", txtNewName);
                        }
                        if (newPhone.isBlank()) {
                            throw new SmartKhataDialog.ValidationFocusException("Mobile Number is required.", txtNewPhone);
                        }
                        Money limit = parseNonNegativeMoney(txtNewCreditLimit.getText(), Money.ofBdt(10000));
                        try {
                            Customer createdCustomer = customerService.createCustomer(new CustomerCreateDto(
                                    businessId, newName, newPhone, newAddr.isEmpty() ? null : newAddr,
                                    limit, Money.ZERO, "Added during POS order checkout"
                            ));
                            resolvedCustomerId = createdCustomer.id();
                        } catch (RuntimeException ex) {
                            throw new SmartKhataDialog.ValidationFocusException(
                                    ex.getMessage() != null ? ex.getMessage() : "Could not save customer details.", txtNewPhone);
                        }
                    } else {
                        CustomerOption chosen = cmbConfirmCustomer.getValue();
                        resolvedCustomerId = chosen != null ? chosen.id() : null;
                        if (due.isPositive() && resolvedCustomerId == null) {
                            throw new SmartKhataDialog.ValidationFocusException(
                                    "Walk-in customers cannot have unpaid Baki. Select/enter a customer or pay full payable amount.", txtPaid);
                        }
                    }

                    String payMethod = payGroup.getSelectedToggle() instanceof ToggleButton tb ? tb.getText() : "CASH";
                    String note = txtNotes.getText() != null && !txtNotes.getText().isBlank()
                            ? txtNotes.getText().trim() : "POS Counter Sale";
                    SaleCreateDto dto = new SaleCreateDto(
                            businessId, resolvedCustomerId, preparedItems, discountSnapshot, recordedPaid,
                            payMethod, idempotencySnapshot, note, userId
                    );
                    if (!saleSubmitStarted.compareAndSet(false, true)) {
                        throw new IllegalStateException("Sale submission already in progress.");
                    }
                    try {
                        return saleService.createSale(dto);
                    } catch (RuntimeException ex) {
                        saleSubmitStarted.set(false);
                        if (ex instanceof SmartKhataDialog.ValidationFocusException vex) {
                            throw vex;
                        }
                        throw new SmartKhataDialog.ValidationFocusException(
                                ex.getMessage() != null ? ex.getMessage() : "Unable to save order.", txtPaid);
                    }
                })
                .showAndWait();

        completedSale.ifPresent(saved -> {
            cartLines.clear();
            currentDiscount = Money.ZERO;
            activeIdempotencyKey = UUID.randomUUID().toString();
            recalculateBillSummary();

            resetHistoryFiltersIfExcluding(saved);
            loadPosContextAsync();
            loadSalesHistoryAsync(saved.id());
            refreshDraftsBadge();

            ViewRouter.refreshSalesImpactViews();

            showPostSaleConfirmation(saved);
        });
    }

    private void showPostSaleConfirmation(Sale created) {
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        VBox details = DialogComponents.tintedSectionCard(
                new Label("Net Payable: " + formatPosBdt(created.netAmount())),
                new Label("Paid Amount: " + formatPosBdt(created.paidAmount())),
                new Label("Due (বাকি): " + formatPosBdt(created.dueAmount())),
                DialogComponents.statusBadge("Status: " + created.paymentStatus(),
                        created.dueAmount().isPositive() ? DialogComponents.Tone.WARNING : DialogComponents.Tone.SUCCESS)
        );
        Optional<Boolean> printChosen = SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Sale Completed (বিক্রয় সম্পন্ন)")
                .subtitle("Invoice " + created.invoiceNumber() + " saved successfully!")
                .icon(DialogComponents.SVG_CHECK, DialogComponents.Tone.SUCCESS)
                .content(details)
                .cancelLabel("Done")
                .primaryAction("Print Receipt", DialogComponents.SVG_PRINT, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> Boolean.TRUE)
                .showAndWait();

        if (printChosen.orElse(false)) {
            saleService.getSaleDetail(created.id()).ifPresent(detail ->
                    showReceiptPreviewModal(buildPersistedReceiptText(detail), "Invoice " + created.invoiceNumber()));
        }
        onFocusPosSearch();
    }

    @FXML
    private void onSaveAsDraft() {
        if (cartLines.isEmpty()) {
            AlertHelper.showInfo(null, "Empty Cart", "Add items to the POS cart before saving a draft.");
            return;
        }

        long businessId = getActiveBusinessId();
        CustomerOption cust = cmbPosCustomer != null ? cmbPosCustomer.getValue() : null;
        Long custId = cust != null ? cust.id() : null;
        String custName = cust != null ? cust.name() : "Walk-in Cash Customer";

        List<PosDraftLine> lines = new ArrayList<>();
        for (PosCartLine l : cartLines) {
            lines.add(new PosDraftLine(l.productId(), l.productName(), l.getQuantity(), l.getUnitPrice().paisha()));
        }

        PosDraft newDraft = new PosDraft(
                UUID.randomUUID().toString(),
                businessId,
                LocalDateTime.now().toString(),
                custId,
                custName,
                currentDiscount.paisha(),
                lines
        );

        List<PosDraft> allDrafts = readAllDrafts();
        allDrafts.add(0, newDraft);
        writeAllDrafts(allDrafts);

        cartLines.clear();
        currentDiscount = Money.ZERO;
        activeIdempotencyKey = UUID.randomUUID().toString();
        recalculateBillSummary();
        refreshDraftsBadge();

        AlertHelper.showInfo(null, "Draft Saved",
                "POS bill saved as draft (" + lines.size() + " product line(s)).\nUse 'Held Drafts' at the bottom of the cart anytime to resume it.");
    }

    @FXML
    private void onOpenDraftsDialog() {
        long businessId = getActiveBusinessId();
        List<PosDraft> bizDrafts = readAllDrafts().stream()
                .filter(d -> d.businessId() == businessId)
                .toList();

        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        if (bizDrafts.isEmpty()) {
            AlertHelper.showInfo(owner, "No Held Drafts", "There are no saved POS drafts for this business.");
            refreshDraftsBadge();
            return;
        }

        ObservableList<PosDraft> draftItems = FXCollections.observableArrayList(bizDrafts);
        ListView<PosDraft> listView = new ListView<>(draftItems);
        listView.setPrefHeight(270);
        listView.setStyle("-fx-border-color: #DCE5E9; -fx-border-radius: 9; -fx-background-radius: 9;");
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PosDraft d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setText(null);
                } else {
                    String when;
                    try {
                        when = LocalDateTime.parse(d.createdAtIso()).format(DRAFT_FMT);
                    } catch (Exception e) {
                        when = d.createdAtIso();
                    }
                    long totalPaisha = d.lines().stream()
                            .mapToLong(l -> l.unitPricePaisha() * l.quantity())
                            .sum() - d.discountPaisha();
                    setText(when + "  •  " + d.customerName() + "  •  " + d.lines().size() + " line(s)  •  "
                            + formatPosBdt(Money.ofPaisha(Math.max(0L, totalPaisha))));
                }
            }
        });
        listView.getSelectionModel().selectFirst();

        Button btnDelDraft = new Button("Delete Selected Draft");
        btnDelDraft.getStyleClass().add("sk-prod-row-delete-btn");
        btnDelDraft.setOnAction(e -> {
            PosDraft sel = listView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                deleteDraftById(sel.id());
                draftItems.remove(sel);
            }
        });

        VBox draftCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CLIPBOARD, "Saved Bills on Hold",
                        "Select a bill to restore into the POS counter or remove stale drafts."),
                listView,
                new HBox(btnDelDraft)
        );

        SmartKhataDialog.<PosDraft>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Held POS Drafts")
                .subtitle("Restore an uncompleted bill directly into the active cart.")
                .icon(DialogComponents.SVG_CLIPBOARD, DialogComponents.Tone.SOFT_GREEN)
                .content(draftCard)
                .initialFocus(listView)
                .primaryAction("Load Selected Draft", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    PosDraft sel = listView.getSelectionModel().getSelectedItem();
                    if (sel == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Select a draft bill first.", listView);
                    }
                    return sel;
                })
                .showAndWait()
                .ifPresent(this::restoreDraftIntoCart);
        refreshDraftsBadge();
    }

    private void restoreDraftIntoCart(PosDraft draft) {
        Map<Long, ProductListItemDto> catalogById = new HashMap<>();
        for (ProductListItemDto p : activeCatalog) {
            catalogById.put(p.id(), p);
        }

        cartLines.clear();
        List<String> skippedNames = new ArrayList<>();
        for (PosDraftLine dl : draft.lines()) {
            ProductListItemDto live = catalogById.get(dl.productId());
            if (live == null || live.currentStock() <= 0) {
                skippedNames.add(dl.productName());
                continue;
            }
            int cappedQty = Math.min(dl.quantity(), live.currentStock());
            cartLines.add(new PosCartLine(live, cappedQty, Money.ofPaisha(dl.unitPricePaisha())));
        }

        currentDiscount = Money.ofPaisha(Math.max(0L, draft.discountPaisha()));
        activeIdempotencyKey = UUID.randomUUID().toString();

        if (cmbPosCustomer != null) {
            if (draft.customerId() == null) {
                cmbPosCustomer.getSelectionModel().selectFirst();
            } else {
                cmbPosCustomer.getItems().stream()
                        .filter(opt -> Objects.equals(opt.id(), draft.customerId()))
                        .findFirst()
                        .ifPresent(opt -> cmbPosCustomer.getSelectionModel().select(opt));
            }
        }

        deleteDraftById(draft.id());
        recalculateBillSummary();

        if (!skippedNames.isEmpty()) {
            AlertHelper.showInfo(null, "Draft Restored with Stock Adjustments",
                    "Restored draft bill, skipped unavailable/out-of-stock item(s): " + String.join(", ", skippedNames));
        }
    }

    @FXML
    private void onPosPrintPreview() {
        if (cartLines.isEmpty()) {
            AlertHelper.showInfo(null, "Empty Cart", "Add items to the cart to preview a bill receipt.");
            return;
        }
        String preview = buildDraftReceiptText();
        showReceiptPreviewModal(preview, "Active POS Bill Preview (Draft)");
    }

    @FXML
    private void onSeeAllProducts() {
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;

        TextField filterField = new TextField();
        filterField.setPromptText("Search by product name, barcode, or SKU...");

        ComboBox<String> catFilter = DialogComponents.styleCombo(new ComboBox<>());
        catFilter.getItems().add("All Categories");
        for (Category c : activeCategories) {
            catFilter.getItems().add(c.name());
        }
        catFilter.getSelectionModel().selectFirst();
        catFilter.setPrefWidth(200);

        HBox filterBar = new HBox(10.0,
                DialogComponents.textFieldWrap(DialogComponents.SVG_SEARCH, filterField),
                catFilter);
        HBox.setHgrow(filterBar.getChildren().get(0), Priority.ALWAYS);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        ObservableList<ProductListItemDto> filteredRows = FXCollections.observableArrayList(activeCatalog);
        TableView<ProductListItemDto> table = DialogComponents.styleTable(new TableView<>(filteredRows));
        table.setPrefHeight(390);

        TableColumn<ProductListItemDto, ProductListItemDto> cImg = new TableColumn<>("Img");
        cImg.setPrefWidth(46);
        cImg.setMaxWidth(52);
        cImg.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        cImg.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            {
                iv.setFitWidth(24);
                iv.setFitHeight(24);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
            }
            @Override
            protected void updateItem(ProductListItemDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    iv.setImage(ProductImageResolver.resolve(item.imagePath(), item.name(), item.categoryName()));
                    setGraphic(iv);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        TableColumn<ProductListItemDto, String> cName = new TableColumn<>("Product");
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        cName.setPrefWidth(220);

        TableColumn<ProductListItemDto, String> cCat = new TableColumn<>("Category");
        cCat.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().categoryName() != null ? c.getValue().categoryName() : "General"));

        TableColumn<ProductListItemDto, String> cStock = DialogComponents.rightAlignedColumn("Stock",
                p -> String.valueOf(p.currentStock()));
        cStock.setPrefWidth(75);

        TableColumn<ProductListItemDto, String> cPrice = DialogComponents.rightAlignedColumn("Selling Price",
                p -> formatPosBdt(p.sellingPrice()));
        cPrice.setPrefWidth(115);

        TableColumn<ProductListItemDto, ProductListItemDto> cAction = new TableColumn<>("Action");
        cAction.setPrefWidth(95);
        cAction.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        cAction.setCellFactory(col -> new TableCell<>() {
            private final Button addBtn = new Button("+ Add");
            private ProductListItemDto current;
            {
                addBtn.getStyleClass().add("sk-prod-row-action-btn");
                addBtn.setOnAction(e -> {
                    if (current != null) addProductToCart(current, 1);
                });
            }
            @Override
            protected void updateItem(ProductListItemDto item, boolean empty) {
                super.updateItem(item, empty);
                current = empty ? null : item;
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    addBtn.setDisable(item.currentStock() <= 0);
                    setGraphic(addBtn);
                }
            }
        });

        table.getColumns().addAll(cImg, cName, cCat, cStock, cPrice, cAction);

        Runnable refilter = () -> {
            String q = filterField.getText() != null ? filterField.getText().trim().toLowerCase(Locale.ROOT) : "";
            String cat = catFilter.getValue();
            filteredRows.setAll(activeCatalog.stream().filter(p -> {
                boolean matchesQ = q.isEmpty() || matchesCatalogQuery(p, q);
                boolean matchesCat = cat == null || "All Categories".equals(cat)
                        || (p.categoryName() != null && p.categoryName().equalsIgnoreCase(cat));
                return matchesQ && matchesCat;
            }).toList());
        };
        filterField.textProperty().addListener((o, a, b) -> refilter.run());
        catFilter.setOnAction(e -> refilter.run());

        table.setRowFactory(tv -> {
            TableRow<ProductListItemDto> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    addProductToCart(row.getItem(), 1);
                }
            });
            return row;
        });

        VBox catalogCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "Active Catalog Lookup",
                        "Filter products by name, barcode, SKU or category and add directly to cart."),
                filterBar,
                table
        );

        SmartKhataDialog.<Void>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title("All Active Products (সকল পণ্য)")
                .subtitle("Quickly browse active store catalog and add items to POS cart.")
                .icon(DialogComponents.SVG_CUBE, DialogComponents.Tone.SOFT_GREEN)
                .content(catalogCard)
                .initialFocus(filterField)
                .infoOnly(true)
                .primaryAction("Done", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .showAndWait();
    }

    @FXML
    private void onOpenInventory() {
        ViewRouter.navigateTo(ViewRouter.ViewId.INVENTORY);
    }

    // =====================================================================
    // TOP HEADER MODE TOGGLE & "+ NEW SALE (POS)"
    // =====================================================================

    @FXML
    private void onToggleHistory() {
        applyModeVisibility(!historyModeActive);
        if (historyModeActive) {
            loadSalesHistoryAsync();
        }
    }

    @FXML
    private void onNewSale() {
        if (historyModeActive) {
            applyModeVisibility(false);
            onFocusPosSearch();
            return;
        }
        if (!cartLines.isEmpty()) {
            javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
            boolean ok = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.WARNING,
                    "Start New POS Bill",
                    "Start a fresh POS bill and clear the current cart?",
                    "Unsaved items in the current cart will be removed (use 'Save as Draft' first if needed).",
                    "Clear & Start Fresh"
            );
            if (!ok) return;
            onClearAllCart();
        } else {
            onFocusPosSearch();
        }
    }

    private void applyModeVisibility(boolean showHistory) {
        this.historyModeActive = showHistory;
        if (panePosWorkspace != null) {
            panePosWorkspace.setVisible(!showHistory);
            panePosWorkspace.setManaged(!showHistory);
        }
        if (paneHistoryWorkspace != null) {
            paneHistoryWorkspace.setVisible(showHistory);
            paneHistoryWorkspace.setManaged(showHistory);
        }
        if (btnToggleHistory != null) {
            btnToggleHistory.getStyleClass().remove("pos-history-toggle-btn-active");
            if (showHistory) {
                btnToggleHistory.setText("Back to POS Counter");
                btnToggleHistory.getStyleClass().add("pos-history-toggle-btn-active");
            } else {
                btnToggleHistory.setText("Previous Orders (" + knownTotalOrdersCount + ")");
            }
        }
    }

    // =====================================================================
    // SALES HISTORY & POS PREVIOUS ORDERS SETUP & HANDLERS
    // =====================================================================

    private void setupPosOrdersTable() {
        if (tblPosOrders == null) return;
        tblPosOrders.setItems(posRecentOrdersList);

        colPosOrdInvoice.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().invoiceNumber()));
        colPosOrdDate.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().invoiceDate() != null ? cell.getValue().invoiceDate().format(DATE_FMT) : "—"
        ));
        colPosOrdCustomer.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().customerName()));
        colPosOrdItems.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().itemCount())));
        colPosOrdNet.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().netAmount())));
        colPosOrdPaid.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().paidAmount())));
        colPosOrdDue.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().dueAmount())));
        configureDueMoneyColumn(colPosOrdDue);
        configureStatusBadgeColumn(colPosOrdStatus);
        configureOrderActionsColumn(colPosOrdActions);

        tblPosOrders.setRowFactory(tv -> {
            TableRow<SaleListItemDto> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    showSaleDetailModal(row.getItem());
                }
            });
            return row;
        });
    }

    private void setupSalesTable() {
        tblSales.setItems(saleList);

        colInvoiceNo.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().invoiceNumber()));
        colDate.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().invoiceDate() != null ? cell.getValue().invoiceDate().format(DATE_FMT) : "—"
        ));
        colCustomer.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().customerName()));
        colAmount.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().netAmount())));
        colPaid.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().paidAmount())));
        colDue.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().dueAmount())));
        configureDueMoneyColumn(colDue);
        configureStatusBadgeColumn(colStatus);
        configureOrderActionsColumn(colSalesActions);

        tblSales.setRowFactory(tv -> {
            TableRow<SaleListItemDto> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    showSaleDetailModal(row.getItem());
                }
            });
            return row;
        });
    }

    private void configureDueMoneyColumn(TableColumn<SaleListItemDto, String> column) {
        if (column == null) return;
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    SaleListItemDto row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && row.dueAmount().isPositive()) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #DC2626;");
                    } else {
                        setStyle("-fx-font-weight: normal; -fx-text-fill: #15803D;");
                    }
                }
            }
        });
    }

    private void configureStatusBadgeColumn(TableColumn<SaleListItemDto, String> column) {
        if (column == null) return;
        column.setCellValueFactory(cell -> {
            SaleListItemDto item = cell.getValue();
            if (item.isReversed()) return new SimpleStringProperty("VOIDED");
            return new SimpleStringProperty(item.paymentStatus());
        });
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(status);
                    if ("VOIDED".equalsIgnoreCase(status)) {
                        badge.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-padding: 2 6; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 10px;");
                    } else if ("PAID".equalsIgnoreCase(status)) {
                        badge.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-padding: 2 6; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 10px;");
                    } else if ("PARTIAL".equalsIgnoreCase(status)) {
                        badge.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #B45309; -fx-padding: 2 6; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 10px;");
                    } else {
                        badge.setStyle("-fx-background-color: #FFEDD5; -fx-text-fill: #C2410C; -fx-padding: 2 6; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 10px;");
                    }
                    setGraphic(badge);
                    setText(null);
                }
            }
        });
    }

    private void configureOrderActionsColumn(TableColumn<SaleListItemDto, SaleListItemDto> column) {
        if (column == null) return;
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setCellFactory(col -> new TableCell<>() {
            private final Button btnDetails = new Button("Details");
            private final Button btnEdit = new Button("Edit");
            private final Button btnDelete = new Button("Delete");
            private final HBox box = new HBox(5.0, btnDetails, btnEdit, btnDelete);
            private SaleListItemDto currentItem;

            {
                box.setAlignment(Pos.CENTER);

                btnDetails.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_RECEIPT, "#075C32", 11.5));
                btnDetails.getStyleClass().add("pos-row-action-details");
                btnDetails.setTooltip(new Tooltip("View order details, items & receipt"));
                btnDetails.setOnAction(e -> {
                    if (currentItem != null) {
                        selectSaleInHistory(currentItem);
                        showSaleDetailModal(currentItem);
                    }
                });

                btnEdit.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_EDIT, "#1D4ED8", 11.5));
                btnEdit.getStyleClass().add("pos-row-action-edit");
                btnEdit.setTooltip(new Tooltip("Edit customer, items, prices or payment"));
                btnEdit.setOnAction(e -> {
                    if (currentItem != null) {
                        selectSaleInHistory(currentItem);
                        showEditSaleModal(currentItem);
                    }
                });

                btnDelete.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_TRASH, "#DC2626", 11.5));
                btnDelete.getStyleClass().add("pos-row-action-delete");
                btnDelete.setTooltip(new Tooltip("Delete POS order and restore stock/balances"));
                btnDelete.setOnAction(e -> {
                    if (currentItem != null) {
                        selectSaleInHistory(currentItem);
                        confirmAndDeleteSale(currentItem);
                    }
                });
            }

            @Override
            protected void updateItem(SaleListItemDto item, boolean empty) {
                super.updateItem(item, empty);
                currentItem = empty ? null : item;
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    btnEdit.setDisable(item.isReversed());
                    setGraphic(box);
                    setText(null);
                }
            }
        });
    }

    private void selectSaleInHistory(SaleListItemDto item) {
        if (tblSales != null && item != null) {
            saleList.stream()
                    .filter(s -> s.id() == item.id())
                    .findFirst()
                    .ifPresent(match -> tblSales.getSelectionModel().select(match));
        }
    }

    private void setupDetailItemsTable() {
        tblDetailItems.setItems(detailItemList);

        colItemProduct.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().productName()));
        colItemQty.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().quantity())));
        colItemPrice.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().unitPrice())));
        colItemTotal.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().lineTotal())));
    }

    private void setupStatusFilter() {
        suppressingHistoryFilters = true;
        try {
            if (cmbHistoryDateRange != null) {
                cmbHistoryDateRange.setItems(FXCollections.observableArrayList(
                        "All Dates", "Today", "This Week", "This Month", "Last Month", "Custom Range"
                ));
                cmbHistoryDateRange.setValue("All Dates");
            }
            if (cmbStatusFilter != null) {
                cmbStatusFilter.setItems(FXCollections.observableArrayList("All Statuses", "PAID", "PARTIAL", "BAKI"));
                cmbStatusFilter.getSelectionModel().selectFirst();
            }
            updateHistoryFilterUiState();
        } finally {
            suppressingHistoryFilters = false;
        }
    }

    private void loadSalesHistoryAsync() {
        loadSalesHistoryAsync(null);
    }

    private void loadSalesHistoryAsync(Long preferredSaleId) {
        long businessId = getActiveBusinessId();
        String query = txtSearch != null ? txtSearch.getText() : null;
        String rawStatus = cmbStatusFilter != null ? cmbStatusFilter.getValue() : null;
        String status = (rawStatus == null || "All Statuses".equalsIgnoreCase(rawStatus) || "ALL".equalsIgnoreCase(rawStatus))
                ? null : rawStatus;
        LocalDate[] range = resolveHistoryDateRange();
        LocalDate startDate = range[0];
        LocalDate endDate = range[1];
        boolean filtered = (query != null && !query.trim().isEmpty())
                || status != null
                || startDate != null
                || endDate != null;
        updateHistoryFilterUiState();

        pendingHistoryLoad.cancel(false);
        pendingHistoryLoad = CompletableFuture.supplyAsync(() -> {
            List<SaleListItemDto> list = saleService.searchSales(businessId, query, null, status, startDate, endDate);
            List<SaleListItemDto> allOrders = filtered
                    ? saleService.searchSales(businessId, null, null, null, null, null)
                    : list;
            return new SaleSearchOutcome(list, allOrders, filtered && !allOrders.isEmpty());
        }, executor).thenAccept(outcome -> Platform.runLater(() -> {
            List<SaleListItemDto> list = outcome.rows();
            saleList.setAll(list);
            allLoadedPosOrders = outcome.allOrders();
            knownTotalOrdersCount = allLoadedPosOrders.size();
            applyPosOrdersQuickFilter();
            if (!historyModeActive && btnToggleHistory != null) {
                btnToggleHistory.setText("Previous Orders (" + knownTotalOrdersCount + ")");
            }
            updateHistoryEmptyPlaceholder(outcome.hiddenByFilters(), null);

            long totalSalesPaisha = 0L;
            long totalDuePaisha = 0L;
            int invoiceCount = 0;

            for (SaleListItemDto s : list) {
                if (!s.isReversed()) {
                    totalSalesPaisha += s.netAmount().paisha();
                    totalDuePaisha += s.dueAmount().paisha();
                    invoiceCount++;
                }
            }

            if (lblTotalInvoices != null) lblTotalInvoices.setText(String.valueOf(invoiceCount));
            if (lblTotalSales != null) lblTotalSales.setText(BengaliMoneyFormatter.format(Money.ofPaisha(totalSalesPaisha)));
            if (lblTotalDues != null) lblTotalDues.setText(BengaliMoneyFormatter.format(Money.ofPaisha(totalDuePaisha)));

            Long targetId = preferredSaleId;
            if (targetId == null && tblSales.getSelectionModel().getSelectedItem() != null) {
                targetId = tblSales.getSelectionModel().getSelectedItem().id();
            }
            SaleListItemDto match = null;
            if (targetId != null) {
                long idVal = targetId;
                match = list.stream().filter(s -> s.id() == idVal).findFirst().orElse(null);
            }
            if (match != null) {
                tblSales.getSelectionModel().select(match);
                tblSales.scrollTo(match);
            } else if (!list.isEmpty()) {
                tblSales.getSelectionModel().selectFirst();
            } else {
                showNoSelection();
            }
        })).exceptionally(ex -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.error("Failed to load sales history: {}", cause.getMessage(), cause);
            Platform.runLater(() -> {
                saleList.clear();
                allLoadedPosOrders = List.of();
                knownTotalOrdersCount = 0;
                applyPosOrdersQuickFilter();
                showNoSelection();
                updateHistoryEmptyPlaceholder(false,
                        cause.getMessage() != null ? cause.getMessage() : "Unable to load sales invoices.");
            });
            return null;
        });
    }

    private record SaleSearchOutcome(List<SaleListItemDto> rows, List<SaleListItemDto> allOrders, boolean hiddenByFilters) {}

    @FXML
    private void onPosOrdersSearchReleased() {
        applyPosOrdersQuickFilter();
    }

    private static final int RECENT_POS_ORDER_LIMIT = 8;

    private void applyPosOrdersQuickFilter() {
        List<SaleListItemDto> base = allLoadedPosOrders != null ? allLoadedPosOrders : List.of();
        String q = txtPosOrdersSearch != null && txtPosOrdersSearch.getText() != null
                ? txtPosOrdersSearch.getText().trim().toLowerCase(Locale.ROOT) : "";
        List<SaleListItemDto> matched = q.isEmpty() ? base : base.stream()
                .filter(s -> (s.invoiceNumber() != null && s.invoiceNumber().toLowerCase(Locale.ROOT).contains(q))
                        || (s.customerName() != null && s.customerName().toLowerCase(Locale.ROOT).contains(q))
                        || (s.paymentStatus() != null && s.paymentStatus().toLowerCase(Locale.ROOT).contains(q)))
                .toList();
        List<SaleListItemDto> topEight = matched.stream().limit(RECENT_POS_ORDER_LIMIT).toList();
        posRecentOrdersList.setAll(topEight);
        if (lblPosOrdersCount != null) {
            int total = base.size();
            if (!q.isEmpty()) {
                lblPosOrdersCount.setText("Showing " + topEight.size() + " of " + matched.size() + " matched");
            } else if (total > RECENT_POS_ORDER_LIMIT) {
                lblPosOrdersCount.setText("Last " + RECENT_POS_ORDER_LIMIT + " of " + total + " Orders");
            } else {
                lblPosOrdersCount.setText(total + (total == 1 ? " Order" : " Orders"));
            }
        }
    }

    private LocalDate[] resolveHistoryDateRange() {
        String preset = cmbHistoryDateRange != null ? cmbHistoryDateRange.getValue() : "All Dates";
        LocalDate now = LocalDate.now();
        if ("Today".equals(preset)) {
            return new LocalDate[]{now, now};
        } else if ("This Week".equals(preset)) {
            return new LocalDate[]{now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)), now};
        } else if ("This Month".equals(preset)) {
            return new LocalDate[]{now.withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth())};
        } else if ("Last Month".equals(preset)) {
            LocalDate prev = now.minusMonths(1);
            return new LocalDate[]{prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth())};
        } else if ("Custom Range".equals(preset)) {
            LocalDate s = dpHistoryStart != null ? dpHistoryStart.getValue() : null;
            LocalDate e = dpHistoryEnd != null ? dpHistoryEnd.getValue() : null;
            if (s != null && e != null && e.isBefore(s)) {
                return new LocalDate[]{e, s};
            }
            return new LocalDate[]{s, e};
        }
        return new LocalDate[]{null, null};
    }

    private void updateHistoryFilterUiState() {
        String preset = cmbHistoryDateRange != null ? cmbHistoryDateRange.getValue() : "All Dates";
        boolean isCustom = "Custom Range".equals(preset);
        if (boxHistoryCustomDates != null) {
            boxHistoryCustomDates.setVisible(isCustom);
            boxHistoryCustomDates.setManaged(isCustom);
        }
        String query = txtSearch != null ? txtSearch.getText() : null;
        String st = cmbStatusFilter != null ? cmbStatusFilter.getValue() : "All Statuses";
        boolean hasFilter = (query != null && !query.trim().isEmpty())
                || (st != null && !"All Statuses".equalsIgnoreCase(st))
                || (preset != null && !"All Dates".equals(preset));
        if (btnHistoryClearFilters != null) {
            btnHistoryClearFilters.setVisible(hasFilter);
            btnHistoryClearFilters.setManaged(hasFilter);
        }
        if (lblHistoryFilterHint != null) {
            LocalDate[] r = resolveHistoryDateRange();
            if (r[0] != null && r[1] != null) {
                lblHistoryFilterHint.setText("Date filter: " + r[0] + " to " + r[1]);
            } else if (r[0] != null) {
                lblHistoryFilterHint.setText("Date filter: From " + r[0]);
            } else if (r[1] != null) {
                lblHistoryFilterHint.setText("Date filter: Until " + r[1]);
            } else {
                lblHistoryFilterHint.setText("Showing all dates");
            }
        }
    }

    private void resetHistoryFiltersIfExcluding(Sale saved) {
        if (saved == null) return;
        String query = txtSearch != null ? txtSearch.getText() : null;
        String statusStr = cmbStatusFilter != null ? cmbStatusFilter.getValue() : "All Statuses";
        LocalDate[] range = resolveHistoryDateRange();
        LocalDate invDate = saved.invoiceDate() != null ? saved.invoiceDate().toLocalDate() : LocalDate.now();

        boolean excludedBySearch = query != null && !query.trim().isEmpty();
        boolean excludedByStatus = statusStr != null && !"All Statuses".equalsIgnoreCase(statusStr)
                && !statusStr.equalsIgnoreCase(saved.paymentStatus());
        boolean excludedByDate = (range[0] != null && invDate.isBefore(range[0]))
                || (range[1] != null && invDate.isAfter(range[1]));

        if (excludedBySearch || excludedByStatus || excludedByDate) {
            suppressingHistoryFilters = true;
            try {
                if (txtSearch != null) txtSearch.clear();
                if (cmbStatusFilter != null) cmbStatusFilter.setValue("All Statuses");
                if (cmbHistoryDateRange != null) cmbHistoryDateRange.setValue("All Dates");
                if (dpHistoryStart != null) dpHistoryStart.setValue(null);
                if (dpHistoryEnd != null) dpHistoryEnd.setValue(null);
                updateHistoryFilterUiState();
            } finally {
                suppressingHistoryFilters = false;
            }
        }
    }

    private void updateHistoryEmptyPlaceholder(boolean hiddenByFilters, String errorMessage) {
        if (tblSales == null) return;
        VBox box = new VBox(8.0);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-padding: 36px 20px;");
        if (errorMessage != null) {
            Label title = new Label("Unable to Load Sales Invoices");
            title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #DC2626;");
            Label sub = new Label(errorMessage);
            sub.setWrapText(true);
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748B;");
            Button retry = new Button("Retry");
            retry.getStyleClass().add("sk-dlg-btn-secondary");
            retry.setOnAction(e -> loadSalesHistoryAsync());
            box.getChildren().addAll(title, sub, retry);
        } else if (hiddenByFilters) {
            Label title = new Label("No Matching Sales Invoices");
            title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #475569;");
            Label sub = new Label("Try adjusting your search, date range, or status filter.");
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
            Button clear = new Button("Clear Filters");
            clear.getStyleClass().add("sk-dlg-btn-subtle-green");
            clear.setOnAction(e -> onClearHistoryFilters());
            box.getChildren().addAll(title, sub, clear);
        } else {
            Label title = new Label("No Sales Invoices Yet");
            title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748B;");
            Label sub = new Label("Click '+ New Sale (POS)' to complete your first counter sale.");
            sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
            box.getChildren().addAll(title, sub);
        }
        tblSales.setPlaceholder(box);
    }

    private void showSaleDetails(SaleListItemDto item) {
        vboxNoSelection.setVisible(false);
        vboxNoSelection.setManaged(false);
        vboxInvoiceDetails.setVisible(true);
        vboxInvoiceDetails.setManaged(true);

        selectedDetailSale = null;
        if (lblDetailNotes != null) {
            lblDetailNotes.setVisible(false);
            lblDetailNotes.setManaged(false);
            lblDetailNotes.setText("");
        }

        lblDetailInvoiceNo.setText(item.invoiceNumber());
        lblDetailCustomerInfo.setText(String.format("Customer: %s | Date: %s",
                item.customerName(),
                item.invoiceDate() != null ? item.invoiceDate().format(DATE_FMT) : "—"));

        lblDetailNet.setText(BengaliMoneyFormatter.format(item.netAmount()));
        lblDetailPaid.setText(BengaliMoneyFormatter.format(item.paidAmount()));
        lblDetailDue.setText(BengaliMoneyFormatter.format(item.dueAmount()));

        if (item.isReversed()) {
            lblVoidStatus.setText("VOIDED / REVERSED (বাতিলকৃত চালান)");
            lblVoidStatus.setVisible(true);
            lblVoidStatus.setManaged(true);
            btnVoidSale.setDisable(true);
            if (btnReturnItems != null) btnReturnItems.setDisable(true);
            if (btnEditSaleOrder != null) btnEditSaleOrder.setDisable(true);
        } else {
            lblVoidStatus.setVisible(false);
            lblVoidStatus.setManaged(false);
            btnVoidSale.setDisable(false);
            if (btnReturnItems != null) btnReturnItems.setDisable(false);
            if (btnEditSaleOrder != null) btnEditSaleOrder.setDisable(false);
        }

        boolean canDeleteVoided = item.isReversed() && SessionContext.isOwner();
        if (btnDeleteVoidedSale != null) {
            btnDeleteVoidedSale.setVisible(canDeleteVoided);
            btnDeleteVoidedSale.setManaged(canDeleteVoided);
        }

        pendingDetailLoad.cancel(false);
        pendingDetailLoad = CompletableFuture.supplyAsync(() -> saleService.getSaleDetail(item.id()), executor)
                .thenAccept(opt -> Platform.runLater(() -> {
                    if (opt.isPresent()) {
                        Sale detailSale = opt.get().sale();
                        selectedDetailSale = detailSale;
                        if (lblDetailNotes != null) {
                            String noteText = detailSale.notes();
                            boolean hasNote = noteText != null && !noteText.isBlank();
                            lblDetailNotes.setText(hasNote ? "Note: " + noteText.trim() : "");
                            lblDetailNotes.setVisible(hasNote);
                            lblDetailNotes.setManaged(hasNote);
                        }
                        if (detailSale.reversalReason() != null && !detailSale.reversalReason().isBlank() && detailSale.isReversed()) {
                            lblVoidStatus.setText("VOIDED: " + detailSale.reversalReason());
                        }
                        detailItemList.setAll(opt.get().items());
                    } else {
                        detailItemList.clear();
                    }
                })).exceptionally(ex -> {
                    log.error("Failed to load sale details: {}", ex.getMessage());
                    return null;
                });
    }

    private void showNoSelection() {
        selectedDetailSale = null;
        if (lblDetailNotes != null) {
            lblDetailNotes.setVisible(false);
            lblDetailNotes.setManaged(false);
        }
        if (btnDeleteVoidedSale != null) {
            btnDeleteVoidedSale.setVisible(false);
            btnDeleteVoidedSale.setManaged(false);
        }
        vboxInvoiceDetails.setVisible(false);
        vboxInvoiceDetails.setManaged(false);
        vboxNoSelection.setVisible(true);
        vboxNoSelection.setManaged(true);
        detailItemList.clear();
    }

    @FXML
    private void onSearchKeyReleased() {
        loadSalesHistoryAsync();
    }

    @FXML
    private void onStatusFilterChanged() {
        if (!suppressingHistoryFilters) {
            loadSalesHistoryAsync();
        }
    }

    @FXML
    private void onHistoryDatePresetChanged() {
        if (!suppressingHistoryFilters) {
            updateHistoryFilterUiState();
            loadSalesHistoryAsync();
        }
    }

    @FXML
    private void onHistoryCustomDateChanged() {
        if (!suppressingHistoryFilters) {
            loadSalesHistoryAsync();
        }
    }

    @FXML
    private void onClearHistoryFilters() {
        suppressingHistoryFilters = true;
        try {
            if (txtSearch != null) txtSearch.clear();
            if (cmbStatusFilter != null) cmbStatusFilter.setValue("All Statuses");
            if (cmbHistoryDateRange != null) cmbHistoryDateRange.setValue("All Dates");
            if (dpHistoryStart != null) dpHistoryStart.setValue(null);
            if (dpHistoryEnd != null) dpHistoryEnd.setValue(null);
            updateHistoryFilterUiState();
        } finally {
            suppressingHistoryFilters = false;
        }
        loadSalesHistoryAsync();
    }

    @FXML
    private void onRefresh() {
        refreshData();
    }

    @FXML
    private void onOpenSelectedOrderDetails() {
        SaleListItemDto selected = tblSales != null ? tblSales.getSelectionModel().getSelectedItem() : null;
        if (selected != null) {
            showSaleDetailModal(selected);
        }
    }

    @FXML
    private void onEditSelectedOrder() {
        SaleListItemDto selected = tblSales != null ? tblSales.getSelectionModel().getSelectedItem() : null;
        if (selected != null) {
            showEditSaleModal(selected);
        }
    }

    @FXML
    private void onDeleteSelectedOrder() {
        SaleListItemDto selected = tblSales != null ? tblSales.getSelectionModel().getSelectedItem() : null;
        if (selected != null) {
            confirmAndDeleteSale(selected);
        }
    }

    @FXML
    private void onEditSaleNote() {
        SaleListItemDto selected = tblSales.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        String currentNote = (selectedDetailSale != null && selectedDetailSale.id() != null
                && selectedDetailSale.id().equals(selected.id()) && selectedDetailSale.notes() != null)
                ? selectedDetailSale.notes() : "";

        javafx.stage.Window owner = tblSales != null && tblSales.getScene() != null ? tblSales.getScene().getWindow() : null;
        AlertHelper.promptInput(
                owner,
                "Edit Invoice Note (চালান নোট সম্পাদনা)",
                "Update internal remarks / delivery note for " + selected.invoiceNumber(),
                "Invoice Note",
                currentNote,
                "Enter optional invoice note (leave empty to clear)",
                false,
                "Save Note"
        ).ifPresent(newNote -> {
            try {
                saleService.updateSaleNotes(selected.id(), newNote);
                showSaleDetails(selected);
                AlertHelper.showInfo(owner, "Note Updated", "Invoice note updated for " + selected.invoiceNumber() + ".");
            } catch (Exception ex) {
                log.error("Failed to update sale note: {}", ex.getMessage(), ex);
                showActionError("Update Failed", ex.getMessage());
            }
        });
    }

    @FXML
    private void onDeleteVoidedSale() {
        SaleListItemDto selected = tblSales.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        javafx.stage.Window owner = tblSales != null && tblSales.getScene() != null ? tblSales.getScene().getWindow() : null;
        if (!SessionContext.isOwner()) {
            showActionError("Permission Denied", "Only the Business Owner can delete voided invoices.");
            return;
        }
        if (!selected.isReversed()) {
            showActionError("Active Invoice", "Void this sale invoice first before deleting it.");
            return;
        }

        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Voided Invoice (OWNER)",
                "Permanently delete voided invoice " + selected.invoiceNumber() + "?",
                "This removes the voided invoice, line items, and balanced reversal entries permanently.",
                "Delete Permanently"
        );
        if (confirmed) {
            try {
                saleService.deleteVoidedSale(selected.id());
                refreshData();
                DashboardController dc = ViewRouter.getDashboardController();
                if (dc != null) {
                    dc.refreshDashboard();
                }
                AlertHelper.showInfo(owner, "Voided Invoice Deleted",
                        "Voided invoice " + selected.invoiceNumber() + " deleted permanently.");
            } catch (Exception ex) {
                log.error("Failed to delete voided sale: {}", ex.getMessage(), ex);
                showActionError("Delete Failed", ex.getMessage());
            }
        }
    }

    private void showSaleDetailModal(SaleListItemDto item) {
        javafx.stage.Window owner = rootSalesPane != null && rootSalesPane.getScene() != null
                ? rootSalesPane.getScene().getWindow() : null;
        com.smartkhata.ui.modal.SaleOrderModals.showOrderDetailsModal(
                owner, item, saleService, customerService, inventoryService, this::refreshData);
    }

    private void showEditSaleModal(SaleListItemDto item) {
        javafx.stage.Window owner = rootSalesPane != null && rootSalesPane.getScene() != null
                ? rootSalesPane.getScene().getWindow() : null;
        com.smartkhata.ui.modal.SaleOrderModals.showEditOrderModal(
                owner, item, saleService, customerService, inventoryService, this::refreshData);
    }

    private void confirmAndDeleteSale(SaleListItemDto item) {
        javafx.stage.Window owner = rootSalesPane != null && rootSalesPane.getScene() != null
                ? rootSalesPane.getScene().getWindow() : null;
        com.smartkhata.ui.modal.SaleOrderModals.confirmAndDeleteOrder(
                owner, item, saleService, this::refreshData);
    }

    @FXML
    private void onPrintReceipt() {
        SaleListItemDto selected = tblSales.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<SaleDetailDto> opt = saleService.getSaleDetail(selected.id());
        if (opt.isEmpty()) return;

        showReceiptPreviewModal(buildPersistedReceiptText(opt.get()), "Invoice " + selected.invoiceNumber());
    }

    @FXML
    private void onReturnSaleItems() {
        SaleListItemDto selected = tblSales.getSelectionModel().getSelectedItem();
        javafx.stage.Window owner = tblSales != null && tblSales.getScene() != null ? tblSales.getScene().getWindow() : null;
        com.smartkhata.ui.modal.SaleOrderModals.showReturnSaleItemsModal(
                owner, selected, saleService, inventoryService, this::refreshData);
    }

    @FXML
    private void onVoidSale() {
        SaleListItemDto selected = tblSales.getSelectionModel().getSelectedItem();
        javafx.stage.Window owner = tblSales != null && tblSales.getScene() != null ? tblSales.getScene().getWindow() : null;
        com.smartkhata.ui.modal.SaleOrderModals.showVoidSaleModal(
                owner, selected, saleService, this::refreshData);
    }

    private String buildPersistedReceiptText(SaleDetailDto detail) {
        return com.smartkhata.ui.modal.SaleOrderModals.buildPersistedReceiptText(detail);
    }

    private String buildDraftReceiptText() {
        String shopName = SessionContext.getCurrentBusiness() != null
                ? SessionContext.getCurrentBusiness().name() : "SMARTKHATA POS";
        String cashier = SessionContext.getCurrentUser() != null
                ? SessionContext.getCurrentUser().fullName() : "Cashier";
        CustomerOption cust = cmbPosCustomer != null ? cmbPosCustomer.getValue() : null;
        String custName = cust != null ? cust.name() : "Walk-in Cash Customer";

        Money subtotal = calculateCartSubtotal();
        Money net = calculateCartNet();

        StringBuilder r = new StringBuilder();
        r.append("==================================================\n");
        r.append(centerLine(shopName.toUpperCase(Locale.ROOT), 50)).append("\n");
        r.append(centerLine("*** PRE-BILL PRINT PREVIEW ***", 50)).append("\n");
        r.append("==================================================\n");
        r.append("Date:      ").append(LocalDateTime.now().format(DATE_FMT)).append("\n");
        r.append("Cashier:   ").append(cashier).append("\n");
        r.append("Customer:  ").append(custName).append("\n");
        r.append("--------------------------------------------------\n");
        r.append(String.format("%-24s %5s %9s %9s\n", "Item", "Qty", "Price", "Total"));
        r.append("--------------------------------------------------\n");

        for (PosCartLine line : cartLines) {
            String name = line.productName();
            if (name.length() > 24) name = name.substring(0, 21) + "...";
            r.append(String.format("%-24s %5d %9s %9s\n",
                    name, line.getQuantity(),
                    formatPlainDecimal(line.getUnitPrice()),
                    formatPlainDecimal(line.lineTotal())));
        }

        r.append("--------------------------------------------------\n");
        r.append(String.format("%-35s %14s\n", "Subtotal:", formatPosBdt(subtotal)));
        if (currentDiscount.isPositive()) {
            r.append(String.format("%-35s %14s\n", "Discount:", "-" + formatPosBdt(currentDiscount)));
        }
        r.append(String.format("%-35s %14s\n", "Net Payable:", formatPosBdt(net)));
        r.append("==================================================\n");
        r.append(centerLine("Complete sale (F4) to finalize invoice", 50)).append("\n");
        r.append("==================================================\n");
        return r.toString();
    }

    private void showReceiptPreviewModal(String receiptText, String subtitle) {
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        com.smartkhata.ui.modal.SaleOrderModals.showReceiptPreviewModal(owner, receiptText, subtitle);
    }

    private String centerLine(String text, int width) {
        if (text == null) return "";
        if (text.length() >= width) return text.substring(0, width);
        int leftPad = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, leftPad)) + text;
    }

    // =====================================================================
    // DURABLE LOCAL POS DRAFT PERSISTENCE
    // =====================================================================

    private Path getDraftStoragePath() {
        Path base = AppConfig.getDataDir();
        if (base == null) {
            base = Paths.get(System.getProperty("java.io.tmpdir"), "smartkhata-pos");
        }
        return base.resolve("pos_drafts.tsv");
    }

    private void refreshDraftsBadge() {
        long businessId = getActiveBusinessId();
        long count = readAllDrafts().stream().filter(d -> d.businessId() == businessId).count();
        if (btnRecallDrafts != null) {
            btnRecallDrafts.setText("Held Drafts (" + count + ")");
            btnRecallDrafts.setVisible(count > 0);
            btnRecallDrafts.setManaged(count > 0);
        }
    }

    private synchronized List<PosDraft> readAllDrafts() {
        Path file = getDraftStoragePath();
        if (!Files.exists(file)) return new ArrayList<>();
        List<PosDraft> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length < 7) continue;

                String id = parts[0];
                long bizId = Long.parseLong(parts[1]);
                String iso = parts[2];
                Long custId = parts[3].isBlank() ? null : Long.parseLong(parts[3]);
                String custName = decodeB64(parts[4]);
                long discPaisha = Long.parseLong(parts[5]);

                List<PosDraftLine> items = new ArrayList<>();
                if (!parts[6].isBlank()) {
                    for (String itemTok : parts[6].split(";")) {
                        if (itemTok.isBlank()) continue;
                        String[] f = itemTok.split(",", -1);
                        if (f.length == 4) {
                            items.add(new PosDraftLine(
                                    Long.parseLong(f[0]),
                                    decodeB64(f[1]),
                                    Integer.parseInt(f[2]),
                                    Long.parseLong(f[3])
                            ));
                        }
                    }
                }
                result.add(new PosDraft(id, bizId, iso, custId, custName, discPaisha, items));
            }
        } catch (Exception e) {
            log.warn("Could not read POS drafts cleanly: {}", e.getMessage());
        }
        return result;
    }

    private synchronized void writeAllDrafts(List<PosDraft> drafts) {
        Path file = getDraftStoragePath();
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (PosDraft d : drafts) {
                StringBuilder itemSb = new StringBuilder();
                for (int i = 0; i < d.lines().size(); i++) {
                    if (i > 0) itemSb.append(';');
                    PosDraftLine dl = d.lines().get(i);
                    itemSb.append(dl.productId()).append(',')
                            .append(encodeB64(dl.productName())).append(',')
                            .append(dl.quantity()).append(',')
                            .append(dl.unitPricePaisha());
                }
                lines.add(String.join("\t",
                        d.id(),
                        String.valueOf(d.businessId()),
                        d.createdAtIso(),
                        d.customerId() != null ? String.valueOf(d.customerId()) : "",
                        encodeB64(d.customerName()),
                        String.valueOf(d.discountPaisha()),
                        itemSb.toString()
                ));
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed saving POS drafts: {}", e.getMessage(), e);
        }
    }

    private void deleteDraftById(String id) {
        List<PosDraft> remaining = readAllDrafts().stream()
                .filter(d -> !d.id().equals(id))
                .toList();
        writeAllDrafts(remaining);
    }

    private String encodeB64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((s != null ? s : "").getBytes(StandardCharsets.UTF_8));
    }

    private String decodeB64(String s) {
        if (s == null || s.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    // =====================================================================
    // FORMATTING & PARSING HELPERS
    // =====================================================================

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

    private Money parseNonNegativeMoney(String input, Money fallback) {
        if (input == null || input.trim().isBlank()) return fallback;
        try {
            BigDecimal bd = new BigDecimal(input.trim().replaceAll("[, ]", ""));
            if (bd.signum() < 0) return fallback;
            return Money.ofBdt(bd);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showActionError(String title, String message) {
        javafx.stage.Window owner = txtPosSearch != null && txtPosSearch.getScene() != null ? txtPosSearch.getScene().getWindow() : null;
        AlertHelper.showError(owner, title, message != null ? message : "Unexpected error");
    }

    private long getActiveBusinessId() {
        if (SessionContext.getCurrentBusiness() != null) {
            return SessionContext.getCurrentBusiness().id();
        }
        return 1L;
    }
}

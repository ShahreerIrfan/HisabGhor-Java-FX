package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Category;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.StockMovement;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteSupplierService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.ProductFormModal;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import com.smartkhata.util.PricingCalculator;
import com.smartkhata.util.ProductImageResolver;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for Products, Stock Ledger, and Auditable Inventory Movements.
 * Features search, category filtering, 5 KPI summary cards, pagination, CSV import/export,
 * low-stock alerts, auditable manual adjustments, and cost valuation reporting.
 */
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);
    private static final DateTimeFormatter DATE_SHORT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FULL_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final int PAGE_SIZE = 10;

    private final InventoryService inventoryService = new SqliteInventoryService();
    private final SupplierService supplierService = new SqliteSupplierService();

    // 5 KPI Summary Cards & Header Controls
    @FXML private StackPane cardTotalProducts;
    @FXML private StackPane cardActiveProducts;
    @FXML private StackPane cardLowStock;
    @FXML private StackPane cardOutOfStock;
    @FXML private StackPane cardStockValue;
    @FXML private Label lblTotalProductsCount;
    @FXML private Label lblActiveProductsCount;
    @FXML private Label lblLowStockCount;
    @FXML private Label lblOutOfStockCount;
    @FXML private Label lblTotalValuation;

    @FXML private Button btnCategories;
    @FXML private Button btnAdjustStock;
    @FXML private MenuButton btnImportExport;
    @FXML private Button btnAddProduct;

    // Search & Filter Toolbar
    @FXML private HBox searchContainer;
    @FXML private TextField txtSearch;
    @FXML private Button btnClearSearch;
    @FXML private ComboBox<CategoryOption> cmbCategoryFilter;
    @FXML private CheckBox chkIncludeArchived;
    @FXML private Button btnRefresh;

    // Main Products Table & Pagination
    @FXML private TableView<ProductListItemDto> tblProducts;
    @FXML private TableColumn<ProductListItemDto, String> colRowIndex;
    @FXML private TableColumn<ProductListItemDto, ProductListItemDto> colName;
    @FXML private TableColumn<ProductListItemDto, String> colCategory;
    @FXML private TableColumn<ProductListItemDto, String> colBarcodeSku;
    @FXML private TableColumn<ProductListItemDto, String> colCostPrice;
    @FXML private TableColumn<ProductListItemDto, String> colSellingPrice;
    @FXML private TableColumn<ProductListItemDto, String> colCurrentStock;
    @FXML private TableColumn<ProductListItemDto, String> colStockValue;
    @FXML private TableColumn<ProductListItemDto, String> colStatus;
    @FXML private Label lblPaginationSummary;
    @FXML private HBox paginationBox;

    // Right Side Detail Panel
    @FXML private VBox pnlDetailContainer;
    @FXML private VBox vboxNoSelection;
    @FXML private VBox vboxProductDetails;
    @FXML private ImageView imgDetailProduct;
    @FXML private Label lblDetailName;
    @FXML private Label lblDetailCategoryBarcode;
    @FXML private Label lblDetailSkuUnit;
    @FXML private MenuButton btnDetailMore;
    @FXML private Button btnEditProduct;
    @FXML private Button btnArchiveProduct;
    @FXML private Button btnDeleteProduct;
    @FXML private Button btnEditMovementNote;
    @FXML private Button btnViewAllMovements;
    @FXML private Label lblDetailStock;
    @FXML private Label lblDetailThreshold;
    @FXML private Label lblDetailValue;
    @FXML private Label lblDetailTp;
    @FXML private Label lblDetailMrp;
    @FXML private Label lblDetailAvgCost;
    @FXML private Label lblDetailMargin;
    @FXML private Label lblDetailPricingWarning;

    // Stock Movement History Table
    @FXML private TableView<StockMovement> tblMovements;
    @FXML private TableColumn<StockMovement, String> colMoveDate;
    @FXML private TableColumn<StockMovement, String> colMoveType;
    @FXML private TableColumn<StockMovement, String> colMoveQty;
    @FXML private TableColumn<StockMovement, String> colMoveCost;
    @FXML private TableColumn<StockMovement, String> colMoveNotes;

    // Product Suppliers Table in Detail Pane
    @FXML private TableView<ProductSupplierViewDto> tblProductSuppliers;
    @FXML private TableColumn<ProductSupplierViewDto, String> colPsSupplier;
    @FXML private TableColumn<ProductSupplierViewDto, String> colPsTp;
    @FXML private TableColumn<ProductSupplierViewDto, String> colPsUnit;
    @FXML private TableColumn<ProductSupplierViewDto, String> colPsLead;
    @FXML private TableColumn<ProductSupplierViewDto, String> colPsStatus;

    private final List<ProductListItemDto> allMatchingProducts = new ArrayList<>();
    private final ObservableList<ProductListItemDto> productList = FXCollections.observableArrayList();
    private final ObservableList<StockMovement> movementList = FXCollections.observableArrayList();
    private final ObservableList<ProductSupplierViewDto> detailSupplierList = FXCollections.observableArrayList();
    private int currentPage = 1;

    public record CategoryOption(Long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FXML
    public void initialize() {
        log.info("Initializing InventoryController...");

        setupCardClips();
        setupSearchBar();
        setupProductsTable();
        setupMovementsTable();
        setupProductSuppliersTable();
        loadCategoriesFilter();

        // Selection listener for detail pane and movement history
        tblProducts.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                showProductDetails(newVal);
            } else {
                showNoSelection();
            }
        });

        refreshData();
    }

    private void setupCardClips() {
        for (StackPane card : new StackPane[]{
                cardTotalProducts, cardActiveProducts, cardLowStock, cardOutOfStock, cardStockValue
        }) {
            if (card == null) continue;
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(card.widthProperty());
            clip.heightProperty().bind(card.heightProperty());
            clip.setArcWidth(24.0);
            clip.setArcHeight(24.0);
            card.setClip(clip);
        }
    }

    private void setupSearchBar() {
        if (txtSearch == null) return;

        if (btnClearSearch != null) {
            txtSearch.textProperty().addListener((obs, oldText, newText) -> {
                boolean hasQuery = newText != null && !newText.isBlank();
                btnClearSearch.setVisible(hasQuery);
                btnClearSearch.setManaged(hasQuery);
            });
        }
    }

    private void setupProductsTable() {
        tblProducts.setItems(productList);

        if (colRowIndex != null) {
            colRowIndex.setCellValueFactory(cell -> new SimpleStringProperty(""));
            colRowIndex.setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                        setText(null);
                    } else {
                        int globalRow = (currentPage - 1) * PAGE_SIZE + getIndex() + 1;
                        setText(String.valueOf(globalRow));
                        setStyle("-fx-text-fill: #64748B; -fx-font-weight: 600;");
                    }
                }
            });
        }

        colName.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        colName.setComparator((a, b) -> {
            String n1 = a != null && a.name() != null ? a.name() : "";
            String n2 = b != null && b.name() != null ? b.name() : "";
            return n1.compareToIgnoreCase(n2);
        });
        colName.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            private final StackPane thumbBox = new StackPane(iv);
            private final Label lbl = new Label();
            private final HBox rowBox = new HBox(8.0, thumbBox, lbl);
            {
                iv.setFitWidth(22.0);
                iv.setFitHeight(22.0);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
                thumbBox.setPrefSize(28.0, 28.0);
                thumbBox.setMinSize(28.0, 28.0);
                thumbBox.setMaxSize(28.0, 28.0);
                thumbBox.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E2E8F0; -fx-border-radius: 6px; -fx-background-radius: 6px;");
                lbl.setStyle("-fx-font-weight: 700; -fx-text-fill: #0F172A; -fx-font-size: 12px;");
                lbl.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(lbl, javafx.scene.layout.Priority.ALWAYS);
                rowBox.setAlignment(Pos.CENTER_LEFT);
            }
            @Override
            protected void updateItem(ProductListItemDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setTooltip(null);
                } else {
                    iv.setImage(ProductImageResolver.resolve(item.imagePath(), item.name(), item.categoryName()));
                    lbl.setText(item.name());
                    setGraphic(rowBox);
                    setText(null);
                    setTooltip(new Tooltip(item.name()));
                }
            }
        });

        colCategory.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().categoryName()));
        colCategory.setCellFactory(col -> createTooltipTextCell("-fx-text-fill: #475569;"));

        colBarcodeSku.setCellValueFactory(cell -> {
            String b = cell.getValue().barcode();
            String s = cell.getValue().sku();
            if (b != null && !b.isBlank()) return new SimpleStringProperty(b);
            if (s != null && !s.isBlank()) return new SimpleStringProperty(s);
            return new SimpleStringProperty("—");
        });
        colBarcodeSku.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #334155; -fx-font-family: 'Segoe UI', sans-serif;");
                    ProductListItemDto row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null) {
                        setTooltip(new Tooltip(String.format("Barcode: %s\nSKU: %s",
                                row.barcode() != null && !row.barcode().isBlank() ? row.barcode() : "—",
                                row.sku() != null && !row.sku().isBlank() ? row.sku() : "—")));
                    }
                }
            }
        });

        colCostPrice.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().costPrice())));
        colSellingPrice.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().sellingPrice())));

        colCurrentStock.setCellValueFactory(cell -> {
            ProductListItemDto p = cell.getValue();
            return new SimpleStringProperty(String.format("%d %s", p.currentStock(), p.unit()));
        });
        colCurrentStock.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    ProductListItemDto row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && row.currentStock() <= 0) {
                        setStyle("-fx-font-weight: 800; -fx-text-fill: #DC2626;");
                    } else if (row != null && row.isLowStock()) {
                        setStyle("-fx-font-weight: 800; -fx-text-fill: #D97706;");
                    } else {
                        setStyle("-fx-font-weight: 700; -fx-text-fill: #15803D;");
                    }
                }
            }
        });

        if (colStockValue != null) {
            colStockValue.setCellValueFactory(cell -> {
                ProductListItemDto p = cell.getValue();
                Money val = p.currentStock() > 0 ? p.costPrice().multiply(p.currentStock()) : Money.ZERO;
                return new SimpleStringProperty(BengaliMoneyFormatter.format(val));
            });
            colStockValue.setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                    } else {
                        setText(item);
                        setStyle("-fx-font-weight: 700; -fx-text-fill: #0F172A;");
                    }
                }
            });
        }

        colStatus.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().isArchived() ? "Archived" : "Active"));
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label badge = new Label(status);
                    badge.getStyleClass().add("Archived".equalsIgnoreCase(status) ? "badge-archived" : "badge-active");
                    setGraphic(badge);
                    setText(null);
                }
            }
        });
    }

    private TableCell<ProductListItemDto, String> createTooltipTextCell(String inlineStyle) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle(inlineStyle);
                    if (!"—".equals(item) && !item.isBlank()) {
                        setTooltip(new Tooltip(item));
                    } else {
                        setTooltip(null);
                    }
                }
            }
        };
    }

    private void setupMovementsTable() {
        tblMovements.setItems(movementList);

        colMoveDate.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().createdAt() != null ? cell.getValue().createdAt().format(DATE_SHORT_FMT) : "—"
        ));
        colMoveDate.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    StockMovement mv = getTableRow() != null ? getTableRow().getItem() : null;
                    if (mv != null && mv.createdAt() != null) {
                        setTooltip(new Tooltip(mv.createdAt().format(DATE_FULL_FMT)));
                    } else {
                        setTooltip(null);
                    }
                }
            }
        });

        colMoveType.setCellValueFactory(cell -> new SimpleStringProperty(formatMovementLabel(cell.getValue().movementType())));
        colMoveType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setStyle("-fx-font-weight: 600; -fx-text-fill: #334155;");
                    setTooltip(new Tooltip(item));
                }
            }
        });

        colMoveQty.setCellValueFactory(cell -> {
            int q = cell.getValue().quantity();
            return new SimpleStringProperty((q > 0 ? "+" : "") + q);
        });
        colMoveQty.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.startsWith("+")) {
                        setStyle("-fx-font-weight: 800; -fx-text-fill: #15803D;");
                    } else {
                        setStyle("-fx-font-weight: 800; -fx-text-fill: #DC2626;");
                    }
                }
            }
        });

        colMoveCost.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().unitCost() != null ? BengaliMoneyFormatter.format(cell.getValue().unitCost()) : "—"
        ));

        colMoveNotes.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().notes() != null && !cell.getValue().notes().isBlank() ? cell.getValue().notes() : "—"
        ));
        colMoveNotes.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #475569;");
                    setTooltip(!"—".equals(item) ? new Tooltip(item) : null);
                }
            }
        });

        tblMovements.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (btnEditMovementNote != null) {
                btnEditMovementNote.setDisable(newV == null);
            }
        });
        if (btnEditMovementNote != null) {
            btnEditMovementNote.setDisable(true);
        }

        MenuItem editNoteMenuItem = new MenuItem("Edit Notes / Reason");
        editNoteMenuItem.setOnAction(e -> onEditMovementNote());
        ContextMenu moveMenu = new ContextMenu(editNoteMenuItem);
        tblMovements.setContextMenu(moveMenu);
    }

    private String formatMovementLabel(String type) {
        if (type == null) return "—";
        return switch (type) {
            case "OPENING" -> "Opening Stock";
            case "PURCHASE_IN" -> "Purchase Inward";
            case "SALE_OUT" -> "Sale Outward";
            case "MANUAL_ADJUSTMENT" -> "Adjustment";
            case "RETURN_IN" -> "Customer Return";
            case "RETURN_OUT" -> "Supplier Return";
            default -> type;
        };
    }

    private void loadCategoriesFilter() {
        long businessId = getActiveBusinessId();
        CompletableFuture.supplyAsync(() -> inventoryService.getCategories(businessId))
                .thenAccept(cats -> Platform.runLater(() -> {
                    ObservableList<CategoryOption> options = FXCollections.observableArrayList();
                    options.add(new CategoryOption(null, "All Categories"));
                    for (Category c : cats) {
                        options.add(new CategoryOption(c.id(), c.name()));
                    }
                    if (cmbCategoryFilter != null) {
                        CategoryOption previous = cmbCategoryFilter.getValue();
                        cmbCategoryFilter.setItems(options);
                        if (previous != null && previous.id() != null) {
                            options.stream()
                                    .filter(o -> previous.id().equals(o.id()))
                                    .findFirst()
                                    .ifPresentOrElse(
                                            o -> cmbCategoryFilter.getSelectionModel().select(o),
                                            () -> cmbCategoryFilter.getSelectionModel().selectFirst()
                                    );
                        } else {
                            cmbCategoryFilter.getSelectionModel().selectFirst();
                        }
                    }
                })).exceptionally(ex -> {
                    log.error("Failed to load categories: {}", ex.getMessage());
                    return null;
                });
    }

    public void refreshData() {
        long businessId = getActiveBusinessId();
        String query = txtSearch != null ? txtSearch.getText() : null;
        Long categoryId = cmbCategoryFilter != null && cmbCategoryFilter.getValue() != null ? cmbCategoryFilter.getValue().id() : null;
        boolean includeArchived = chkIncludeArchived != null && chkIncludeArchived.isSelected();

        ProductListItemDto previouslySelected = tblProducts != null ? tblProducts.getSelectionModel().getSelectedItem() : null;
        Long prevId = previouslySelected != null ? previouslySelected.id() : null;

        CompletableFuture.supplyAsync(() -> {
            List<ProductListItemDto> list = inventoryService.searchProducts(businessId, query, categoryId, includeArchived);
            InventorySummaryDto summary = inventoryService.getInventorySummary(businessId);
            return new Object[]{list, summary};
        }).thenAccept(results -> {
            @SuppressWarnings("unchecked")
            List<ProductListItemDto> list = (List<ProductListItemDto>) results[0];
            InventorySummaryDto summary = (InventorySummaryDto) results[1];

            Platform.runLater(() -> {
                allMatchingProducts.clear();
                allMatchingProducts.addAll(list);

                if (lblTotalProductsCount != null) {
                    lblTotalProductsCount.setText(String.valueOf(summary.totalProducts()));
                }
                if (lblActiveProductsCount != null) {
                    lblActiveProductsCount.setText(String.valueOf(summary.totalActiveProducts()));
                }
                if (lblLowStockCount != null) {
                    lblLowStockCount.setText(String.valueOf(summary.lowStockAlertCount()));
                }
                if (lblOutOfStockCount != null) {
                    lblOutOfStockCount.setText(String.valueOf(summary.outOfStockCount()));
                }
                if (lblTotalValuation != null) {
                    lblTotalValuation.setText(BengaliMoneyFormatter.format(summary.totalInventoryValue()));
                }

                // Keep the selected product's page in view if possible
                if (prevId != null) {
                    for (int i = 0; i < allMatchingProducts.size(); i++) {
                        if (allMatchingProducts.get(i).id() == prevId) {
                            currentPage = (i / PAGE_SIZE) + 1;
                            break;
                        }
                    }
                }

                applyPagination(prevId);
            });
        }).exceptionally(ex -> {
            log.error("Failed to load inventory asynchronously: {}", ex.getMessage(), ex);
            return null;
        });
    }

    private void applyPagination(Long targetSelectId) {
        int total = allMatchingProducts.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        if (currentPage > totalPages) currentPage = totalPages;
        if (currentPage < 1) currentPage = 1;

        int fromIdx = total == 0 ? 0 : (currentPage - 1) * PAGE_SIZE;
        int toIdx = Math.min(total, fromIdx + PAGE_SIZE);

        productList.setAll(allMatchingProducts.subList(fromIdx, toIdx));

        if (lblPaginationSummary != null) {
            if (total == 0) {
                lblPaginationSummary.setText("Showing 0 of 0 products");
            } else {
                lblPaginationSummary.setText(String.format("Showing %d – %d of %d products", fromIdx + 1, toIdx, total));
            }
        }

        renderPaginationButtons(totalPages);

        if (targetSelectId != null) {
            productList.stream()
                    .filter(p -> p.id() == targetSelectId)
                    .findFirst()
                    .ifPresentOrElse(
                            p -> {
                                tblProducts.getSelectionModel().select(p);
                                showProductDetails(p);
                            },
                            () -> selectFirstOrClear()
                    );
        } else {
            selectFirstOrClear();
        }
    }

    private void selectFirstOrClear() {
        if (!productList.isEmpty()) {
            tblProducts.getSelectionModel().selectFirst();
        } else {
            showNoSelection();
        }
    }

    private void renderPaginationButtons(int totalPages) {
        if (paginationBox == null) return;
        paginationBox.getChildren().clear();

        Button prevBtn = new Button("‹");
        prevBtn.getStyleClass().add("inv-page-btn");
        prevBtn.setDisable(currentPage <= 1);
        prevBtn.setOnAction(e -> {
            if (currentPage > 1) {
                currentPage--;
                applyPagination(null);
            }
        });
        paginationBox.getChildren().add(prevBtn);

        int windowStart = Math.max(1, currentPage - 2);
        int windowEnd = Math.min(totalPages, windowStart + 4);
        windowStart = Math.max(1, windowEnd - 4);

        if (windowStart > 1) {
            paginationBox.getChildren().add(buildPageButton(1));
            if (windowStart > 2) {
                Label ellipsis = new Label("...");
                ellipsis.setStyle("-fx-text-fill: #64748B; -fx-padding: 0 3px;");
                paginationBox.getChildren().add(ellipsis);
            }
        }

        for (int p = windowStart; p <= windowEnd; p++) {
            paginationBox.getChildren().add(buildPageButton(p));
        }

        if (windowEnd < totalPages) {
            if (windowEnd < totalPages - 1) {
                Label ellipsis = new Label("...");
                ellipsis.setStyle("-fx-text-fill: #64748B; -fx-padding: 0 3px;");
                paginationBox.getChildren().add(ellipsis);
            }
            paginationBox.getChildren().add(buildPageButton(totalPages));
        }

        Button nextBtn = new Button("›");
        nextBtn.getStyleClass().add("inv-page-btn");
        nextBtn.setDisable(currentPage >= totalPages);
        nextBtn.setOnAction(e -> {
            if (currentPage < totalPages) {
                currentPage++;
                applyPagination(null);
            }
        });
        paginationBox.getChildren().add(nextBtn);
    }

    private Button buildPageButton(int pageNum) {
        Button btn = new Button(String.valueOf(pageNum));
        btn.getStyleClass().add(pageNum == currentPage ? "inv-page-btn-active" : "inv-page-btn");
        btn.setOnAction(e -> {
            if (currentPage != pageNum) {
                currentPage = pageNum;
                applyPagination(null);
            }
        });
        return btn;
    }

    private void showProductDetails(ProductListItemDto item) {
        vboxNoSelection.setVisible(false);
        vboxNoSelection.setManaged(false);
        vboxProductDetails.setVisible(true);
        vboxProductDetails.setManaged(true);

        lblDetailName.setText(item.name());
        if (lblDetailCategoryBarcode != null) {
            lblDetailCategoryBarcode.setText(String.format("Category: %s  |  Barcode: %s",
                    item.categoryName() != null ? item.categoryName() : "—",
                    item.barcode() != null && !item.barcode().isBlank() ? item.barcode() : "—"));
        }
        if (lblDetailSkuUnit != null) {
            String packInfo = item.packSize() > 1
                    ? String.format(" | Buy: 1 %s = %d %s", item.purchaseUnit(), item.packSize(), item.unit())
                    : "";
            lblDetailSkuUnit.setText(String.format("SKU: %s  |  Unit: %s%s",
                    item.sku() != null && !item.sku().isBlank() ? item.sku() : "—",
                    item.unit(), packInfo));
        }

        if (imgDetailProduct != null) {
            imgDetailProduct.setImage(ProductImageResolver.resolve(item.imagePath(), item.name(), item.categoryName()));
        }
        boolean ownerAllowed = SessionContext.getCurrentUser() == null || SessionContext.getCurrentUser().role().isOwner();
        if (btnArchiveProduct != null) {
            btnArchiveProduct.setDisable(!ownerAllowed);
            btnArchiveProduct.setTooltip(ownerAllowed ? null : new Tooltip("Owner permission required to archive/restore products."));
        }
        if (btnDeleteProduct != null) {
            btnDeleteProduct.setDisable(!ownerAllowed);
            btnDeleteProduct.setTooltip(ownerAllowed ? new Tooltip("Delete product (only allowed without sales/purchase history)") : new Tooltip("Owner permission required to delete products."));
        }

        lblDetailStock.setText(String.format("%d %s", item.currentStock(), item.unit()));
        if (item.currentStock() <= 0) {
            lblDetailStock.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #DC2626;");
        } else if (item.isLowStock()) {
            lblDetailStock.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #D97706;");
        } else {
            lblDetailStock.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #15803D;");
        }

        lblDetailThreshold.setText(String.format("%d %s", item.lowStockThreshold(), item.unit()));

        // Documented Cost Method: Weighted average cost_price * non-negative stock count
        Money valuation = item.currentStock() > 0 ? item.costPrice().multiply(item.currentStock()) : Money.ZERO;
        lblDetailValue.setText(BengaliMoneyFormatter.format(valuation));

        if (lblDetailTp != null) {
            lblDetailTp.setText(BengaliMoneyFormatter.format(item.tradePrice()));
        }
        if (lblDetailMrp != null) {
            lblDetailMrp.setText(BengaliMoneyFormatter.format(item.mrp()));
        }
        if (lblDetailAvgCost != null) {
            lblDetailAvgCost.setText(BengaliMoneyFormatter.format(item.costPrice()));
        }
        if (lblDetailMargin != null) {
            Money profit = item.profitAmount();
            double margin = item.profitMarginPercent();
            double markup = item.markupPercent();
            lblDetailMargin.setText(String.format("%s (%.1f%% / %.1f%%)",
                    BengaliMoneyFormatter.format(profit), margin, markup));
            lblDetailMargin.setStyle(profit.isNegative()
                    ? "-fx-font-size: 11.5px; -fx-font-weight: 800; -fx-text-fill: #DC2626;"
                    : "-fx-font-size: 11.5px; -fx-font-weight: 800; -fx-text-fill: #15803D;");
        }
        if (lblDetailPricingWarning != null) {
            List<String> warns = new ArrayList<>();
            if (PricingCalculator.isSellingBelowCost(item.sellingPrice(), item.effectiveCostBasis())) {
                warns.add("Warning: Selling Price is below purchase/inventory cost!");
            }
            if (PricingCalculator.isSellingAboveMrp(item.sellingPrice(), item.mrp())) {
                warns.add("Notice: Default Selling Price exceeds configured MRP.");
            }
            if (!warns.isEmpty()) {
                lblDetailPricingWarning.setText(String.join(" ", warns));
                lblDetailPricingWarning.setVisible(true);
                lblDetailPricingWarning.setManaged(true);
            } else {
                lblDetailPricingWarning.setVisible(false);
                lblDetailPricingWarning.setManaged(false);
            }
        }

        btnArchiveProduct.getStyleClass().removeAll("inv-detail-action-archive", "btn-restore-soft");
        if (item.isArchived()) {
            btnArchiveProduct.setText("Restore");
            btnArchiveProduct.getStyleClass().add("btn-restore-soft");
        } else {
            btnArchiveProduct.setText("Archive");
            btnArchiveProduct.getStyleClass().add("inv-detail-action-archive");
        }

        // Load stock movements & mapped suppliers asynchronously
        CompletableFuture.supplyAsync(() -> inventoryService.getProductMovements(item.id()))
                .thenAccept(movements -> Platform.runLater(() -> movementList.setAll(movements)))
                .exceptionally(ex -> {
                    log.error("Failed to load movements: {}", ex.getMessage(), ex);
                    return null;
                });

        CompletableFuture.supplyAsync(() -> inventoryService.getProductSuppliers(item.id(), true))
                .thenAccept(mappings -> Platform.runLater(() -> detailSupplierList.setAll(mappings)))
                .exceptionally(ex -> {
                    log.error("Failed to load product suppliers: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    private void showNoSelection() {
        vboxProductDetails.setVisible(false);
        vboxProductDetails.setManaged(false);
        vboxNoSelection.setVisible(true);
        vboxNoSelection.setManaged(true);
        movementList.clear();
        detailSupplierList.clear();
    }

    private void setupProductSuppliersTable() {
        if (tblProductSuppliers == null) return;
        tblProductSuppliers.setItems(detailSupplierList);
        if (colPsSupplier != null) {
            colPsSupplier.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().supplierName()));
        }
        if (colPsTp != null) {
            colPsTp.setCellValueFactory(c -> new SimpleStringProperty(
                    BengaliMoneyFormatter.format(c.getValue().tradePrice())));
        }
        if (colPsUnit != null) {
            colPsUnit.setCellValueFactory(c -> {
                ProductSupplierViewDto d = c.getValue();
                String txt = d.packSize() > 1
                        ? d.purchaseUnit() + " (" + d.packSize() + " " + d.baseUnit() + ")"
                        : d.purchaseUnit();
                return new SimpleStringProperty(txt);
            });
        }
        if (colPsLead != null) {
            colPsLead.setCellValueFactory(c -> new SimpleStringProperty(
                    "MOQ " + c.getValue().minOrderQty() + " / " + c.getValue().leadTimeDays() + "d"));
        }
        if (colPsStatus != null) {
            colPsStatus.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().isPreferred() ? "Preferred" : (c.getValue().isActive() ? "Active" : "Inactive")));
        }
    }

    @FXML
    private void onSearchKeyReleased() {
        currentPage = 1;
        refreshData();
    }

    @FXML
    private void onClearSearch() {
        if (txtSearch != null) {
            txtSearch.clear();
            txtSearch.requestFocus();
        }
        currentPage = 1;
        refreshData();
    }

    @FXML
    private void onCategoryFilterChanged() {
        currentPage = 1;
        refreshData();
    }

    @FXML
    private void onToggleIncludeArchived() {
        currentPage = 1;
        refreshData();
    }

    @FXML
    private void onRefresh() {
        refreshData();
    }

    @FXML
    private void onViewAllMovements() {
        ProductListItemDto selected = tblProducts.getSelectionModel().getSelectedItem();
        Window owner = tblProducts != null && tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null;
        if (selected == null) {
            AlertHelper.showInfo(owner, "No Product Selected",
                    "Please select a product first to inspect its complete stock movement history.");
            return;
        }

        HBox summaryStrip = new HBox(16.0);
        summaryStrip.getStyleClass().add("sk-dlg-section-tint");
        summaryStrip.setAlignment(Pos.CENTER_LEFT);
        Label lblProdName = new Label(selected.name());
        lblProdName.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #18283B;");
        Label lblStockBadge = DialogComponents.statusBadge(
                "Current Stock: " + selected.currentStock() + " " + selected.unit(),
                selected.currentStock() <= 0 ? DialogComponents.Tone.DANGER
                        : (selected.isLowStock() ? DialogComponents.Tone.WARNING : DialogComponents.Tone.SUCCESS)
        );
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        summaryStrip.getChildren().addAll(
                DialogComponents.iconBox(DialogComponents.SVG_CUBE, "#008A32", 18.0),
                lblProdName, sp, lblStockBadge
        );

        TableView<StockMovement> fullTable = DialogComponents.styleTable(
                new TableView<>(FXCollections.observableArrayList(movementList))
        );
        fullTable.setPrefHeight(390.0);

        TableColumn<StockMovement, String> cDate = new TableColumn<>("Timestamp");
        cDate.setPrefWidth(135);
        cDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().createdAt() != null ? c.getValue().createdAt().format(DATE_FULL_FMT) : "—"));

        TableColumn<StockMovement, String> cType = new TableColumn<>("Movement Type");
        cType.setPrefWidth(140);
        cType.setCellValueFactory(c -> new SimpleStringProperty(formatMovementLabel(c.getValue().movementType())));

        TableColumn<StockMovement, String> cQty = DialogComponents.rightAlignedColumn("Qty Change",
                m -> (m.quantity() > 0 ? "+" : "") + m.quantity());
        cQty.setPrefWidth(90);

        TableColumn<StockMovement, String> cCost = DialogComponents.rightAlignedColumn("Unit Cost",
                m -> m.unitCost() != null ? BengaliMoneyFormatter.format(m.unitCost()) : "—");
        cCost.setPrefWidth(110);

        TableColumn<StockMovement, String> cRef = new TableColumn<>("Reference");
        cRef.setPrefWidth(110);
        cRef.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().referenceType() != null ? c.getValue().referenceType()
                        + (c.getValue().referenceId() != null ? " #" + c.getValue().referenceId() : "") : "—"));

        TableColumn<StockMovement, String> cNotes = new TableColumn<>("Notes / Reason");
        cNotes.setPrefWidth(185);
        cNotes.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().notes() != null && !c.getValue().notes().isBlank() ? c.getValue().notes() : "—"));

        fullTable.getColumns().addAll(cDate, cType, cQty, cCost, cRef, cNotes);
        fullTable.setPlaceholder(DialogComponents.emptyState(
                DialogComponents.SVG_CLIPBOARD, "No Stock Movements",
                "Stock movements from opening balances, GRNs, sales, and adjustments appear here."
        ));

        VBox body = new VBox(14.0, summaryStrip, DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CLIPBOARD, "Auditable Stock Ledger",
                        "Chronological immutable history of inward and outward inventory movements."),
                fullTable
        ));

        SmartKhataDialog.<Void>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.LARGE)
                .title("Stock Movement History (মজুদ চলাচল খাতা)")
                .subtitle("Complete auditable stock ledger for " + selected.name())
                .icon(DialogComponents.SVG_CLIPBOARD, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .infoOnly(true)
                .primaryAction("Close", DialogComponents.SVG_CLOSE, SmartKhataDialog.ActionTone.PRIMARY)
                .showAndWait();
    }

    @FXML
    private void onExportInventoryCsv() {
        long businessId = getActiveBusinessId();
        String query = txtSearch != null ? txtSearch.getText() : null;
        Long categoryId = cmbCategoryFilter != null && cmbCategoryFilter.getValue() != null ? cmbCategoryFilter.getValue().id() : null;
        boolean includeArchived = chkIncludeArchived != null && chkIncludeArchived.isSelected();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Inventory Catalog CSV");
        chooser.setInitialFileName("smartkhata_inventory_" + java.time.LocalDate.now() + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));
        File file = chooser.showSaveDialog(tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null);
        if (file == null) return;

        try {
            List<ProductListItemDto> items = inventoryService.searchProducts(businessId, query, categoryId, includeArchived);
            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                writer.write('\uFEFF'); // UTF-8 BOM for Excel Bengali support
                writer.write("Name,Category,Barcode,SKU,Unit,CostPriceBDT,SellingPriceBDT,CurrentStock,LowStockThreshold,StockValueBDT,Status\n");
                for (ProductListItemDto p : items) {
                    Money val = p.currentStock() > 0 ? p.costPrice().multiply(p.currentStock()) : Money.ZERO;
                    writer.write(String.join(",",
                            csvEscape(p.name()),
                            csvEscape(p.categoryName()),
                            csvEscape(p.barcode()),
                            csvEscape(p.sku()),
                            csvEscape(p.unit()),
                            csvEscape(p.costPrice().toBdt().toPlainString()),
                            csvEscape(p.sellingPrice().toBdt().toPlainString()),
                            String.valueOf(p.currentStock()),
                            String.valueOf(p.lowStockThreshold()),
                            csvEscape(val.toBdt().toPlainString()),
                            csvEscape(p.isArchived() ? "Archived" : "Active")
                    ));
                    writer.write('\n');
                }
            }
            AlertHelper.showInfo(null, "Export Successful",
                    String.format("Exported %d products cleanly to:\n%s", items.size(), file.getAbsolutePath()));
        } catch (Exception e) {
            log.error("Failed to export inventory CSV: {}", e.getMessage(), e);
            AlertHelper.showStartupError(null, "Export Failed", e.getMessage(), e);
        }
    }

    @FXML
    private void onDownloadCsvTemplate() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Product CSV Import Template");
        chooser.setInitialFileName("smartkhata_product_import_template.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));
        File file = chooser.showSaveDialog(tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null);
        if (file == null) return;

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("Name,Category,Barcode,SKU,Unit,CostPriceBDT,SellingPriceBDT,LowStockThreshold,OpeningStock\n");
            writer.write("\"Sample Mineral Water (500 ml)\",\"Beverages\",\"894110099001\",\"BEV-WAT-500\",\"pcs\",15,20,10,24\n");
            AlertHelper.showInfo(null, "Template Saved", "CSV import template saved to:\n" + file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to write CSV template: {}", e.getMessage(), e);
            AlertHelper.showStartupError(null, "Save Template Failed", e.getMessage(), e);
        }
    }

    @FXML
    private void onImportInventoryCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Products from CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));
        File file = chooser.showOpenDialog(tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null);
        if (file == null || !file.exists()) return;

        long businessId = getActiveBusinessId();
        int imported = 0;
        int skipped = 0;
        StringBuilder errors = new StringBuilder();

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, Long> categoryMap = new HashMap<>();
            for (Category c : inventoryService.getCategories(businessId)) {
                categoryMap.put(c.name().trim().toLowerCase(), c.id());
            }

            String headerLine = reader.readLine();
            if (headerLine == null) {
                AlertHelper.showInfo(null, "Empty File", "The selected CSV file has no rows.");
                return;
            }

            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;
                List<String> cols = parseCsvRow(line);
                String name = cols.size() > 0 ? cols.get(0).trim() : "";
                if (name.isBlank()) {
                    skipped++;
                    continue;
                }
                try {
                    String catName = cols.size() > 1 ? cols.get(1).trim() : "";
                    Long catId = null;
                    if (!catName.isBlank() && !"—".equals(catName)) {
                        String key = catName.toLowerCase();
                        catId = categoryMap.get(key);
                        if (catId == null) {
                            Category created = inventoryService.createCategory(businessId, catName, null);
                            catId = created.id();
                            categoryMap.put(key, catId);
                        }
                    }

                    String barcode = cols.size() > 2 ? cols.get(2).trim() : "";
                    String sku = cols.size() > 3 ? cols.get(3).trim() : "";
                    String unit = cols.size() > 4 && !cols.get(4).trim().isBlank() ? cols.get(4).trim() : "pcs";
                    Money cost = cols.size() > 5 ? parseMoney(cols.get(5)) : Money.ZERO;
                    Money sell = cols.size() > 6 ? parseMoney(cols.get(6)) : Money.ZERO;
                    int threshold = cols.size() > 7 ? Math.max(0, parseInteger(cols.get(7), 5)) : 5;
                    int opening = cols.size() > 8 ? Math.max(0, parseInteger(cols.get(8), 0)) : 0;

                    inventoryService.createProduct(new ProductCreateDto(
                            businessId, catId, name, barcode, sku, unit,
                            cost, sell, threshold, opening, "Imported via CSV"
                    ));
                    imported++;
                } catch (Exception rowEx) {
                    skipped++;
                    if (errors.length() < 300) {
                        errors.append("Line ").append(lineNum).append(": ").append(rowEx.getMessage()).append("\n");
                    }
                }
            }

            loadCategoriesFilter();
            refreshData();
            String msg = String.format("Imported: %d products\nSkipped: %d rows", imported, skipped);
            if (!errors.isEmpty()) {
                msg += "\n\nNotes:\n" + errors;
            }
            AlertHelper.showInfo(null, "CSV Import Complete", msg);
        } catch (Exception e) {
            log.error("CSV import failed: {}", e.getMessage(), e);
            AlertHelper.showStartupError(null, "Import Failed", e.getMessage(), e);
        }
    }

    private static List<String> parseCsvRow(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @FXML
    private void onManageCategories() {
        Window owner = tblProducts != null && tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null;

        TextField txtCatName = new TextField();
        txtCatName.setPromptText("e.g. Grocery, Bakery, Drinks");
        TextField txtCatDesc = new TextField();
        txtCatDesc.setPromptText("Optional description");

        Button btnSaveCat = new Button("+ Add Category");
        btnSaveCat.getStyleClass().add("sk-dlg-btn-primary");
        btnSaveCat.setPrefHeight(40.0);

        GridPane addRowGrid = DialogComponents.responsiveTwoColGrid(
                480.0,
                DialogComponents.formField("Category Name", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, txtCatName)),
                DialogComponents.formField("Description", false, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txtCatDesc))
        );
        HBox addFooter = new HBox(btnSaveCat);
        addFooter.setAlignment(Pos.CENTER_RIGHT);

        VBox addCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_TAG, "Add New Category (নতুন ক্যাটাগরি)",
                        "Create product categories for cleaner catalog filtering and reporting."),
                addRowGrid,
                addFooter
        );

        TableView<Category> tblCategories = DialogComponents.styleTable(new TableView<>());
        tblCategories.setPrefHeight(260);

        TableColumn<Category, String> colCatName = new TableColumn<>("Category Name");
        colCatName.setPrefWidth(190);
        colCatName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));

        TableColumn<Category, String> colDesc = new TableColumn<>("Description");
        colDesc.setPrefWidth(230);
        colDesc.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().description() != null && !data.getValue().description().isBlank()
                        ? data.getValue().description() : "—"));

        TableColumn<Category, Category> colCatActions = new TableColumn<>("Actions");
        colCatActions.setPrefWidth(145);
        colCatActions.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));

        Runnable refreshCatTable = () -> {
            long businessId = getActiveBusinessId();
            CompletableFuture.supplyAsync(() -> inventoryService.getCategories(businessId))
                    .thenAccept(cats -> Platform.runLater(() ->
                            tblCategories.setItems(FXCollections.observableArrayList(cats))))
                    .exceptionally(ex -> {
                        log.error("Failed to load categories: {}", ex.getMessage());
                        return null;
                    });
        };

        colCatActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnEdit = new Button("Edit");
            private final Button btnDel = new Button("Delete");
            private final HBox rowBox = new HBox(6, btnEdit, btnDel);
            private Category currentCat;
            {
                btnEdit.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_EDIT, "#008A32", 12.0));
                btnEdit.getStyleClass().add("sk-prod-row-action-btn");
                btnDel.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_TRASH, "#DC3545", 12.0));
                btnDel.getStyleClass().add("sk-prod-row-delete-btn");
                rowBox.setAlignment(Pos.CENTER_LEFT);

                btnEdit.setOnAction(e -> {
                    if (currentCat == null) return;
                    TextField tfName = new TextField(currentCat.name());
                    TextField tfDesc = new TextField(currentCat.description() != null ? currentCat.description() : "");
                    VBox editBody = DialogComponents.sectionCard(
                            DialogComponents.formField("Category Name", true, null,
                                    DialogComponents.textFieldWrap(DialogComponents.SVG_TAG, tfName)),
                            DialogComponents.formField("Description", false, null,
                                    DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, tfDesc))
                    );
                    Window dlgOwner = tblCategories.getScene() != null ? tblCategories.getScene().getWindow() : owner;
                    SmartKhataDialog.<String[]>builder()
                            .owner(dlgOwner)
                            .size(SmartKhataDialog.Size.SMALL)
                            .title("Edit Category (ক্যাটাগরি পরিবর্তন)")
                            .subtitle("Update category name or description for " + currentCat.name())
                            .icon(DialogComponents.SVG_EDIT, DialogComponents.Tone.SOFT_GREEN)
                            .content(editBody)
                            .initialFocus(tfName)
                            .primaryAction("Update Category", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                            .onSubmit(() -> {
                                String n = tfName.getText() != null ? tfName.getText().trim() : "";
                                if (n.isBlank()) {
                                    throw new SmartKhataDialog.ValidationFocusException("Category Name cannot be empty.", tfName);
                                }
                                return new String[]{n, tfDesc.getText() != null ? tfDesc.getText().trim() : ""};
                            })
                            .showAndWait()
                            .ifPresent(pair -> {
                                try {
                                    inventoryService.updateCategory(currentCat.id(), pair[0], pair[1]);
                                    refreshCatTable.run();
                                    loadCategoriesFilter();
                                    refreshData();
                                } catch (Exception ex) {
                                    AlertHelper.showStartupError(dlgOwner, "Update Failed", ex.getMessage(), ex);
                                }
                            });
                });

                btnDel.setOnAction(e -> {
                    if (currentCat == null) return;
                    Window dlgOwner = tblCategories.getScene() != null ? tblCategories.getScene().getWindow() : owner;
                    boolean ok = AlertHelper.confirm(
                            dlgOwner,
                            SmartKhataDialog.ActionTone.DANGER,
                            "Delete Category",
                            "Delete category '" + currentCat.name() + "'?",
                            "Products currently assigned to this category will remain active without a category.",
                            "Delete Category"
                    );
                    if (ok) {
                        try {
                            inventoryService.deleteCategory(currentCat.id());
                            refreshCatTable.run();
                            loadCategoriesFilter();
                            refreshData();
                        } catch (Exception ex) {
                            AlertHelper.showStartupError(dlgOwner, "Delete Failed", ex.getMessage(), ex);
                        }
                    }
                });
            }
            @Override
            protected void updateItem(Category cat, boolean empty) {
                super.updateItem(cat, empty);
                currentCat = empty ? null : cat;
                setGraphic(empty || cat == null ? null : rowBox);
            }
        });

        tblCategories.getColumns().addAll(colCatName, colDesc, colCatActions);
        tblCategories.setPlaceholder(DialogComponents.emptyState(
                DialogComponents.SVG_TAG, "No categories yet",
                "Use the form above to add your first store product category."
        ));

        VBox tableCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CLIPBOARD, "Existing Categories",
                        "Edit names or remove unused product categories."),
                tblCategories
        );

        VBox body = new VBox(14.0, addCard, tableCard);

        btnSaveCat.setOnAction(e -> {
            Optional<SmartKhataDialog.DialogHandle> handleOpt = SmartKhataDialog.findHandle(body);
            handleOpt.ifPresent(SmartKhataDialog.DialogHandle::clearError);
            String name = txtCatName.getText() != null ? txtCatName.getText().trim() : "";
            String desc = txtCatDesc.getText() != null ? txtCatDesc.getText().trim() : "";
            if (name.isBlank()) {
                handleOpt.ifPresent(h -> h.showError("Category Name is required.", txtCatName));
                return;
            }
            try {
                inventoryService.createCategory(getActiveBusinessId(), name, desc);
                txtCatName.clear();
                txtCatDesc.clear();
                refreshCatTable.run();
                loadCategoriesFilter();
            } catch (Exception ex) {
                log.error("Failed to add category: {}", ex.getMessage());
                handleOpt.ifPresent(h -> h.showError(ex.getMessage(), txtCatName));
            }
        });

        refreshCatTable.run();

        SmartKhataDialog.<Void>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Manage Categories (পণ্য ক্যাটাগরি)")
                .subtitle("Create, rename, or remove product categories for your store.")
                .icon(DialogComponents.SVG_TAG, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(txtCatName)
                .infoOnly(true)
                .primaryAction("Close", DialogComponents.SVG_CLOSE, SmartKhataDialog.ActionTone.PRIMARY)
                .showAndWait();
    }

    @FXML
    private void onAddProduct() {
        showProductFormDialog(null);
    }

    @FXML
    private void onEditProduct() {
        ProductListItemDto selected = tblProducts.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Optional<Product> fullOpt = inventoryService.getProductById(selected.id());
        fullOpt.ifPresent(this::showProductFormDialog);
    }

    private void showProductFormDialog(Product existing) {
        boolean isEdit = existing != null;
        long businessId = getActiveBusinessId();
        Window ownerWindow = btnAddProduct != null && btnAddProduct.getScene() != null
                ? btnAddProduct.getScene().getWindow()
                : (tblProducts != null && tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null);

        ProductFormModal.Dependencies deps = new ProductFormModal.Dependencies(
                () -> inventoryService.getCategories(businessId),
                (catName, catDesc) -> {
                    Category created = inventoryService.createCategory(businessId, catName, catDesc);
                    Platform.runLater(this::loadCategoriesFilter);
                    return created;
                },
                () -> {
                    List<SupplierListItemDto> activeSups = supplierService.searchSuppliers(businessId, null, false);
                    List<Supplier> suppliers = new ArrayList<>();
                    for (SupplierListItemDto dto : activeSups) {
                        supplierService.getSupplierById(dto.id()).ifPresent(suppliers::add);
                    }
                    return suppliers;
                },
                supplierService::createSupplier,
                productId -> inventoryService.getProductSuppliers(productId, true),
                payload -> {
                    Product targetProduct = isEdit
                            ? inventoryService.updateProduct(payload.updateDto())
                            : inventoryService.createProduct(payload.createDto());
                    for (Long delId : payload.deletedMappingIds()) {
                        inventoryService.removeProductSupplier(delId);
                    }
                    for (ProductFormModal.DraftSupplierLink link : payload.supplierLinks()) {
                        inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                                link.mappingId(),
                                businessId,
                                targetProduct.id(),
                                link.supplierId(),
                                link.tradePrice(),
                                link.supplierProductCode(),
                                link.minOrderQty(),
                                link.purchaseUnit(),
                                link.packSize(),
                                link.isPreferred(),
                                link.leadTimeDays(),
                                link.isActive()
                        ));
                    }
                    return targetProduct;
                }
        );

        Optional<Product> savedOpt = isEdit
                ? ProductFormModal.showEditDialog(ownerWindow, businessId, existing, deps)
                : ProductFormModal.showAddDialog(ownerWindow, businessId, deps);

        savedOpt.ifPresent(saved -> {
            refreshData();
            AlertHelper.showInfo(ownerWindow instanceof Stage s ? s : null,
                    isEdit ? "Product Updated" : "Product Created",
                    "Product '" + saved.name() + "' saved successfully.");
        });
    }

    @FXML
    private void onAdjustStock() {
        ProductListItemDto selected = tblProducts.getSelectionModel().getSelectedItem();
        Window owner = tblProducts != null && tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null;
        if (selected == null) {
            AlertHelper.showInfo(owner, "No Selection", "Please select a product from the list to adjust its stock.");
            return;
        }

        Label lblProductVal = new Label(selected.name());
        lblProductVal.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #18283B;");
        Label lblCurrentBadge = DialogComponents.statusBadge(
                "Current: " + selected.currentStock() + " " + selected.unit(),
                DialogComponents.Tone.NEUTRAL
        );
        Label lblProjectedBadge = DialogComponents.statusBadge(
                "Projected: " + selected.currentStock() + " " + selected.unit(),
                DialogComponents.Tone.SUCCESS
        );
        HBox stockBadges = new HBox(8.0, lblCurrentBadge, lblProjectedBadge);
        stockBadges.setAlignment(Pos.CENTER_LEFT);

        VBox summaryCard = DialogComponents.tintedSectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_CUBE, "Selected Product & Balance",
                        "Auditable manual stock count adjustment."),
                lblProductVal,
                stockBadges
        );

        TextField deltaField = new TextField();
        deltaField.setPromptText("e.g. +10 (inward) or -5 (damage/loss)");
        TextField reasonField = new TextField();
        reasonField.setPromptText("Mandatory reason: Physical audit count / damaged in transit / etc.");

        deltaField.textProperty().addListener((o, a, raw) -> {
            try {
                if (raw == null || raw.trim().isBlank() || "+".equals(raw.trim()) || "-".equals(raw.trim())) {
                    lblProjectedBadge.setText("Projected: " + selected.currentStock() + " " + selected.unit());
                    lblProjectedBadge.getStyleClass().setAll("sk-dlg-badge-neutral");
                    return;
                }
                int d = Integer.parseInt(raw.trim().replace("+", ""));
                int next = selected.currentStock() + d;
                lblProjectedBadge.setText("Projected: " + next + " " + selected.unit());
                lblProjectedBadge.getStyleClass().setAll(
                        next < 0 ? "sk-dlg-badge-danger" : (d == 0 ? "sk-dlg-badge-warning" : "sk-dlg-badge-success")
                );
            } catch (NumberFormatException ex) {
                lblProjectedBadge.setText("Invalid quantity");
                lblProjectedBadge.getStyleClass().setAll("sk-dlg-badge-danger");
            }
        });

        VBox adjustmentCard = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(DialogComponents.SVG_EDIT, "Adjustment Details",
                        "Both quantity delta and audit reason are permanently logged."),
                DialogComponents.formField("Quantity Change (+/-)", true,
                        "Use positive integers for excess count (+5) or negative for shrinkage (-3).",
                        DialogComponents.unitFieldWrap(DialogComponents.SVG_CUBE, deltaField, selected.unit())),
                DialogComponents.formField("Adjustment Reason", true, null,
                        DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, reasonField))
        );

        VBox body = new VBox(14.0, summaryCard, adjustmentCard);

        Optional<StockAdjustmentDto> result = SmartKhataDialog.<StockAdjustmentDto>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.MEDIUM)
                .title("Manual Stock Adjustment (মজুদ সমন্বয়)")
                .subtitle("Record an auditable stock change with mandatory business reason.")
                .icon(DialogComponents.SVG_CUBE, DialogComponents.Tone.SOFT_GREEN)
                .content(body)
                .initialFocus(deltaField)
                .primaryAction("Confirm Adjustment", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String deltaStr = deltaField.getText() != null ? deltaField.getText().trim() : "";
                    String reason = reasonField.getText() != null ? reasonField.getText().trim() : "";
                    int delta;
                    try {
                        delta = Integer.parseInt(deltaStr.replace("+", ""));
                    } catch (NumberFormatException e) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Please enter a valid whole number quantity (e.g. +10 or -5).", deltaField);
                    }
                    if (delta == 0) {
                        throw new SmartKhataDialog.ValidationFocusException("Quantity delta cannot be zero.", deltaField);
                    }
                    if (reason.isBlank()) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "A business reason is mandatory for stock adjustment audits.", reasonField);
                    }
                    long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L;
                    return new StockAdjustmentDto(selected.id(), delta, reason, userId);
                })
                .showAndWait();

        result.ifPresent(dto -> {
            try {
                inventoryService.adjustStock(dto);
                refreshData();
                AlertHelper.showInfo(owner, "Stock Adjusted", "Stock adjustment recorded successfully.");
            } catch (InsufficientStockException ex) {
                AlertHelper.showStartupError(owner, "Insufficient Stock",
                        String.format("Cannot reduce stock by %d. Current available: %d", ex.getRequestedQuantity(), ex.getAvailableStock()), ex);
            } catch (Exception e) {
                log.error("Failed to adjust stock: {}", e.getMessage(), e);
                AlertHelper.showStartupError(owner, "Adjustment Failed", e.getMessage(), e);
            }
        });
    }

    @FXML
    private void onToggleArchive() {
        ProductListItemDto selected = tblProducts.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblProducts != null && tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null;

        if (selected.isArchived()) {
            boolean confirmed = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.PRIMARY,
                    "Restore Product",
                    "Restore product '" + selected.name() + "' to active inventory?",
                    "Restored products immediately become available for POS billing and Purchase Orders.",
                    "Restore Product"
            );
            if (confirmed) {
                try {
                    inventoryService.restoreProduct(selected.id());
                    refreshData();
                    AlertHelper.showInfo(owner, "Product Restored", "Product '" + selected.name() + "' has been restored.");
                } catch (Exception e) {
                    AlertHelper.showStartupError(owner, "Restore Failed", e.getMessage(), e);
                }
            }
        } else {
            boolean confirmed = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.WARNING,
                    "Archive Product",
                    "Are you sure you want to archive product '" + selected.name() + "'?",
                    "Archived products are hidden from POS sales lookup, while all stock movements and financial history remain preserved.",
                    "Archive Product"
            );
            if (confirmed) {
                try {
                    inventoryService.archiveProduct(selected.id());
                    refreshData();
                    AlertHelper.showInfo(owner, "Product Archived", "Product '" + selected.name() + "' has been archived.");
                } catch (Exception e) {
                    AlertHelper.showStartupError(owner, "Archive Failed", e.getMessage(), e);
                }
            }
        }
    }

    @FXML
    private void onDeleteProduct() {
        ProductListItemDto selected = tblProducts.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblProducts != null && tblProducts.getScene() != null ? tblProducts.getScene().getWindow() : null;

        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Product (পণ্য মুছুন)",
                "Permanently delete product '" + selected.name() + "'?",
                "Only products without sales, purchase, GRN, or adjustment history can be permanently deleted. Archive instead if history exists.",
                "Delete Permanently"
        );
        if (confirmed) {
            try {
                inventoryService.deleteProduct(selected.id());
                refreshData();
                AlertHelper.showInfo(owner, "Product Deleted", "Product '" + selected.name() + "' has been deleted.");
            } catch (Exception e) {
                log.warn("Delete product failed: {}", e.getMessage());
                AlertHelper.showWarning(owner, "Cannot Delete Product", e.getMessage());
            }
        }
    }

    @FXML
    private void onEditMovementNote() {
        StockMovement selectedMove = tblMovements.getSelectionModel().getSelectedItem();
        Window owner = tblMovements != null && tblMovements.getScene() != null ? tblMovements.getScene().getWindow() : null;
        if (selectedMove == null) {
            AlertHelper.showInfo(owner, "No Movement Selected", "Select a stock movement row first to edit its note.");
            return;
        }

        AlertHelper.promptInput(
                owner,
                "Edit Stock Movement Note (নোট পরিবর্তন)",
                "Movement: " + formatMovementLabel(selectedMove.movementType())
                        + " (" + (selectedMove.quantity() > 0 ? "+" : "") + selectedMove.quantity() + ")",
                "Notes / Reason",
                selectedMove.notes() != null ? selectedMove.notes() : "",
                "Enter audit note or reason...",
                false,
                "Update Note"
        ).ifPresent(newNote -> {
            try {
                inventoryService.updateStockMovementNotes(selectedMove.id(), newNote);
                ProductListItemDto prod = tblProducts.getSelectionModel().getSelectedItem();
                if (prod != null) {
                    showProductDetails(prod);
                }
            } catch (Exception ex) {
                AlertHelper.showStartupError(owner, "Update Note Failed", ex.getMessage(), ex);
            }
        });
    }

    private Money parseMoney(String input) {
        if (input == null || input.trim().isBlank()) {
            return Money.ZERO;
        }
        try {
            BigDecimal bd = new BigDecimal(input.trim().replaceAll("[, ]", ""));
            return Money.ofBdt(bd);
        } catch (Exception e) {
            return Money.ZERO;
        }
    }

    private int parseInteger(String input, int defaultVal) {
        if (input == null || input.trim().isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(input.trim().replaceAll("[, ]", ""));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private long getActiveBusinessId() {
        if (SessionContext.getCurrentBusiness() != null) {
            return SessionContext.getCurrentBusiness().id();
        }
        return 1L;
    }
}


package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.entity.SupplierLedgerEntry;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteSupplierService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.SupplierFormModal;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Controller for Supplier Directory & Paona Khata (সরবরাহকারী খাতা).
 * Provides responsive search, sortable vendor directory, opening payable balance posting,
 * vendor detail inspector, double-entry payable ledger statement, and archive lifecycle management.
 */
public class SupplierListController {

    private static final Logger log = LoggerFactory.getLogger(SupplierListController.class);
    private static final DateTimeFormatter DATE_SHORT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FULL_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final SupplierService supplierService;
    private final ExecutorService executor;

    private Image archiveIcon;
    private Image restoreIcon;

    // Top Header & Metric Chips
    @FXML private StackPane headerArtSpacer;
    @FXML private Label lblTotalSuppliers;
    @FXML private Label lblTotalPayables;
    @FXML private Button btnAddSupplier;

    // Search & Filter Toolbar
    @FXML private HBox searchContainer;
    @FXML private TextField txtSearch;
    @FXML private Button btnClearSearch;
    @FXML private CheckBox chkIncludeArchived;
    @FXML private Button btnRefresh;

    // Main Supplier Table
    @FXML private TableView<SupplierListItemDto> tblSuppliers;
    @FXML private TableColumn<SupplierListItemDto, String> colName;
    @FXML private TableColumn<SupplierListItemDto, String> colContactPerson;
    @FXML private TableColumn<SupplierListItemDto, String> colPhone;
    @FXML private TableColumn<SupplierListItemDto, String> colAddress;
    @FXML private TableColumn<SupplierListItemDto, String> colCurrentPayable;
    @FXML private TableColumn<SupplierListItemDto, String> colStatus;

    // Right Side Detail Panel
    @FXML private VBox pnlDetailContainer;
    @FXML private VBox vboxNoSelection;
    @FXML private VBox vboxSupplierDetails;
    @FXML private Label lblDetailName;
    @FXML private Label lblDetailContact;
    @FXML private Label lblDetailMeta;
    @FXML private Button btnEditSupplier;
    @FXML private Button btnArchiveSupplier;
    @FXML private Button btnDeleteSupplier;
    @FXML private ImageView imgArchiveAction;
    @FXML private Label lblDetailPayable;
    @FXML private Label lblDetailOpening;
    @FXML private Label lblDetailNotes;

    // Supplier Ledger Table
    @FXML private TableView<SupplierLedgerEntry> tblLedger;
    @FXML private TableColumn<SupplierLedgerEntry, String> colLedgerDate;
    @FXML private TableColumn<SupplierLedgerEntry, String> colLedgerType;
    @FXML private TableColumn<SupplierLedgerEntry, String> colLedgerCredit;
    @FXML private TableColumn<SupplierLedgerEntry, String> colLedgerDebit;
    @FXML private TableColumn<SupplierLedgerEntry, String> colLedgerBalance;

    // Linked Supplied Products Table
    @FXML private TableView<ProductSupplierViewDto> tblSuppliedProducts;
    @FXML private TableColumn<ProductSupplierViewDto, String> colSupProdName;
    @FXML private TableColumn<ProductSupplierViewDto, String> colSupProdTp;
    @FXML private TableColumn<ProductSupplierViewDto, String> colSupProdUnit;
    @FXML private TableColumn<ProductSupplierViewDto, String> colSupProdLast;
    @FXML private TableColumn<ProductSupplierViewDto, String> colSupProdPref;

    private final ObservableList<SupplierListItemDto> supplierList = FXCollections.observableArrayList();
    private final ObservableList<SupplierLedgerEntry> ledgerList = FXCollections.observableArrayList();
    private final ObservableList<ProductSupplierViewDto> suppliedProductsList = FXCollections.observableArrayList();

    private volatile CompletableFuture<Void> pendingListFuture = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> pendingDetailFuture = CompletableFuture.completedFuture(null);

    public SupplierListController() {
        this(new SqliteSupplierService());
    }

    public SupplierListController(SupplierService supplierService) {
        this.supplierService = supplierService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "supplier-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @FXML
    public void initialize() {
        log.info("Initializing SupplierListController...");

        archiveIcon = loadImage("/com/smartkhata/ui/images/supplier/archive.png");
        restoreIcon = loadImage("/com/smartkhata/ui/images/supplier/check-circle.png");

        setupHeaderResponsiveness();
        setupSearchBar();
        setupSupplierTable();
        setupLedgerTable();
        setupSuppliedProductsTable();

        // Selection Listener for Detail Panel & Ledger Loading
        tblSuppliers.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                showSupplierDetails(newVal);
            } else {
                showNoSelection();
            }
        });

        refreshData();
    }

    private Image loadImage(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            return is != null ? new Image(is) : null;
        } catch (Exception e) {
            log.debug("Optional supplier icon unavailable [{}]: {}", resourcePath, e.getMessage());
            return null;
        }
    }

    private void setupHeaderResponsiveness() {
        if (headerArtSpacer != null) {
            headerArtSpacer.widthProperty().addListener((obs, oldW, newW) -> {
                boolean hasRoom = newW.doubleValue() >= 86.0;
                headerArtSpacer.setVisible(hasRoom);
            });
        }
    }

    private void setupSearchBar() {
        if (txtSearch == null) return;

        if (searchContainer != null) {
            txtSearch.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                var styles = searchContainer.getStyleClass();
                if (Boolean.TRUE.equals(isFocused)) {
                    if (!styles.contains("search-field-focused")) {
                        styles.add("search-field-focused");
                    }
                } else {
                    styles.remove("search-field-focused");
                }
            });
        }

        if (btnClearSearch != null) {
            txtSearch.textProperty().addListener((obs, oldText, newText) -> {
                boolean hasQuery = newText != null && !newText.isBlank();
                btnClearSearch.setVisible(hasQuery);
                btnClearSearch.setManaged(hasQuery);
            });
        }
    }

    private void setupSupplierTable() {
        tblSuppliers.setItems(supplierList);

        colName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
        colName.setCellFactory(col -> new TableCell<>() {
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

        colContactPerson.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().contactPerson() != null && !cell.getValue().contactPerson().isBlank() ? cell.getValue().contactPerson() : "—"
        ));
        colPhone.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().phone() != null && !cell.getValue().phone().isBlank() ? cell.getValue().phone() : "—"
        ));
        colAddress.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().address() != null && !cell.getValue().address().isBlank() ? cell.getValue().address() : "—"
        ));

        colCurrentPayable.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().currentPayable())));
        colCurrentPayable.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    SupplierListItemDto row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && row.currentPayable().isPositive()) {
                        setStyle("-fx-font-weight: 800; -fx-text-fill: #EA580C;");
                    } else {
                        setStyle("-fx-font-weight: 600; -fx-text-fill: #15803D;");
                    }
                }
            }
        });

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

    private void setupLedgerTable() {
        tblLedger.setItems(ledgerList);

        colLedgerDate.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().entryDate() != null ? cell.getValue().entryDate().format(DATE_SHORT_FMT) : "—"
        ));
        colLedgerDate.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    SupplierLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
                    if (entry != null && entry.entryDate() != null) {
                        setTooltip(new Tooltip(entry.entryDate().format(DATE_FULL_FMT)));
                    } else {
                        setTooltip(null);
                    }
                }
            }
        });

        colLedgerType.setCellValueFactory(cell -> {
            String type = cell.getValue().entryType();
            String label = switch (type) {
                case "OPENING_BALANCE" -> "Opening Payable";
                case "PURCHASE_BILL" -> "Purchase Bill";
                case "PAYMENT_DISBURSEMENT" -> "Payment Paid";
                case "BILL_REVERSAL" -> "Bill Reversal";
                case "ADJUSTMENT" -> "Adjustment";
                default -> type;
            };
            return new SimpleStringProperty(label);
        });
        colLedgerType.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    SupplierLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
                    String tip = switch (entry != null ? entry.entryType() : "") {
                        case "OPENING_BALANCE" -> "Opening Payable (প্রারম্ভিক)";
                        case "PURCHASE_BILL" -> "Purchase Bill (ক্রয় চালান)";
                        case "PAYMENT_DISBURSEMENT" -> "Payment Voucher (পরিশোধ)";
                        case "BILL_REVERSAL" -> "Bill Reversal";
                        case "ADJUSTMENT" -> "Payable Adjustment";
                        default -> item;
                    };
                    if (entry != null && entry.notes() != null && !entry.notes().isBlank()) {
                        tip += "\n" + entry.notes();
                    }
                    setTooltip(new Tooltip(tip));
                }
            }
        });

        // Payables: credit increases payable, debit decreases payable
        colLedgerCredit.setCellValueFactory(cell -> {
            Money credit = cell.getValue().credit();
            return new SimpleStringProperty(credit.isPositive() ? BengaliMoneyFormatter.format(credit) : "—");
        });
        colLedgerCredit.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    SupplierLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
                    if (entry != null && entry.credit().isPositive()) {
                        setStyle("-fx-font-weight: 700; -fx-text-fill: #EA580C;");
                    } else {
                        setStyle("-fx-text-fill: #64748B;");
                    }
                }
            }
        });

        colLedgerDebit.setCellValueFactory(cell -> {
            Money debit = cell.getValue().debit();
            return new SimpleStringProperty(debit.isPositive() ? BengaliMoneyFormatter.format(debit) : "—");
        });
        colLedgerDebit.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    SupplierLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
                    if (entry != null && entry.debit().isPositive()) {
                        setStyle("-fx-font-weight: 700; -fx-text-fill: #15803D;");
                    } else {
                        setStyle("-fx-text-fill: #64748B;");
                    }
                }
            }
        });

        colLedgerBalance.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().runningBalance())));
        colLedgerBalance.setCellFactory(col -> new TableCell<>() {
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

    private void setupSuppliedProductsTable() {
        if (tblSuppliedProducts == null) return;
        tblSuppliedProducts.setItems(suppliedProductsList);
        if (colSupProdName != null) {
            colSupProdName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().productName()));
        }
        if (colSupProdTp != null) {
            colSupProdTp.setCellValueFactory(c -> new SimpleStringProperty(
                    BengaliMoneyFormatter.format(c.getValue().tradePrice())));
        }
        if (colSupProdUnit != null) {
            colSupProdUnit.setCellValueFactory(c -> {
                ProductSupplierViewDto dto = c.getValue();
                String txt = dto.packSize() > 1
                        ? dto.purchaseUnit() + " (" + dto.packSize() + " " + dto.baseUnit() + ")"
                        : dto.purchaseUnit();
                return new SimpleStringProperty(txt);
            });
        }
        if (colSupProdLast != null) {
            colSupProdLast.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().lastPurchasePrice() != null && c.getValue().lastPurchasePrice().isPositive()
                            ? BengaliMoneyFormatter.format(c.getValue().lastPurchasePrice())
                            : "—"));
        }
        if (colSupProdPref != null) {
            colSupProdPref.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().isPreferred() ? "Preferred" : (c.getValue().isActive() ? "Active" : "Inactive")));
        }
    }

    public void refreshData() {
        if (executor.isShutdown()) {
            return;
        }
        long businessId = getActiveBusinessId();
        String query = txtSearch != null ? txtSearch.getText() : null;
        boolean includeArchived = chkIncludeArchived != null && chkIncludeArchived.isSelected();

        pendingListFuture = CompletableFuture.supplyAsync(() -> {
            List<SupplierListItemDto> list = supplierService.searchSuppliers(businessId, query, includeArchived);
            Money totalPayables = supplierService.getTotalOutstandingPayables(businessId);
            return new Object[]{list, totalPayables};
        }, executor).thenAccept(results -> {
            @SuppressWarnings("unchecked")
            List<SupplierListItemDto> list = (List<SupplierListItemDto>) results[0];
            Money totalPayables = (Money) results[1];

            Platform.runLater(() -> {
                supplierList.setAll(list);
                if (lblTotalSuppliers != null) {
                    lblTotalSuppliers.setText(String.valueOf(list.size()));
                }
                if (lblTotalPayables != null) {
                    lblTotalPayables.setText(BengaliMoneyFormatter.format(totalPayables));
                }

                // Reselect previously selected item (and force detail panel refresh) or select first
                SupplierListItemDto current = tblSuppliers.getSelectionModel().getSelectedItem();
                if (current != null) {
                    Optional<SupplierListItemDto> match = list.stream()
                            .filter(s -> s.id() == current.id())
                            .findFirst();
                    if (match.isPresent()) {
                        tblSuppliers.getSelectionModel().select(match.get());
                        showSupplierDetails(match.get());
                    } else if (!list.isEmpty()) {
                        tblSuppliers.getSelectionModel().selectFirst();
                    } else {
                        showNoSelection();
                    }
                } else if (!list.isEmpty()) {
                    tblSuppliers.getSelectionModel().selectFirst();
                } else {
                    showNoSelection();
                }
            });
        }).exceptionally(ex -> {
            log.error("Failed to load suppliers asynchronously: {}", ex.getMessage(), ex);
            return null;
        });
    }

    private void showSupplierDetails(SupplierListItemDto item) {
        vboxNoSelection.setVisible(false);
        vboxNoSelection.setManaged(false);
        vboxSupplierDetails.setVisible(true);
        vboxSupplierDetails.setManaged(true);

        lblDetailName.setText(item.name());
        lblDetailContact.setText(String.format("Contact: %s | Phone: %s | Address: %s",
                item.contactPerson() != null && !item.contactPerson().isBlank() ? item.contactPerson() : "—",
                item.phone() != null && !item.phone().isBlank() ? item.phone() : "—",
                item.address() != null && !item.address().isBlank() ? item.address() : "—"));
        if (lblDetailMeta != null) {
            String codeTxt = item.supplierCode() != null && !item.supplierCode().isBlank() ? item.supplierCode() : "—";
            String emailTxt = item.email() != null && !item.email().isBlank() ? item.email() : "—";
            String termsTxt = item.paymentTerms() != null && !item.paymentTerms().isBlank() ? item.paymentTerms() : "Standard";
            lblDetailMeta.setText(String.format("Code: %s | Email: %s | Terms: %s (%d days) | %s",
                    codeTxt, emailTxt, termsTxt, item.creditPeriodDays(),
                    item.isActive() ? "Active" : "Inactive"));
        }

        lblDetailPayable.setText(BengaliMoneyFormatter.format(item.currentPayable()));
        if (item.currentPayable().isPositive()) {
            lblDetailPayable.setStyle("-fx-font-size: 16.5px; -fx-font-weight: 800; -fx-text-fill: #EA580C;");
        } else {
            lblDetailPayable.setStyle("-fx-font-size: 16.5px; -fx-font-weight: 800; -fx-text-fill: #15803D;");
        }

        updateArchiveButtonState(item.isArchived());

        if (executor.isShutdown()) {
            return;
        }

        // Load full supplier details, ledger and supplied products asynchronously
        pendingDetailFuture = CompletableFuture.runAsync(() -> {
            Optional<Supplier> fullSupplier = supplierService.getSupplierById(item.id());
            List<SupplierLedgerEntry> entries = supplierService.getSupplierLedger(item.id());
            List<ProductSupplierViewDto> products = supplierService.getSupplierProducts(item.id());

            Platform.runLater(() -> {
                if (fullSupplier.isPresent()) {
                    Supplier s = fullSupplier.get();
                    lblDetailOpening.setText(BengaliMoneyFormatter.format(s.openingBalance()));
                    if (s.notes() != null && !s.notes().isBlank()) {
                        lblDetailNotes.setText("Notes: " + s.notes());
                        lblDetailNotes.setVisible(true);
                        lblDetailNotes.setManaged(true);
                    } else {
                        lblDetailNotes.setVisible(false);
                        lblDetailNotes.setManaged(false);
                    }
                }
                ledgerList.setAll(entries);
                suppliedProductsList.setAll(products);
            });
        }, executor).exceptionally(ex -> {
            log.error("Failed to load supplier ledger details: {}", ex.getMessage(), ex);
            return null;
        });
    }

    private void updateArchiveButtonState(boolean isArchived) {
        if (btnArchiveSupplier == null) return;

        btnArchiveSupplier.getStyleClass().removeAll("btn-archive-soft", "btn-restore-soft");
        if (isArchived) {
            btnArchiveSupplier.setText("Restore");
            btnArchiveSupplier.getStyleClass().add("btn-restore-soft");
            if (imgArchiveAction != null && restoreIcon != null) {
                imgArchiveAction.setImage(restoreIcon);
            }
        } else {
            btnArchiveSupplier.setText("Archive");
            btnArchiveSupplier.getStyleClass().add("btn-archive-soft");
            if (imgArchiveAction != null && archiveIcon != null) {
                imgArchiveAction.setImage(archiveIcon);
            }
        }

        User currentUser = SessionContext.getCurrentUser();
        boolean ownerAllowed = currentUser == null || currentUser.role().isOwner();
        btnArchiveSupplier.setDisable(!ownerAllowed);
        btnArchiveSupplier.setTooltip(ownerAllowed
                ? null
                : new Tooltip("Owner permission required to archive or restore suppliers."));
        if (btnDeleteSupplier != null) {
            btnDeleteSupplier.setDisable(!ownerAllowed);
            btnDeleteSupplier.setTooltip(ownerAllowed
                    ? new Tooltip("Delete supplier if no purchase bills or payments exist")
                    : new Tooltip("Owner permission required to delete suppliers."));
        }
    }

    private void showNoSelection() {
        vboxSupplierDetails.setVisible(false);
        vboxSupplierDetails.setManaged(false);
        vboxNoSelection.setVisible(true);
        vboxNoSelection.setManaged(true);
        ledgerList.clear();
        suppliedProductsList.clear();
    }

    @FXML
    private void onSearchKeyReleased() {
        refreshData();
    }

    @FXML
    private void onClearSearch() {
        if (txtSearch != null) {
            txtSearch.clear();
            txtSearch.requestFocus();
        }
        refreshData();
    }

    @FXML
    private void onToggleIncludeArchived() {
        refreshData();
    }

    @FXML
    private void onRefresh() {
        refreshData();
    }

    @FXML
    private void onAddSupplier() {
        Optional<Supplier> savedOpt = SupplierFormModal.showAddDialog(
                getOwnerWindow(),
                getActiveBusinessId(),
                supplierService::createSupplier
        );
        savedOpt.ifPresent(saved -> {
            refreshData();
            AlertHelper.showInfo(null, "Supplier Created", "Supplier '" + saved.name() + "' has been saved successfully.");
        });
    }

    @FXML
    private void onEditSupplier() {
        SupplierListItemDto selected = tblSuppliers.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Supplier> fullSupplierOpt = supplierService.getSupplierById(selected.id());
        if (fullSupplierOpt.isEmpty()) return;
        Supplier supplier = fullSupplierOpt.get();

        Optional<Supplier> updatedOpt = SupplierFormModal.showEditDialog(
                getOwnerWindow(),
                supplier,
                supplierService::updateSupplier
        );
        updatedOpt.ifPresent(updated -> {
            refreshData();
            AlertHelper.showInfo(null, "Supplier Updated", "Supplier details updated successfully.");
        });
    }

    @FXML
    private void onToggleArchive() {
        SupplierListItemDto selected = tblSuppliers.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = getOwnerWindow();

        if (selected.isArchived()) {
            boolean confirmed = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.PRIMARY,
                    "Restore Supplier",
                    "Are you sure you want to restore supplier '" + selected.name() + "' to active directory?",
                    "Restored suppliers become selectable again in Purchase Orders, GRNs, and Product mapping.",
                    "Restore Supplier"
            );
            if (confirmed) {
                try {
                    supplierService.restoreSupplier(selected.id());
                    refreshData();
                    AlertHelper.showInfo(owner, "Supplier Restored", "Supplier '" + selected.name() + "' has been restored.");
                } catch (Exception e) {
                    AlertHelper.showStartupError(owner, "Restore Failed", e.getMessage(), e);
                }
            }
        } else {
            boolean confirmed = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.WARNING,
                    "Archive Supplier",
                    "Are you sure you want to archive supplier '" + selected.name() + "'?",
                    "Archived suppliers are hidden from the primary directory, while historical bills and ledgers remain intact.",
                    "Archive Supplier"
            );
            if (confirmed) {
                try {
                    supplierService.archiveSupplier(selected.id());
                    refreshData();
                    AlertHelper.showInfo(owner, "Supplier Archived", "Supplier '" + selected.name() + "' has been archived.");
                } catch (Exception e) {
                    AlertHelper.showStartupError(owner, "Archive Failed", e.getMessage(), e);
                }
            }
        }
    }

    @FXML
    private void onDeleteSupplier() {
        SupplierListItemDto selected = tblSuppliers.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = getOwnerWindow();

        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Supplier (সরবরাহকারী মুছুন)",
                "Delete supplier '" + selected.name() + "' permanently?",
                "Only suppliers without linked purchase bills, GRNs, or payment vouchers can be deleted. Archive instead if history exists.",
                "Delete Permanently"
        );
        if (confirmed) {
            try {
                supplierService.deleteSupplier(selected.id());
                refreshData();
                AlertHelper.showInfo(owner, "Supplier Deleted", "Supplier '" + selected.name() + "' has been deleted.");
            } catch (IllegalStateException | SecurityException ex) {
                AlertHelper.showWarning(owner, "Cannot Delete Supplier", ex.getMessage());
            } catch (Exception e) {
                log.error("Failed to delete supplier: {}", e.getMessage(), e);
                AlertHelper.showStartupError(owner, "Delete Failed", e.getMessage(), e);
            }
        }
    }

    public void shutdown() {
        try {
            pendingListFuture.cancel(true);
            pendingDetailFuture.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Supplier controller shutdown note: {}", e.getMessage());
        }
    }

    private Window getOwnerWindow() {
        if (btnAddSupplier != null && btnAddSupplier.getScene() != null) {
            return btnAddSupplier.getScene().getWindow();
        }
        if (tblSuppliers != null && tblSuppliers.getScene() != null) {
            return tblSuppliers.getScene().getWindow();
        }
        return null;
    }

    private long getActiveBusinessId() {
        if (SessionContext.getCurrentBusiness() != null) {
            return SessionContext.getCurrentBusiness().id();
        }
        return 1L;
    }
}

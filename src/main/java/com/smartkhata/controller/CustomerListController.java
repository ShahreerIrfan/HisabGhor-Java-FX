package com.smartkhata.controller;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.CustomerLedgerEntry;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteCustomerService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.CustomerFormModal;
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
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Controller for Customer Directory & Baki Khata (গ্রাহক বাকি খাতা).
 * Provides responsive search, sortable customer directory, opening balance posting,
 * customer detail inspector, ledger statement display, and archive lifecycle management.
 */
public class CustomerListController {

    private static final Logger log = LoggerFactory.getLogger(CustomerListController.class);
    private static final DateTimeFormatter DATE_SHORT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FULL_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CustomerService customerService;
    private final ExecutorService executor;

    private Image archiveIcon;
    private Image restoreIcon;

    // Top Header & Metric Chips
    @FXML private StackPane headerArtSpacer;
    @FXML private Label lblTotalCustomers;
    @FXML private Label lblTotalDues;
    @FXML private Button btnAddCustomer;

    // Search & Filter Toolbar
    @FXML private HBox searchContainer;
    @FXML private TextField txtSearch;
    @FXML private Button btnClearSearch;
    @FXML private CheckBox chkIncludeArchived;
    @FXML private Button btnRefresh;

    // Main Customer Table
    @FXML private TableView<CustomerListItemDto> tblCustomers;
    @FXML private TableColumn<CustomerListItemDto, String> colName;
    @FXML private TableColumn<CustomerListItemDto, String> colPhone;
    @FXML private TableColumn<CustomerListItemDto, String> colAddress;
    @FXML private TableColumn<CustomerListItemDto, String> colCreditLimit;
    @FXML private TableColumn<CustomerListItemDto, String> colCurrentDue;
    @FXML private TableColumn<CustomerListItemDto, String> colStatus;

    // Right Side Detail Panel
    @FXML private VBox pnlDetailContainer;
    @FXML private VBox vboxNoSelection;
    @FXML private VBox vboxCustomerDetails;
    @FXML private Label lblDetailName;
    @FXML private Label lblDetailContact;
    @FXML private Button btnEditCustomer;
    @FXML private Button btnArchiveCustomer;
    @FXML private Button btnDeleteCustomer;
    @FXML private ImageView imgArchiveAction;
    @FXML private Label lblDetailDue;
    @FXML private Label lblDetailLimit;
    @FXML private Label lblDetailAvailable;
    @FXML private Label lblDetailNotes;

    // Customer Ledger Table
    @FXML private TableView<CustomerLedgerEntry> tblLedger;
    @FXML private TableColumn<CustomerLedgerEntry, String> colLedgerDate;
    @FXML private TableColumn<CustomerLedgerEntry, String> colLedgerType;
    @FXML private TableColumn<CustomerLedgerEntry, String> colLedgerDebit;
    @FXML private TableColumn<CustomerLedgerEntry, String> colLedgerCredit;
    @FXML private TableColumn<CustomerLedgerEntry, String> colLedgerBalance;

    private final ObservableList<CustomerListItemDto> customerList = FXCollections.observableArrayList();
    private final ObservableList<CustomerLedgerEntry> ledgerList = FXCollections.observableArrayList();

    private volatile CompletableFuture<Void> pendingListFuture = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> pendingDetailFuture = CompletableFuture.completedFuture(null);

    public CustomerListController() {
        this(new SqliteCustomerService());
    }

    public CustomerListController(CustomerService customerService) {
        this.customerService = customerService;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "customer-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @FXML
    public void initialize() {
        log.info("Initializing CustomerListController...");

        archiveIcon = loadImage("/com/smartkhata/ui/images/customer/archive.png");
        restoreIcon = loadImage("/com/smartkhata/ui/images/customer/check-circle.png");

        setupHeaderResponsiveness();
        setupSearchBar();
        setupCustomerTable();
        setupLedgerTable();

        // Selection Listener for Detail Panel & Ledger Loading
        tblCustomers.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                showCustomerDetails(newVal);
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
            log.debug("Optional customer icon unavailable [{}]: {}", resourcePath, e.getMessage());
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

    private void setupCustomerTable() {
        tblCustomers.setItems(customerList);

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

        colPhone.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().phone() != null && !cell.getValue().phone().isBlank() ? cell.getValue().phone() : "—"
        ));
        colAddress.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().address() != null && !cell.getValue().address().isBlank() ? cell.getValue().address() : "—"
        ));

        colCreditLimit.setCellValueFactory(cell -> {
            Money limit = cell.getValue().creditLimit();
            return new SimpleStringProperty(limit.isPositive() ? BengaliMoneyFormatter.format(limit) : "No limit");
        });

        colCurrentDue.setCellValueFactory(cell -> new SimpleStringProperty(BengaliMoneyFormatter.format(cell.getValue().currentDue())));
        colCurrentDue.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    CustomerListItemDto row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && row.currentDue().isPositive()) {
                        setStyle("-fx-font-weight: 800; -fx-text-fill: #DC2626;");
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
                    CustomerLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
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
                case "OPENING_BALANCE" -> "Opening Balance (প্রারম্ভিক)";
                case "SALE_INVOICE" -> "Sale Invoice (বিক্রয়)";
                case "PAYMENT_COLLECTION" -> "Payment Received (জমা)";
                case "INVOICE_REVERSAL" -> "Invoice Reversal";
                case "ADJUSTMENT" -> "Balance Adjustment";
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
                    CustomerLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
                    String tip = item;
                    if (entry != null && entry.notes() != null && !entry.notes().isBlank()) {
                        tip += "\n" + entry.notes();
                    }
                    setTooltip(new Tooltip(tip));
                }
            }
        });

        colLedgerDebit.setCellValueFactory(cell -> {
            Money debit = cell.getValue().debit();
            return new SimpleStringProperty(debit.isPositive() ? BengaliMoneyFormatter.format(debit) : "—");
        });

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
                    CustomerLedgerEntry entry = getTableRow() != null ? getTableRow().getItem() : null;
                    if (entry != null && entry.credit().isPositive()) {
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

    public void refreshData() {
        if (executor.isShutdown()) {
            return;
        }
        long businessId = getActiveBusinessId();
        String query = txtSearch != null ? txtSearch.getText() : null;
        boolean includeArchived = chkIncludeArchived != null && chkIncludeArchived.isSelected();

        pendingListFuture = CompletableFuture.supplyAsync(() -> {
            List<CustomerListItemDto> list = customerService.searchCustomers(businessId, query, includeArchived);
            Money totalDues = customerService.getTotalOutstandingDues(businessId);
            return new Object[]{list, totalDues};
        }, executor).thenAccept(results -> {
            @SuppressWarnings("unchecked")
            List<CustomerListItemDto> list = (List<CustomerListItemDto>) results[0];
            Money totalDues = (Money) results[1];

            Platform.runLater(() -> {
                customerList.setAll(list);
                if (lblTotalCustomers != null) {
                    lblTotalCustomers.setText(String.valueOf(list.size()));
                }
                if (lblTotalDues != null) {
                    lblTotalDues.setText(BengaliMoneyFormatter.format(totalDues));
                }

                // If previously selected item still exists, refresh/reselect it; otherwise select first
                CustomerListItemDto current = tblCustomers.getSelectionModel().getSelectedItem();
                if (current != null) {
                    Optional<CustomerListItemDto> match = list.stream()
                            .filter(c -> c.id() == current.id())
                            .findFirst();
                    if (match.isPresent()) {
                        tblCustomers.getSelectionModel().select(match.get());
                        showCustomerDetails(match.get());
                    } else if (!list.isEmpty()) {
                        tblCustomers.getSelectionModel().selectFirst();
                    } else {
                        showNoSelection();
                    }
                } else if (!list.isEmpty()) {
                    tblCustomers.getSelectionModel().selectFirst();
                } else {
                    showNoSelection();
                }
            });
        }).exceptionally(ex -> {
            log.error("Failed to load customers asynchronously: {}", ex.getMessage(), ex);
            return null;
        });
    }

    private void showCustomerDetails(CustomerListItemDto item) {
        vboxNoSelection.setVisible(false);
        vboxNoSelection.setManaged(false);
        vboxCustomerDetails.setVisible(true);
        vboxCustomerDetails.setManaged(true);

        lblDetailName.setText(item.name());
        lblDetailContact.setText(String.format("Phone: %s | Address: %s",
                item.phone() != null && !item.phone().isBlank() ? item.phone() : "—",
                item.address() != null && !item.address().isBlank() ? item.address() : "—"));

        lblDetailDue.setText(BengaliMoneyFormatter.format(item.currentDue()));
        if (item.currentDue().isPositive()) {
            lblDetailDue.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #DC2626;");
        } else {
            lblDetailDue.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #15803D;");
        }

        lblDetailLimit.setText(item.creditLimit().isPositive() ? BengaliMoneyFormatter.format(item.creditLimit()) : "No limit");

        if (item.creditLimit().isPositive()) {
            Money available = item.creditLimit().subtract(item.currentDue());
            lblDetailAvailable.setText(BengaliMoneyFormatter.format(available));
            if (available.isNegative()) {
                lblDetailAvailable.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #DC2626;");
            } else {
                lblDetailAvailable.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #15803D;");
            }
        } else {
            lblDetailAvailable.setText("Unlimited");
            lblDetailAvailable.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #15803D;");
        }

        updateArchiveButtonState(item.isArchived());

        if (executor.isShutdown()) {
            return;
        }

        // Load customer notes and customer ledger asynchronously
        pendingDetailFuture = CompletableFuture.runAsync(() -> {
            Optional<Customer> fullCustomer = customerService.getCustomerById(item.id());
            List<CustomerLedgerEntry> entries = customerService.getCustomerLedger(item.id());

            Platform.runLater(() -> {
                if (fullCustomer.isPresent() && fullCustomer.get().notes() != null && !fullCustomer.get().notes().isBlank()) {
                    lblDetailNotes.setText("Notes: " + fullCustomer.get().notes());
                    lblDetailNotes.setVisible(true);
                    lblDetailNotes.setManaged(true);
                } else {
                    lblDetailNotes.setVisible(false);
                    lblDetailNotes.setManaged(false);
                }
                ledgerList.setAll(entries);
            });
        }, executor).exceptionally(ex -> {
            log.error("Failed to load customer ledger details: {}", ex.getMessage(), ex);
            return null;
        });
    }

    private void updateArchiveButtonState(boolean isArchived) {
        if (btnArchiveCustomer == null) return;

        btnArchiveCustomer.getStyleClass().removeAll("btn-archive-soft", "btn-restore-soft");
        if (isArchived) {
            btnArchiveCustomer.setText("Restore");
            btnArchiveCustomer.getStyleClass().add("btn-restore-soft");
            if (imgArchiveAction != null && restoreIcon != null) {
                imgArchiveAction.setImage(restoreIcon);
            }
        } else {
            btnArchiveCustomer.setText("Archive");
            btnArchiveCustomer.getStyleClass().add("btn-archive-soft");
            if (imgArchiveAction != null && archiveIcon != null) {
                imgArchiveAction.setImage(archiveIcon);
            }
        }

        User currentUser = SessionContext.getCurrentUser();
        boolean ownerAllowed = currentUser == null || currentUser.role().isOwner();
        btnArchiveCustomer.setDisable(!ownerAllowed);
        btnArchiveCustomer.setTooltip(ownerAllowed
                ? null
                : new Tooltip("Owner permission required to archive or restore customers."));
        if (btnDeleteCustomer != null) {
            btnDeleteCustomer.setDisable(!ownerAllowed);
            btnDeleteCustomer.setTooltip(ownerAllowed
                    ? new Tooltip("Delete customer if no sales or payments exist")
                    : new Tooltip("Owner permission required to delete customers."));
        }
    }

    private void showNoSelection() {
        vboxCustomerDetails.setVisible(false);
        vboxCustomerDetails.setManaged(false);
        vboxNoSelection.setVisible(true);
        vboxNoSelection.setManaged(true);
        ledgerList.clear();
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
    private void onAddCustomer() {
        Window owner = tblCustomers != null && tblCustomers.getScene() != null
                ? tblCustomers.getScene().getWindow() : null;
        Optional<Customer> savedOpt = CustomerFormModal.showAddDialog(
                owner,
                getActiveBusinessId(),
                customerService::createCustomer
        );
        savedOpt.ifPresent(saved -> {
            refreshData();
            AlertHelper.showInfo(owner, "Customer Created",
                    "Customer '" + saved.name() + "' has been saved successfully.");
        });
    }

    @FXML
    private void onEditCustomer() {
        CustomerListItemDto selected = tblCustomers.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<Customer> fullCustomerOpt = customerService.getCustomerById(selected.id());
        if (fullCustomerOpt.isEmpty()) return;
        Window owner = tblCustomers != null && tblCustomers.getScene() != null
                ? tblCustomers.getScene().getWindow() : null;

        Optional<Customer> updatedOpt = CustomerFormModal.showEditDialog(
                owner,
                fullCustomerOpt.get(),
                customerService::updateCustomer
        );
        updatedOpt.ifPresent(updated -> {
            refreshData();
            AlertHelper.showInfo(owner, "Customer Updated",
                    "Customer details updated successfully for '" + updated.name() + "'.");
        });
    }

    @FXML
    private void onToggleArchive() {
        CustomerListItemDto selected = tblCustomers.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblCustomers != null && tblCustomers.getScene() != null
                ? tblCustomers.getScene().getWindow() : null;

        if (selected.isArchived()) {
            boolean confirmed = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.PRIMARY,
                    "Restore Customer",
                    "Are you sure you want to restore customer '" + selected.name() + "' to active directory?",
                    "Restored customers reappear in POS customer selection and ledger search.",
                    "Restore Customer"
            );
            if (confirmed) {
                try {
                    customerService.restoreCustomer(selected.id());
                    refreshData();
                    AlertHelper.showInfo(owner, "Customer Restored", "Customer '" + selected.name() + "' has been restored.");
                } catch (Exception e) {
                    AlertHelper.showStartupError(owner, "Restore Failed", e.getMessage(), e);
                }
            }
        } else {
            boolean confirmed = AlertHelper.confirm(
                    owner,
                    SmartKhataDialog.ActionTone.WARNING,
                    "Archive Customer",
                    "Are you sure you want to archive customer '" + selected.name() + "'?",
                    "Archived customers are hidden from the primary directory, while all ledger transactions remain preserved.",
                    "Archive Customer"
            );
            if (confirmed) {
                try {
                    customerService.archiveCustomer(selected.id());
                    refreshData();
                    AlertHelper.showInfo(owner, "Customer Archived", "Customer '" + selected.name() + "' has been archived.");
                } catch (Exception e) {
                    AlertHelper.showStartupError(owner, "Archive Failed", e.getMessage(), e);
                }
            }
        }
    }

    @FXML
    private void onDeleteCustomer() {
        CustomerListItemDto selected = tblCustomers.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Window owner = tblCustomers != null && tblCustomers.getScene() != null
                ? tblCustomers.getScene().getWindow() : null;

        boolean confirmed = AlertHelper.confirm(
                owner,
                SmartKhataDialog.ActionTone.DANGER,
                "Delete Customer (গ্রাহক মুছুন)",
                "Delete customer '" + selected.name() + "' permanently?",
                "Only customers without linked sales or payment vouchers can be deleted. Otherwise, archive instead.",
                "Delete Permanently"
        );
        if (confirmed) {
            try {
                customerService.deleteCustomer(selected.id());
                refreshData();
                AlertHelper.showInfo(owner, "Customer Deleted", "Customer '" + selected.name() + "' has been deleted.");
            } catch (IllegalStateException | SecurityException ex) {
                AlertHelper.showWarning(owner, "Cannot Delete Customer", ex.getMessage());
            } catch (Exception e) {
                log.error("Failed to delete customer: {}", e.getMessage(), e);
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
            log.debug("Customer controller shutdown note: {}", e.getMessage());
        }
    }

    private Money parseNonNegativeMoney(String input, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            return Money.ZERO;
        }
        String normalized = input.trim().replaceAll("[, ]", "");
        BigDecimal bd;
        try {
            bd = new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldLabel + " must be a valid amount in BDT (e.g. 50000).");
        }
        if (bd.signum() < 0) {
            throw new IllegalArgumentException(fieldLabel + " cannot be negative.");
        }
        return Money.ofBdt(bd);
    }

    private long getActiveBusinessId() {
        if (SessionContext.getCurrentBusiness() != null) {
            return SessionContext.getCurrentBusiness().id();
        }
        return 1L;
    }
}

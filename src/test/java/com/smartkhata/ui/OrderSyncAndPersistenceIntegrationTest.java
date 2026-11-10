package com.smartkhata.ui;

import com.smartkhata.controller.PurchasesController;
import com.smartkhata.controller.PurchaseOrdersController;
import com.smartkhata.controller.SalesController;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.*;
import com.smartkhata.service.impl.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSyncAndPersistenceIntegrationTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private AuthService authService;
    private InventoryService inventoryService;
    private SupplierService supplierService;
    private CustomerService customerService;
    private PurchaseOrderService poService;
    private GrnService grnService;
    private SaleService saleService;
    private long businessId;
    private long userId;
    private final List<SalesController> salesControllers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("order_sync_e2e.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        authService = new LocalAuthService();
        authService.initializeBusiness(new OnboardingRequestDto(
                "SmartKhata Grocery Store", "Owner", "owner",
                "01711000000", "Dhaka", "BDT", "en", "1234".toCharArray()
        ));
        businessId = SessionContext.getRequiredBusinessId();
        userId = SessionContext.getCurrentUser().id();

        inventoryService = new SqliteInventoryService();
        supplierService = new SqliteSupplierService();
        customerService = new SqliteCustomerService();
        poService = new SqlitePurchaseOrderService();
        grnService = new SqliteGrnService();
        saleService = new SqliteSaleService();
    }

    @AfterEach
    void tearDown() {
        for (SalesController sc : salesControllers) {
            try {
                sc.shutdown();
            } catch (Exception ignored) {}
        }
        salesControllers.clear();

        ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS);
        try {
            CountDownLatch fxDrain = new CountDownLatch(1);
            Platform.runLater(fxDrain::countDown);
            fxDrain.await(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            stmt.execute("PRAGMA journal_mode = DELETE;");
        } catch (Exception ignored) {}

        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistRefreshSelectFilterSyncGrnAndSurviveRestartForPurchaseOrders() throws Exception {
        Category cat = inventoryService.createCategory(businessId, "Oil", "Cooking oils");
        Product oil = inventoryService.createProduct(new ProductCreateDto(
                businessId, cat.id(), "5L Soyabean Oil", "89411001", "OIL-5L", "pcs",
                Money.ofBdt(750), Money.ofBdt(820), 5, 20, "Opening", null,
                Money.ofBdt(740), Money.ofBdt(850), "box", 1
        ));
        Supplier sup = supplierService.createSupplier(new SupplierCreateDto(
                businessId, "Fresh Agro Ltd", "Kamal", "01811000000", "Dhaka",
                Money.ZERO, "Main vendor", "SUP-FRESH", null, "Net 15", 15, true
        ));

        FXMLLoader[] loaderHolder = new FXMLLoader[1];
        Parent[] rootHolder = new Parent[1];
        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/smartkhata/ui/views/PurchasesView.fxml"));
                rootHolder[0] = loader.load();
                loaderHolder[0] = loader;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitAsyncLoads();

        // Initially truly empty
        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) rootHolder[0].lookup("#tblOrders");
            assertThat(tbl.getItems()).isEmpty();
            VBox ph = (VBox) tbl.getPlaceholder();
            Label title = (Label) ph.getChildren().get(0);
            assertThat(title.getText()).isEqualTo("No Purchase Orders Yet");
        });

        // Test 2 & Test 3: Save DRAFT PO -> appears as DRAFT -> Confirm PO -> updates cleanly without duplicating
        PurchaseOrder draftPo = poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                null, businessId, sup.id(), LocalDate.now().plusDays(2),
                Money.ZERO, Money.ZERO, Money.ZERO, "REF-DRAFT", "Draft note",
                false, userId,
                List.of(new PurchaseOrderItemCreateDto(oil.id(), 10, "box", 1, Money.ofBdt(740), Money.ZERO, Money.ZERO, null))
        ));
        assertThat(draftPo.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(inventoryService.searchProducts(businessId, null, null, false).get(0).currentStock()).isEqualTo(20);

        runOnFxThread(() -> {
            PurchaseOrdersController poCtrl = extractPoController(loaderHolder[0].getController());
            poCtrl.loadOrdersAndSelect(draftPo.id());
        });
        awaitAsyncLoads();
        extractPoController(loaderHolder[0].getController()).pendingLoadsFuture().get(5, TimeUnit.SECONDS);
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) rootHolder[0].lookup("#tblOrders");
            assertThat(tbl.getItems()).hasSize(1);
            assertThat(tbl.getItems().get(0).poNumber()).isEqualTo(draftPo.poNumber());
            assertThat(tbl.getItems().get(0).status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(tbl.getSelectionModel().getSelectedItem().id()).isEqualTo(draftPo.id());
            Label lblStatus = (Label) rootHolder[0].lookup("#lblDetailPoStatus");
            assertThat(lblStatus.getText()).isEqualTo("DRAFT");
        });

        // Confirm PO & reload without duplicating row
        PurchaseOrder confirmed = poService.confirmPurchaseOrder(draftPo.id());
        assertThat(confirmed.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);

        // Test 1: Create a second confirmed PO directly
        PurchaseOrder secondPo = poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                null, businessId, sup.id(), LocalDate.now().plusDays(3),
                Money.ZERO, Money.ZERO, Money.ZERO, "REF-2", "Confirmed order",
                true, userId,
                List.of(new PurchaseOrderItemCreateDto(oil.id(), 5, "box", 1, Money.ofBdt(740), Money.ZERO, Money.ZERO, null))
        ));

        // Backdate draftPo to 2 months ago for Test 5 Date Filter testing
        LocalDate twoMonthsAgo = LocalDate.now().minusMonths(2);
        TransactionManager.runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE purchase_orders SET po_date = ? WHERE id = ?")) {
                ps.setString(1, twoMonthsAgo.toString() + " 10:30:00");
                ps.setLong(2, draftPo.id());
                ps.executeUpdate();
            }
        });

        // Test 6: Partial GRN against confirmed draftPo -> PO Received & Status & Remaining update live
        List<PurchaseOrderItemViewDto> draftItems = poService.getPurchaseOrderDetail(draftPo.id()).orElseThrow().items();
        grnService.postGoodsReceipt(new GrnCreateDto(
                businessId, draftPo.id(), sup.id(), Money.ZERO, Money.ZERO, "CH-001", "Partial delivery",
                UUID.randomUUID().toString(), userId,
                List.of(new GrnItemCreateDto(draftItems.get(0).id(), oil.id(), 4, 4, 0, 0, "box", 1, Money.ofBdt(740), Money.ZERO, null))
        ));
        assertThat(inventoryService.searchProducts(businessId, null, null, false).get(0).currentStock()).isEqualTo(24);

        runOnFxThread(() -> {
            PurchasesController pc = loaderHolder[0].getController();
            pc.refreshAll();
        });
        awaitAsyncLoads();

        runOnFxThread(() -> {
            PurchaseOrdersController poCtrl = extractPoController(loaderHolder[0].getController());
            poCtrl.loadOrdersAndSelect(draftPo.id());
        });
        awaitAsyncLoads();
        extractPoController(loaderHolder[0].getController()).pendingLoadsFuture().get(5, TimeUnit.SECONDS);
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) rootHolder[0].lookup("#tblOrders");
            assertThat(tbl.getItems()).hasSize(2);
            PurchaseOrderListItemDto updatedRow = tbl.getItems().stream().filter(p -> p.id() == draftPo.id()).findFirst().orElseThrow();
            assertThat(updatedRow.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
            assertThat(updatedRow.totalReceivedQty()).isEqualTo(4);
            assertThat(updatedRow.totalOrderedQty()).isEqualTo(10);

            TableView<PurchaseOrderItemViewDto> tblItems = (TableView<PurchaseOrderItemViewDto>) rootHolder[0].lookup("#tblPoItems");
            assertThat(tblItems.getItems()).hasSize(1);
            assertThat(tblItems.getItems().get(0).pendingQty()).isEqualTo(6);

            // Test 5: Date filtering "Today" vs "Last Month" (filter-aware empty state)
            ComboBox<String> cmbDate = (ComboBox<String>) rootHolder[0].lookup("#cmbPoDateRange");
            cmbDate.setValue("Today");
            cmbDate.getOnAction().handle(null);
        });
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) rootHolder[0].lookup("#tblOrders");
            assertThat(tbl.getItems()).hasSize(1);
            assertThat(tbl.getItems().get(0).id()).isEqualTo(secondPo.id());

            // Last month has 0 rows -> shows filter-aware "No Matching Purchase Orders"
            ComboBox<String> cmbDate = (ComboBox<String>) rootHolder[0].lookup("#cmbPoDateRange");
            cmbDate.setValue("Last Month");
            cmbDate.getOnAction().handle(null);
        });
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) rootHolder[0].lookup("#tblOrders");
            assertThat(tbl.getItems()).isEmpty();
            VBox ph = (VBox) tbl.getPlaceholder();
            Label title = (Label) ph.getChildren().get(0);
            assertThat(title.getText()).isEqualTo("No Matching Purchase Orders");

            Button clearBtn = (Button) rootHolder[0].lookup("#btnPoClearFilters");
            clearBtn.fire();
        });
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) rootHolder[0].lookup("#tblOrders");
            assertThat(tbl.getItems()).hasSize(2);
        });

        // Test 4: Simulate full application restart against the same SQLite DB file
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        assertThat(authService.login("owner", "1234".toCharArray()).isSuccess()).isTrue();

        FXMLLoader restartLoader = new FXMLLoader(getClass().getResource("/com/smartkhata/ui/views/PurchasesView.fxml"));
        Parent[] restartedRoot = new Parent[1];
        runOnFxThread(() -> {
            try {
                restartedRoot[0] = restartLoader.load();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) restartedRoot[0].lookup("#tblOrders");
            assertThat(tbl.getItems())
                    .hasSize(2)
                    .extracting(PurchaseOrderListItemDto::poNumber)
                    .containsExactlyInAnyOrder(draftPo.poNumber(), secondPo.poNumber());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSyncPosSalesIntoHistoryPreventDuplicatesAndSurfaceFailuresCleanly() throws Exception {
        Category cat = inventoryService.createCategory(businessId, "Rice", "Rice staples");
        Product rice = inventoryService.createProduct(new ProductCreateDto(
                businessId, cat.id(), "Miniket Rice 1kg", "100001", "RICE-1KG", "kg",
                Money.ofBdt(70), Money.ofBdt(80), 5, 50, "Opening"
        ));
        Customer rahim = customerService.createCustomer(new CustomerCreateDto(
                businessId, "Rahim Uddin", "01799000000", "Mirpur", Money.ofBdt(10000), Money.ZERO, null
        ));

        FXMLLoader salesLoader = new FXMLLoader(getClass().getResource("/com/smartkhata/ui/views/SalesView.fxml"));
        Parent[] salesRoot = new Parent[1];
        SalesController[] scHolder = new SalesController[1];
        runOnFxThread(() -> {
            try {
                salesRoot[0] = salesLoader.load();
                scHolder[0] = salesLoader.getController();
                salesControllers.add(scHolder[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitAsyncLoads();

        // Test 7 & Test 9: Complete sale + Idempotency duplicate prevention + immediate Sales History sync + zero PO leakage
        String idemKey = UUID.randomUUID().toString();
        SaleCreateDto saleReq = new SaleCreateDto(
                businessId, rahim.id(),
                List.of(new SaleItemDto(rice.id(), rice.name(), 3, Money.ofBdt(80), Money.ofBdt(70), Money.ZERO, Money.ofBdt(240))),
                Money.ZERO, Money.ofBdt(140), "CASH", idemKey, "POS sale", userId
        );
        Sale created1 = saleService.createSale(saleReq);
        Sale duplicate = saleService.createSale(saleReq);
        assertThat(duplicate.id()).isEqualTo(created1.id());
        assertThat(inventoryService.searchProducts(businessId, null, null, false).get(0).currentStock()).isEqualTo(47);
        // Accounting integrity: POS sale NEVER leaks into purchase_orders
        assertThat(poService.searchPurchaseOrders(businessId, null, null, null, null, null)).isEmpty();

        runOnFxThread(() -> scHolder[0].refreshData());
        scHolder[0].pendingLoadsFuture().get(5, TimeUnit.SECONDS);
        awaitAsyncLoads();

        runOnFxThread(() -> {
            new javafx.scene.Scene(salesRoot[0], 1280, 860);
            salesRoot[0].applyCss();
            salesRoot[0].layout();

            Button btnCompleteSale = (Button) salesRoot[0].lookup("#btnCompleteSale");
            assertThat(btnCompleteSale).isNotNull();
            assertThat(btnCompleteSale.getText()).contains("Confirm Order");
            assertThat(btnCompleteSale.isVisible()).isTrue();

            ComboBox<SalesController.CustomerOption> cmbPosCustomer =
                    (ComboBox<SalesController.CustomerOption>) salesRoot[0].lookup("#cmbPosCustomer");
            SalesController.CustomerOption rahimOpt = cmbPosCustomer.getItems().stream()
                    .filter(o -> o.id() != null && o.id().equals(rahim.id()))
                    .findFirst().orElseThrow();
            cmbPosCustomer.getSelectionModel().select(rahimOpt);

            Label lblCustNamePhone = (Label) salesRoot[0].lookup("#lblPosCustNamePhone");
            Label lblCustAddress = (Label) salesRoot[0].lookup("#lblPosCustAddress");
            Label lblCustDueLimit = (Label) salesRoot[0].lookup("#lblPosCustDueLimit");
            assertThat(lblCustNamePhone.getText()).contains("Rahim Uddin").contains("01799000000");
            assertThat(lblCustAddress.getText()).contains("Mirpur");
            assertThat(lblCustDueLimit.isVisible()).isTrue();
            assertThat(lblCustDueLimit.getText()).contains("Limit:");

            TableView<SaleListItemDto> tblSales = (TableView<SaleListItemDto>) salesRoot[0].lookup("#tblSales");
            TableView<SaleListItemDto> tblPosOrders = (TableView<SaleListItemDto>) salesRoot[0].lookup("#tblPosOrders");
            Label lblPosOrdersCount = (Label) salesRoot[0].lookup("#lblPosOrdersCount");
            Button btnToggleHistory = (Button) salesRoot[0].lookup("#btnToggleHistory");

            assertThat(tblSales.getItems()).hasSize(1);
            assertThat(tblPosOrders.getItems()).hasSize(1);
            assertThat(lblPosOrdersCount.getText()).isEqualTo("1 Order");
            assertThat(btnToggleHistory.getText()).isEqualTo("Previous Orders (1)");

            SaleListItemDto row = tblPosOrders.getItems().get(0);
            assertThat(row.invoiceNumber()).isEqualTo(created1.invoiceNumber());
            assertThat(row.customerName()).isEqualTo("Rahim Uddin");
            assertThat(row.netAmount()).isEqualTo(Money.ofBdt(240));
            assertThat(row.paidAmount()).isEqualTo(Money.ofBdt(140));
            assertThat(row.dueAmount()).isEqualTo(Money.ofBdt(100));
            assertThat(row.paymentStatus()).isEqualTo("PARTIAL");

            Label lblCount = (Label) salesRoot[0].lookup("#lblTotalInvoices");
            assertThat(lblCount.getText()).isEqualTo("1");
        });

        // Sequence uniqueness after void + delete of a pure Baki invoice #2 (zero linked payment receipt)
        Sale created2 = saleService.createSale(new SaleCreateDto(
                businessId, rahim.id(),
                List.of(new SaleItemDto(rice.id(), rice.name(), 1, Money.ofBdt(80), Money.ofBdt(70), Money.ZERO, Money.ofBdt(80))),
                Money.ZERO, Money.ZERO, "CASH", UUID.randomUUID().toString(), null, userId
        ));
        saleService.voidSale(created2.id(), "Test void", userId);
        saleService.deleteVoidedSale(created2.id());
        Sale created3 = saleService.createSale(new SaleCreateDto(
                businessId, null,
                List.of(new SaleItemDto(rice.id(), rice.name(), 1, Money.ofBdt(80), Money.ofBdt(70), Money.ZERO, Money.ofBdt(80))),
                Money.ZERO, Money.ofBdt(80), "CASH", UUID.randomUUID().toString(), null, userId
        ));
        assertThat(created3.invoiceNumber()).isNotEqualTo(created1.invoiceNumber()).endsWith("-0002");

        // Test 8a: Failed sale does not persist partial rows
        assertThatThrownBy(() -> saleService.createSale(new SaleCreateDto(
                businessId, null,
                List.of(new SaleItemDto(rice.id(), rice.name(), 9999, Money.ofBdt(80), Money.ofBdt(70), Money.ZERO, Money.ofBdt(799920))),
                Money.ZERO, Money.ofBdt(799920), "CASH", UUID.randomUUID().toString(), null, userId
        ))).isInstanceOf(InsufficientStockException.class);
        assertThat(saleService.searchSales(businessId, null, null, null, null, null)).hasSize(2);

        // Test 8b: Query error on PurchaseOrdersController surfaces explicit error state instead of "No Purchase Orders Yet"
        PurchaseOrderService failingPoService = new PurchaseOrderService() {
            @Override public PurchaseOrder savePurchaseOrder(PurchaseOrderCreateDto dto) { throw new RuntimeException("DB offline"); }
            @Override public PurchaseOrder confirmPurchaseOrder(long poId) { throw new RuntimeException("DB offline"); }
            @Override public void cancelPurchaseOrder(long poId, String reason) { throw new RuntimeException("DB offline"); }
            @Override public void closePurchaseOrder(long poId, String reason) { throw new RuntimeException("DB offline"); }
            @Override public Optional<PurchaseOrderDetailDto> getPurchaseOrderDetail(long poId) { return Optional.empty(); }
            @Override public List<PurchaseOrderListItemDto> searchPurchaseOrders(long bid, String q, Long s, PurchaseOrderStatus st, LocalDate sd, LocalDate ed) {
                throw new RuntimeException("Simulated SQLite read failure");
            }
            @Override public ProductSupplierViewDto resolveProductPricingForSupplier(long pid, long sid) { throw new RuntimeException("DB offline"); }
        };

        FXMLLoader errorLoader = new FXMLLoader(getClass().getResource("/com/smartkhata/ui/views/PurchaseOrdersTab.fxml"));
        errorLoader.setControllerFactory(cls -> {
            if (cls == PurchaseOrdersController.class) {
                return new PurchaseOrdersController(failingPoService, supplierService, inventoryService);
            }
            throw new IllegalArgumentException("Unexpected controller: " + cls);
        });
        Parent[] errRoot = new Parent[1];
        runOnFxThread(() -> {
            try {
                errRoot[0] = errorLoader.load();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitAsyncLoads();

        runOnFxThread(() -> {
            TableView<PurchaseOrderListItemDto> tbl = (TableView<PurchaseOrderListItemDto>) errRoot[0].lookup("#tblOrders");
            VBox ph = (VBox) tbl.getPlaceholder();
            Label title = (Label) ph.getChildren().get(0);
            Label msg = (Label) ph.getChildren().get(1);
            assertThat(title.getText()).isEqualTo("Unable to Load Purchase Orders");
            assertThat(msg.getText()).contains("Simulated SQLite read failure");
        });

        // Test 10: Edit POS Order & Delete Active Paid/Partial POS Orders atomically + Catalog Seeder with SVG images
        com.smartkhata.service.sample.SampleDataSeeder.SeedCatalogResult seedRes1 =
                com.smartkhata.service.sample.SampleDataSeeder.seedRichGroceryCatalogAndCustomers(businessId, userId);
        com.smartkhata.service.sample.SampleDataSeeder.SeedCatalogResult seedRes2 =
                com.smartkhata.service.sample.SampleDataSeeder.seedRichGroceryCatalogAndCustomers(businessId, userId);
        assertThat(seedRes1.productsAdded()).isEqualTo(10);
        assertThat(seedRes1.customersAdded()).isEqualTo(6);
        assertThat(seedRes2.productsAdded()).isZero();
        assertThat(seedRes2.customersAdded()).isZero();

        // Verify previous sale edit (qty 3 -> 5, paid 140 -> 300, assign note) updates stock, invoice & ledger accurately
        int stockBeforeEdit = inventoryService.searchProducts(businessId, "Miniket Rice 1kg", null, false).get(0).currentStock();
        Sale editedSale1 = saleService.updateSaleOrder(new SaleUpdateDto(
                created1.id(), businessId, rahim.id(),
                List.of(new SaleItemDto(rice.id(), rice.name(), 5, Money.ofBdt(80), Money.ofBdt(70), Money.ZERO, Money.ofBdt(400))),
                Money.ofBdt(20), Money.ofBdt(300), "BKASH", "Edited order qty to 5", userId
        ));
        assertThat(editedSale1.netAmount()).isEqualTo(Money.ofBdt(380));
        assertThat(editedSale1.paidAmount()).isEqualTo(Money.ofBdt(300));
        assertThat(editedSale1.dueAmount()).isEqualTo(Money.ofBdt(80));
        assertThat(editedSale1.paymentStatus()).isEqualTo("PARTIAL");
        assertThat(saleService.findPrimaryPaymentMethod(editedSale1.id())).contains("BKASH");
        assertThat(inventoryService.searchProducts(businessId, "Miniket Rice 1kg", null, false).get(0).currentStock())
                .isEqualTo(stockBeforeEdit - 2);
        assertThat(customerService.searchCustomers(businessId, "Rahim Uddin", false).get(0).currentDue())
                .isEqualTo(Money.ofBdt(80));

        // Verify direct deletion of active POS order #3 (walk-in cash paid!) & edited #1 (partial customer order!)
        saleService.deleteSaleOrder(created3.id());
        saleService.deleteSaleOrder(editedSale1.id());

        assertThat(saleService.searchSales(businessId, null, null, null, null, null)).isEmpty();
        assertThat(customerService.searchCustomers(businessId, "Rahim Uddin", false).get(0).currentDue())
                .isEqualTo(Money.ZERO);
        assertThat(inventoryService.searchProducts(businessId, "Miniket Rice 1kg", null, false).get(0).currentStock())
                .isEqualTo(50);

        runOnFxThread(() -> scHolder[0].refreshData());
        scHolder[0].pendingLoadsFuture().get(5, TimeUnit.SECONDS);
        awaitAsyncLoads();
        runOnFxThread(() -> {
            TableView<SaleListItemDto> tblPosOrders = (TableView<SaleListItemDto>) salesRoot[0].lookup("#tblPosOrders");
            Label lblPosOrdersCount = (Label) salesRoot[0].lookup("#lblPosOrdersCount");
            assertThat(tblPosOrders.getMinHeight()).isGreaterThanOrEqualTo(320.0);
            assertThat(tblPosOrders.getItems()).isEmpty();
            assertThat(lblPosOrdersCount.getText()).isEqualTo("0 Orders");
        });

        // Verify POS Previous Orders table shows strictly the latest 8 orders when > 8 orders exist
        List<String> createdInvoices = new ArrayList<>();
        for (int idx = 1; idx <= 10; idx++) {
            Sale s = saleService.createSale(new SaleCreateDto(
                    businessId, null,
                    List.of(new SaleItemDto(rice.id(), rice.name(), 1, Money.ofBdt(80), Money.ofBdt(70), Money.ZERO, Money.ofBdt(80))),
                    Money.ZERO, Money.ofBdt(80), "CASH", UUID.randomUUID().toString(), "Order #" + idx, userId
            ));
            createdInvoices.add(s.invoiceNumber());
        }
        runOnFxThread(() -> scHolder[0].refreshData());
        scHolder[0].pendingLoadsFuture().get(5, TimeUnit.SECONDS);
        awaitAsyncLoads();
        runOnFxThread(() -> {
            TableView<SaleListItemDto> tblPosOrders = (TableView<SaleListItemDto>) salesRoot[0].lookup("#tblPosOrders");
            TableView<SaleListItemDto> tblSales = (TableView<SaleListItemDto>) salesRoot[0].lookup("#tblSales");
            Label lblPosOrdersCount = (Label) salesRoot[0].lookup("#lblPosOrdersCount");
            assertThat(tblSales.getItems()).hasSize(10);
            assertThat(tblPosOrders.getItems()).hasSize(8);
            assertThat(tblPosOrders.getItems().get(0).invoiceNumber()).isEqualTo(createdInvoices.get(9));
            assertThat(tblPosOrders.getItems().get(7).invoiceNumber()).isEqualTo(createdInvoices.get(2));
            assertThat(lblPosOrdersCount.getText()).isEqualTo("Last 8 of 10 Orders");
        });

        // Verify ReportsView Sales Summary renders Actions (View/Edit/Delete) column right alongside all 10 orders
        FXMLLoader reportsLoader = new FXMLLoader(getClass().getResource("/com/smartkhata/ui/views/ReportsView.fxml"));
        Parent[] reportsRoot = new Parent[1];
        runOnFxThread(() -> {
            try {
                reportsRoot[0] = reportsLoader.load();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        awaitAsyncLoads();
        runOnFxThread(() -> {
            TableView<List<String>> tblReport = (TableView<List<String>>) reportsRoot[0].lookup("#tblReport");
            assertThat(tblReport.getItems()).hasSize(10);
            assertThat(tblReport.getColumns()).hasSize(10);
            assertThat(tblReport.getColumns().get(9).getText()).isEqualTo("Actions");
            assertThat(tblReport.getItems().get(0).get(0)).isEqualTo(createdInvoices.get(9));
        });
    }

    private static PurchaseOrdersController extractPoController(PurchasesController pc) {
        try {
            var f = PurchasesController.class.getDeclaredField("poTabController");
            f.setAccessible(true);
            return (PurchaseOrdersController) f.get(pc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitAsyncLoads() throws Exception {
        ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS);
        CountDownLatch fxLatch = new CountDownLatch(1);
        Platform.runLater(fxLatch::countDown);
        assertThat(fxLatch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable wrapped = () -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        };
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                wrapped.run();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.setImplicitExit(false);
            Platform.runLater(wrapped);
        }
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            throw new AssertionError("JavaFX test action failed", error.get());
        }
    }
}

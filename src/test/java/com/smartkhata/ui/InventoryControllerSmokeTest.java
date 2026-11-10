package com.smartkhata.ui;

import com.smartkhata.controller.InventoryController;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SaleService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.LocalAuthService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteSaleService;
import com.smartkhata.util.BengaliMoneyFormatter;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryControllerSmokeTest {

    @TempDir
    Path tempDir;

    private InventoryService inventoryService;
    private SaleService saleService;
    private long businessId;
    private long userId;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("inventory_ui_smoke.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        AuthService authService = new LocalAuthService();
        authService.initializeBusiness(new OnboardingRequestDto(
                "SmartKhata Test Store", "Test Owner", "test_owner",
                "01711000000", "Dhaka", "BDT", "en", "1234".toCharArray()
        ));
        businessId = SessionContext.getRequiredBusinessId();
        userId = SessionContext.getCurrentUser().id();

        inventoryService = new SqliteInventoryService();
        saleService = new SqliteSaleService();
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLoadInventoryPageWithRealKpiCardsPaginationAndProductMovements() throws Exception {
        Category oilCat = inventoryService.createCategory(businessId, "Oil", "Edible oils");
        Category groceryCat = inventoryService.createCategory(businessId, "Grocery", "Staple groceries");

        // Primary retained product matching prompt metrics: 20 opening - 1 sale = 19 pkt, value = 19 * 780 = 14,820 BDT
        Product oil = inventoryService.createProduct(new ProductCreateDto(
                businessId, oilCat.id(), "5 Liter Soyabean Oil", "646515216", "SKU-OIL-00001", "pkt",
                Money.ofBdt(780.00), Money.ofBdt(740.00), 5, 20, "Initial Opening"
        ));
        saleService.createSale(new SaleCreateDto(
                businessId, null,
                List.of(new SaleItemDto(oil.id(), oil.name(), 1, Money.ofBdt(740.00), Money.ofBdt(780.00), Money.ZERO, Money.ofBdt(740.00))),
                Money.ZERO, Money.ofBdt(740.00), "CASH", "INV-SMOKE-01", null, userId
        ));

        // Add additional items to exercise low stock, out of stock, archived & pagination (> 10 items)
        inventoryService.createProduct(new ProductCreateDto(
                businessId, groceryCat.id(), "Low Stock Tea", "100001", "SKU-TEA-1", "pkt",
                Money.ofBdt(200.00), Money.ofBdt(240.00), 10, 4, "Opening"
        ));
        inventoryService.createProduct(new ProductCreateDto(
                businessId, groceryCat.id(), "Out of Stock Salt", "100002", "SKU-SALT-0", "pkt",
                Money.ofBdt(35.00), Money.ofBdt(42.00), 10, 0, "Opening"
        ));
        Product archivedItem = inventoryService.createProduct(new ProductCreateDto(
                businessId, groceryCat.id(), "Archived Spice", "100003", "SKU-ARC-1", "pkt",
                Money.ofBdt(50.00), Money.ofBdt(60.00), 5, 10, "Opening"
        ));
        inventoryService.archiveProduct(archivedItem.id());

        for (int i = 1; i <= 9; i++) {
            inventoryService.createProduct(new ProductCreateDto(
                    businessId, groceryCat.id(), String.format("Staple Item %02d", i),
                    "20000" + i, "SKU-ST-" + i, "pcs",
                    Money.ofBdt(10.00), Money.ofBdt(15.00), 2, 10, "Opening"
            ));
        }

        AtomicReference<Map<String, Object>> namespaceRef = new AtomicReference<>();
        AtomicReference<InventoryController> controllerRef = new AtomicReference<>();

        runOnFxThread(() -> {
            try {
                URL fxmlUrl = InventoryController.class.getResource("/com/smartkhata/ui/views/InventoryView.fxml");
                assertThat(fxmlUrl).isNotNull();
                FXMLLoader loader = new FXMLLoader(fxmlUrl);
                Parent root = loader.load();
                Scene scene = new Scene(root, 1280, 800);
                URL cssUrl = InventoryController.class.getResource("/com/smartkhata/ui/css/styles.css");
                if (cssUrl != null) {
                    scene.getStylesheets().add(cssUrl.toExternalForm());
                }
                namespaceRef.set(loader.getNamespace());
                controllerRef.set(loader.getController());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, Object> ns = namespaceRef.get();
        TableView<ProductListItemDto> tblProducts = (TableView<ProductListItemDto>) ns.get("tblProducts");
        TableView<StockMovement> tblMovements = (TableView<StockMovement>) ns.get("tblMovements");

        // Poll boundedly for async CompletableFuture refreshData() on background pool
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!tblProducts.getItems().isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }

        // Wait for detail selection and movements async load to settle cleanly
        long moveDeadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < moveDeadline) {
            if (!tblMovements.getItems().isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }

        Label lblTotal = (Label) ns.get("lblTotalProductsCount");
        Label lblActive = (Label) ns.get("lblActiveProductsCount");
        Label lblLow = (Label) ns.get("lblLowStockCount");
        Label lblOut = (Label) ns.get("lblOutOfStockCount");
        Label lblVal = (Label) ns.get("lblTotalValuation");
        Label lblPageSummary = (Label) ns.get("lblPaginationSummary");

        assertThat(lblTotal.getText()).isEqualTo("13"); // 12 active + 1 archived
        assertThat(lblActive.getText()).isEqualTo("12");
        assertThat(lblLow.getText()).isEqualTo("2");   // Low Stock Tea (4<=10) + Out of Stock Salt (0<=10)
        assertThat(lblOut.getText()).isEqualTo("1");   // Out of Stock Salt
        assertThat(lblVal.getText()).isEqualTo(BengaliMoneyFormatter.format(Money.ofBdt(14820 + 800 + 900)));

        // Page 1 shows 10 rows of 12 active products
        assertThat(tblProducts.getItems()).hasSize(10);
        assertThat(lblPageSummary.getText()).contains("Showing 1").contains("10 of 12 products");

        // First product sorted alphabetically is "5 Liter Soyabean Oil"
        Label lblDetailName = (Label) ns.get("lblDetailName");
        Label lblDetailStock = (Label) ns.get("lblDetailStock");
        Label lblDetailThreshold = (Label) ns.get("lblDetailThreshold");
        Label lblDetailValue = (Label) ns.get("lblDetailValue");
        Label lblDetailCatBarcode = (Label) ns.get("lblDetailCategoryBarcode");
        Label lblDetailSkuUnit = (Label) ns.get("lblDetailSkuUnit");

        assertThat(lblDetailName.getText()).isEqualTo("5 Liter Soyabean Oil");
        assertThat(lblDetailCatBarcode.getText()).contains("Oil").contains("646515216");
        assertThat(lblDetailSkuUnit.getText()).contains("SKU-OIL-00001");
        assertThat(lblDetailStock.getText()).isEqualTo("19 pkt");
        assertThat(lblDetailThreshold.getText()).isEqualTo("5 pkt");
        assertThat(lblDetailValue.getText()).isEqualTo(BengaliMoneyFormatter.format(Money.ofBdt(14820.00)));

        assertThat(tblMovements.getItems()).hasSize(2);
        assertThat(tblMovements.getItems()).anyMatch(m -> "OPENING".equals(m.movementType()) && m.quantity() == 20);
        assertThat(tblMovements.getItems()).anyMatch(m -> "SALE_OUT".equals(m.movementType()) && m.quantity() == -1);

        // Verify search filter interaction updates pagination cleanly
        TextField txtSearch = (TextField) ns.get("txtSearch");
        runOnFxThread(() -> {
            txtSearch.setText("Soyabean");
            controllerRef.get().refreshData();
        });
        long searchDeadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < searchDeadline && tblProducts.getItems().size() != 1) {
            Thread.sleep(40);
        }
        assertThat(tblProducts.getItems()).hasSize(1);
        assertThat(lblPageSummary.getText()).isEqualTo("Showing 1 – 1 of 1 products");
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
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            throw new AssertionError("JavaFX test action failed", error.get());
        }
    }
}


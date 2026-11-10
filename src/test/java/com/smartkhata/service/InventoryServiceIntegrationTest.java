package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.StockMovement;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.SqliteInventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive integration tests for Products, Inventory, and Stock Movement Ledger.
 * Verifies opening stock posting, perpetual movement math, auditable manual adjustments,
 * negative stock rejection, low-stock alerts, documented inventory valuation,
 * multi-tenant isolation, and role authorization.
 */
class InventoryServiceIntegrationTest {

    private Path tempDbPath;
    private InventoryService inventoryService;
    private Business biz1;
    private User owner1;
    private User staff1;
    private Business biz2;
    private User owner2;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDbPath = tempDir.resolve("inventory_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDbPath.toAbsolutePath().toString());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        inventoryService = new SqliteInventoryService();

        // Seed 2 distinct businesses for multi-tenant isolation testing
        TransactionManager.runInTransaction(conn -> {
            // Business 1
            long b1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Bismillah Super Store', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b1Id = rs.getLong(1);
                }
            }
            biz1 = new Business(b1Id, "Bismillah Super Store", null, null, null, null, "BDT", null, null);

            // Owner for Biz 1
            long u1Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'bismillah_owner', 'Hafizur Rahman', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u1Id = rs.getLong(1);
                }
            }
            owner1 = new User(u1Id, b1Id, "bismillah_owner", "Hafizur Rahman", "dummy", "dummy", UserRole.OWNER, true, null, null, null);

            // Cashier for Biz 1
            long staffId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'bismillah_staff', 'Tariqul Islam', 'dummy', 'dummy', 'CASHIER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b1Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    staffId = rs.getLong(1);
                }
            }
            staff1 = new User(staffId, b1Id, "bismillah_staff", "Tariqul Islam", "dummy", "dummy", UserRole.CASHIER, true, null, null, null);

            // Business 2
            long b2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Karnaphuli Mart', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    b2Id = rs.getLong(1);
                }
            }
            biz2 = new Business(b2Id, "Karnaphuli Mart", null, null, null, null, "BDT", null, null);

            long u2Id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) " +
                            "VALUES (?, 'karnaphuli_owner', 'Saiful Alam', 'dummy', 'dummy', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, b2Id);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    u2Id = rs.getLong(1);
                }
            }
            owner2 = new User(u2Id, b2Id, "karnaphuli_owner", "Saiful Alam", "dummy", "dummy", UserRole.OWNER, true, null, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldCreateProductWithInitialOpeningStockInMovements() {
        SessionContext.setSession(biz1, owner1);

        ProductCreateDto dto = new ProductCreateDto(
                biz1.id(),
                null,
                "Rupchanda Fortified Soybean Oil 5L",
                "894110055112",
                "OIL-RUP-5L",
                "litre",
                Money.ofBdt(820.00),
                Money.ofBdt(890.00),
                5,
                40, // Opening stock count
                "Initial batch opening inventory"
        );

        Product created = inventoryService.createProduct(dto);

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Rupchanda Fortified Soybean Oil 5L");
        assertThat(created.barcode()).isEqualTo("894110055112");
        assertThat(created.costPrice()).isEqualTo(Money.ofBdt(820.00));
        assertThat(created.sellingPrice()).isEqualTo(Money.ofBdt(890.00));

        // Stock must be exactly 40 derived from stock_movements
        int currentStock = inventoryService.getCurrentStock(created.id());
        assertThat(currentStock).isEqualTo(40);

        List<StockMovement> movements = inventoryService.getProductMovements(created.id());
        assertThat(movements).hasSize(1);

        StockMovement openingMovement = movements.get(0);
        assertThat(openingMovement.movementType()).isEqualTo("OPENING");
        assertThat(openingMovement.quantity()).isEqualTo(40);
        assertThat(openingMovement.unitCost()).isEqualTo(Money.ofBdt(820.00));
        assertThat(openingMovement.notes()).isEqualTo("Initial batch opening inventory");
        assertThat(openingMovement.createdByUserId()).isEqualTo(owner1.id());
    }

    @Test
    void shouldCreateProductWithZeroStockWithoutMovements() {
        SessionContext.setSession(biz1, owner1);

        ProductCreateDto dto = new ProductCreateDto(
                biz1.id(),
                null,
                "Miniket Rice 50kg Bag",
                "894110077889",
                "RICE-MINI-50KG",
                "bag",
                Money.ofBdt(3400.00),
                Money.ofBdt(3650.00),
                3,
                0, // Zero initial stock
                null
        );

        Product created = inventoryService.createProduct(dto);
        assertThat(inventoryService.getCurrentStock(created.id())).isEqualTo(0);
        assertThat(inventoryService.getProductMovements(created.id())).isEmpty();
    }

    @Test
    void shouldRejectDuplicateProductNameInSameBusiness() {
        SessionContext.setSession(biz1, owner1);

        ProductCreateDto first = new ProductCreateDto(
                biz1.id(), null, "Teer Flour 2kg", null, null, "pkt",
                Money.ofBdt(130.00), Money.ofBdt(145.00), 5, 10, null
        );
        inventoryService.createProduct(first);

        ProductCreateDto duplicate = new ProductCreateDto(
                biz1.id(), null, "Teer Flour 2kg", null, null, "pkt",
                Money.ofBdt(130.00), Money.ofBdt(145.00), 5, 0, null
        );

        assertThatThrownBy(() -> inventoryService.createProduct(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists in this store");
    }

    @Test
    void shouldRejectDuplicateBarcodeInSameBusiness() {
        SessionContext.setSession(biz1, owner1);

        ProductCreateDto first = new ProductCreateDto(
                biz1.id(), null, "Lux Soap 150g", "890103038312", null, "pcs",
                Money.ofBdt(65.00), Money.ofBdt(75.00), 10, 50, null
        );
        inventoryService.createProduct(first);

        ProductCreateDto duplicateBarcode = new ProductCreateDto(
                biz1.id(), null, "Lux Soap Bundle", "890103038312", null, "pcs",
                Money.ofBdt(120.00), Money.ofBdt(140.00), 10, 20, null
        );

        assertThatThrownBy(() -> inventoryService.createProduct(duplicateBarcode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("barcode");
    }

    @Test
    void shouldUpdateProductDetailsWithoutMutatingHistoricalMovements() {
        SessionContext.setSession(biz1, owner1);

        Product product = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Radhuni Turmeric Powder 200g", "894110022331", "SPICE-TUR-200",
                "pkt", Money.ofBdt(85.00), Money.ofBdt(100.00), 10, 25, "Initial batch"
        ));

        ProductUpdateDto updateDto = new ProductUpdateDto(
                product.id(),
                null,
                "Radhuni Pure Turmeric Powder 200g (New Pack)",
                "894110022331",
                "SPICE-TUR-200-V2",
                "pkt",
                Money.ofBdt(90.00),
                Money.ofBdt(110.00),
                15
        );

        Product updated = inventoryService.updateProduct(updateDto);

        assertThat(updated.name()).isEqualTo("Radhuni Pure Turmeric Powder 200g (New Pack)");
        assertThat(updated.costPrice()).isEqualTo(Money.ofBdt(90.00));
        assertThat(updated.sellingPrice()).isEqualTo(Money.ofBdt(110.00));
        assertThat(updated.lowStockThreshold()).isEqualTo(15);

        // Stock count and movements must remain strictly intact
        assertThat(inventoryService.getCurrentStock(product.id())).isEqualTo(25);
        List<StockMovement> movements = inventoryService.getProductMovements(product.id());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).quantity()).isEqualTo(25);
    }

    @Test
    void shouldPerformManualStockAdjustmentWithAuditableReason() {
        SessionContext.setSession(biz1, owner1);

        Product product = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Aci Salt 1kg", null, null, "pkt",
                Money.ofBdt(35.00), Money.ofBdt(42.00), 10, 30, null
        ));

        // 1. Inward adjustment (Found extra during shelf physical audit)
        StockAdjustmentDto auditPlus = new StockAdjustmentDto(
                product.id(),
                5,
                "Physical audit found extra carton on top shelf",
                owner1.id()
        );
        StockMovement mPlus = inventoryService.adjustStock(auditPlus);

        assertThat(mPlus.movementType()).isEqualTo("MANUAL_ADJUSTMENT");
        assertThat(mPlus.quantity()).isEqualTo(5);
        assertThat(mPlus.notes()).contains("Physical audit");
        assertThat(inventoryService.getCurrentStock(product.id())).isEqualTo(35);

        // 2. Outward adjustment (Damaged stock write-off)
        StockAdjustmentDto damageMinus = new StockAdjustmentDto(
                product.id(),
                -3,
                "Water leak damaged packaging, discarded safely",
                owner1.id()
        );
        StockMovement mMinus = inventoryService.adjustStock(damageMinus);

        assertThat(mMinus.quantity()).isEqualTo(-3);
        assertThat(inventoryService.getCurrentStock(product.id())).isEqualTo(32);
    }

    @Test
    void shouldRejectManualStockAdjustmentWithoutReason() {
        SessionContext.setSession(biz1, owner1);

        Product product = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Test Item", null, null, "pcs",
                Money.ofBdt(10.00), Money.ofBdt(15.00), 5, 20, null
        ));

        StockAdjustmentDto noReason = new StockAdjustmentDto(product.id(), 5, "   ", owner1.id());

        assertThatThrownBy(() -> inventoryService.adjustStock(noReason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is strictly required");
    }

    @Test
    void shouldRejectManualStockAdjustmentCausingNegativeStock() {
        SessionContext.setSession(biz1, owner1);

        Product product = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Scarce Product", null, null, "pcs",
                Money.ofBdt(100.00), Money.ofBdt(120.00), 2, 8, null
        ));

        // Attempting to remove 12 when only 8 are available must fail
        StockAdjustmentDto excessiveDeduction = new StockAdjustmentDto(
                product.id(), -12, "Damaged stock deduction", owner1.id()
        );

        assertThatThrownBy(() -> inventoryService.adjustStock(excessiveDeduction))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");

        // Stock remains uncorrupted at 8
        assertThat(inventoryService.getCurrentStock(product.id())).isEqualTo(8);
    }

    @Test
    void shouldAccuratelyDetectLowStockProductsAndAvailability() {
        SessionContext.setSession(biz1, owner1);

        // Product A: Stock 4, Threshold 10 -> Low Stock
        Product prodA = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Low Stock Tea 400g", null, null, "pkt",
                Money.ofBdt(220.00), Money.ofBdt(260.00), 10, 4, null
        ));

        // Product B: Stock 50, Threshold 5 -> Healthy Stock
        Product prodB = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Healthy Stock Sugar 1kg", null, null, "kg",
                Money.ofBdt(120.00), Money.ofBdt(135.00), 5, 50, null
        ));

        assertThat(inventoryService.isAvailable(prodA.id(), 4)).isTrue();
        assertThat(inventoryService.isAvailable(prodA.id(), 5)).isFalse();

        assertThatThrownBy(() -> inventoryService.validateStockAvailability(prodA.id(), 10))
                .isInstanceOf(InsufficientStockException.class);

        InventorySummaryDto summary = inventoryService.getInventorySummary(biz1.id());
        assertThat(summary.totalActiveProducts()).isEqualTo(2);
        assertThat(summary.totalStockQuantity()).isEqualTo(54);
        assertThat(summary.lowStockAlertCount()).isEqualTo(1); // Only Prod A
    }

    @Test
    void shouldComputeInventoryValuationUsingRecordedCostMethod() {
        SessionContext.setSession(biz1, owner1);

        // Documented Cost Valuation Method: Recorded Cost Price * Non-negative Stock Count
        // Item 1: 20 pcs * BDT 150 = BDT 3,000.00
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Horlicks 500g Jar", null, null, "jar",
                Money.ofBdt(150.00), Money.ofBdt(180.00), 5, 20, null
        ));

        // Item 2: 10 pcs * BDT 700 = BDT 7,000.00
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Ghee 1kg Tin", null, null, "tin",
                Money.ofBdt(700.00), Money.ofBdt(850.00), 2, 10, null
        ));

        InventorySummaryDto summary = inventoryService.getInventorySummary(biz1.id());
        // Total expected valuation = 3,000.00 + 7,000.00 = BDT 10,000.00
        assertThat(summary.totalInventoryValue()).isEqualTo(Money.ofBdt(10000.00));
    }

    @Test
    void shouldArchiveAndRestoreProductPreservingStockLedger() {
        SessionContext.setSession(biz1, owner1);

        Product product = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Seasonal Winter Blanket", null, null, "pcs",
                Money.ofBdt(1200.00), Money.ofBdt(1500.00), 2, 15, null
        ));

        // 1. Archive product (Owner only)
        inventoryService.archiveProduct(product.id());

        // Default search hides archived items
        List<ProductListItemDto> activeList = inventoryService.searchProducts(biz1.id(), "Seasonal", null, false);
        assertThat(activeList).isEmpty();

        // Include archived displays it
        List<ProductListItemDto> allList = inventoryService.searchProducts(biz1.id(), "Seasonal", null, true);
        assertThat(allList).hasSize(1);
        assertThat(allList.get(0).isArchived()).isTrue();
        assertThat(allList.get(0).currentStock()).isEqualTo(15);

        // Movements remain intact
        List<StockMovement> movements = inventoryService.getProductMovements(product.id());
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).quantity()).isEqualTo(15);

        // 2. Restore product
        inventoryService.restoreProduct(product.id());
        List<ProductListItemDto> restored = inventoryService.searchProducts(biz1.id(), "Seasonal", null, false);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).isArchived()).isFalse();
    }

    @Test
    void shouldEnforceCrossBusinessIsolationForProductsAndStock() {
        // Create product in Business 1
        SessionContext.setSession(biz1, owner1);
        Product b1Product = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "B1 Proprietary Medicine", null, null, "box",
                Money.ofBdt(50.00), Money.ofBdt(65.00), 5, 100, null
        ));

        // Switch active session to Business 2
        SessionContext.setSession(biz2, owner2);

        // Business 2 cannot query Business 1's products
        List<ProductListItemDto> b2List = inventoryService.searchProducts(biz2.id(), "Proprietary", null, false);
        assertThat(b2List).isEmpty();

        // Business 2 cannot query using Business 1's ID
        assertThatThrownBy(() -> inventoryService.searchProducts(biz1.id(), null, null, false))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot fetch Business 1's product by ID
        assertThatThrownBy(() -> inventoryService.getProductById(b1Product.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot fetch Business 1's stock movements
        assertThatThrownBy(() -> inventoryService.getProductMovements(b1Product.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Cross-business access denied");

        // Business 2 cannot adjust Business 1's stock
        assertThatThrownBy(() -> inventoryService.adjustStock(new StockAdjustmentDto(
                b1Product.id(), 10, "Unauthorized cross-business adjustment", owner2.id()
        ))).isInstanceOf(SecurityException.class);

        // Business 2 cannot archive Business 1's product
        assertThatThrownBy(() -> inventoryService.archiveProduct(b1Product.id()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectOperationsWhenUnauthenticatedOrLocked() {
        // 1. Unauthenticated
        SessionContext.clearSession();

        ProductCreateDto dto = new ProductCreateDto(
                biz1.id(), null, "Ghost Item", null, null, "pcs",
                Money.ofBdt(10.00), Money.ofBdt(15.00), 5, 0, null
        );
        assertThatThrownBy(() -> inventoryService.createProduct(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("User is not authenticated");

        // 2. Locked
        SessionContext.setSession(biz1, owner1);
        SessionContext.lock();

        assertThatThrownBy(() -> inventoryService.createProduct(dto))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("session is locked");
    }

    @Test
    void shouldAllowStaffToAdjustStockAndSearchButRestrictArchiveToOwner() {
        // Staff cashier logs in
        SessionContext.setSession(biz1, staff1);

        Product staffProduct = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Biscuits 50g", null, null, "pkt",
                Money.ofBdt(15.00), Money.ofBdt(20.00), 10, 50, null
        ));
        assertThat(staffProduct).isNotNull();

        // Staff can adjust stock with reason
        StockMovement adjustment = inventoryService.adjustStock(new StockAdjustmentDto(
                staffProduct.id(), -2, "Damaged in shelf stacking", staff1.id()
        ));
        assertThat(adjustment.quantity()).isEqualTo(-2);
        assertThat(inventoryService.getCurrentStock(staffProduct.id())).isEqualTo(48);

        // Non-owner staff attempting to archive product must be blocked
        assertThatThrownBy(() -> inventoryService.archiveProduct(staffProduct.id()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Action requires OWNER role permissions");
    }

    @Test
    void shouldPersistImagePathAndSupportCategoryProductAndMovementCrud() {
        SessionContext.setSession(biz1, owner1);

        var cat = inventoryService.createCategory(biz1.id(), "Staples", "Rice and grains");
        var renamedCat = inventoryService.updateCategory(cat.id(), "Daily Staples", "Rice, flour, oil");
        assertThat(renamedCat.name()).isEqualTo("Daily Staples");

        Product created = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(),
                renamedCat.id(),
                "Miniket Rice 5kg",
                "8941100991122",
                "RICE-5KG",
                "pkt",
                Money.ofBdt(340.00),
                Money.ofBdt(385.00),
                5,
                15,
                "Initial opening",
                "/com/smartkhata/ui/images/pos/rice_bowl.png"
        ));
        assertThat(created.imagePath()).isEqualTo("/com/smartkhata/ui/images/pos/rice_bowl.png");

        Product updated = inventoryService.updateProduct(new ProductUpdateDto(
                created.id(),
                renamedCat.id(),
                "Miniket Rice 5kg Premium",
                "8941100991122",
                "RICE-5KG",
                "pkt",
                Money.ofBdt(345.00),
                Money.ofBdt(395.00),
                6,
                "/com/smartkhata/ui/images/pos/flour_sack.png"
        ));
        assertThat(updated.imagePath()).isEqualTo("/com/smartkhata/ui/images/pos/flour_sack.png");

        List<ProductListItemDto> results = inventoryService.searchProducts(biz1.id(), "Miniket", null, false);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).imagePath()).isEqualTo("/com/smartkhata/ui/images/pos/flour_sack.png");

        List<StockMovement> movements = inventoryService.getProductMovements(created.id());
        assertThat(movements).hasSize(1);
        inventoryService.updateStockMovementNotes(movements.get(0).id(), "Verified shelf count");
        assertThat(inventoryService.getProductMovements(created.id()).get(0).notes()).isEqualTo("Verified shelf count");

        // Deleting category unlinks product cleanly
        inventoryService.deleteCategory(renamedCat.id());
        assertThat(inventoryService.getProductById(created.id()).orElseThrow().categoryId()).isNull();

        // Deleting product with only OPENING stock succeeds cleanly
        inventoryService.deleteProduct(created.id());
        assertThat(inventoryService.getProductById(created.id())).isEmpty();
    }

    @Test
    void shouldRejectDeletingProductWithAdjustmentHistory() {
        SessionContext.setSession(biz1, owner1);
        Product prod = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Linked Adjustment Product", null, null, "pcs",
                Money.ofBdt(50.00), Money.ofBdt(65.00), 2, 10, null, null
        ));
        inventoryService.adjustStock(new StockAdjustmentDto(prod.id(), 5, "Restock audit", owner1.id()));

        assertThatThrownBy(() -> inventoryService.deleteProduct(prod.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Please Archive it instead");
    }

    @Test
    void shouldComputeTotalActiveLowStockOutOfStockAndValuationAccurately() {
        SessionContext.setSession(biz1, owner1);

        // 1. Healthy stock (stock 20 > threshold 5) -> Value = 2,000 BDT
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Healthy Oil", "111", "SKU-1", "pkt",
                Money.ofBdt(100.00), Money.ofBdt(120.00), 5, 20, null
        ));

        // 2. Low stock (stock 3 <= threshold 5) -> Value = 150 BDT
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Low Salt", "222", "SKU-2", "pkt",
                Money.ofBdt(50.00), Money.ofBdt(60.00), 5, 3, null
        ));

        // 3. Out of stock (stock 0 <= 0, threshold 5) -> Value = 0 BDT
        inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Zero Sugar", "333", "SKU-3", "kg",
                Money.ofBdt(40.00), Money.ofBdt(55.00), 5, 0, null
        ));

        // 4. Archived item (excluded from active/low/out-of-stock/valuation, included in totalProducts)
        Product arch = inventoryService.createProduct(new ProductCreateDto(
                biz1.id(), null, "Archived Flour", "444", "SKU-4", "bag",
                Money.ofBdt(500.00), Money.ofBdt(550.00), 5, 10, null
        ));
        inventoryService.archiveProduct(arch.id());

        InventorySummaryDto summary = inventoryService.getInventorySummary(biz1.id());
        assertThat(summary.totalProducts()).isEqualTo(4);
        assertThat(summary.totalActiveProducts()).isEqualTo(3);
        assertThat(summary.lowStockAlertCount()).isEqualTo(2); // Low Salt + Zero Sugar (<= threshold)
        assertThat(summary.outOfStockCount()).isEqualTo(1);    // Zero Sugar (<= 0)
        assertThat(summary.totalStockQuantity()).isEqualTo(23);
        assertThat(summary.totalInventoryValue()).isEqualTo(Money.ofBdt(2150.00));
    }
}


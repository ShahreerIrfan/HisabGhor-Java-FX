package com.smartkhata.ui;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.ProductCreateDto;
import com.smartkhata.model.dto.ProductSupplierUpsertDto;
import com.smartkhata.model.dto.ProductSupplierViewDto;
import com.smartkhata.model.dto.ProductUpdateDto;
import com.smartkhata.model.dto.SupplierCreateDto;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.Category;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteSupplierService;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProductFormModalTest {

    @AfterEach
    void cleanupDb() {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            stmt.execute("PRAGMA journal_mode = DELETE;");
        } catch (Exception ignored) {
        }
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldRenderTwoTabsCalculateLiveAnalyticsAndValidatePricingErrors() throws Exception {
        runOnFxThread(() -> {
            Category oilCat = new Category(1L, 1L, "Oil", "Cooking oils", null);
            AtomicReference<ProductFormModal.SavePayload> submitted = new AtomicReference<>();
            AtomicBoolean closed = new AtomicBoolean(false);

            ProductFormModal.ModalParts parts = ProductFormModal.buildModalParts(
                    1L,
                    null,
                    "Add New Product (নতুন পণ্য)",
                    "Create a product with TP, MRP, selling price, stock settings, and linked suppliers.",
                    "Save Product",
                    List.of(oilCat),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    () -> closed.set(true),
                    (payload, onError, resetUi) -> {
                        submitted.set(payload);
                        resetUi.run();
                    }
            );

            Scene scene = ProductFormModal.buildStyledScene(parts.rootPane(), parts.btnSave(), () -> closed.set(true));
            assertThat(scene.getStylesheets()).isNotEmpty();

            // Tab titles & initial empty analytics placeholders (never misleading zero profit)
            assertThat(parts.tabBtnDetails().getText()).isEqualTo("Details & Pricing");
            assertThat(parts.tabBtnSuppliers().getText()).isEqualTo("Suppliers & Trade Prices (0)");
            assertThat(parts.lblEstProfit().getText()).isEqualTo("—");
            assertThat(parts.lblEstMargin().getText()).isEqualTo("—");
            assertThat(parts.lblEstMarkup().getText()).isEqualTo("—");

            // Image preview reacts dynamically to product name keyword matching and Clear restores placeholder
            assertThat(parts.imgEmptyPlaceholder().isVisible()).isTrue();
            parts.txtName().setText("Teer Soybean Oil 5L");
            assertThat(parts.imgEmptyPlaceholder().isVisible()).isFalse();
            assertThat(parts.imgPreview().isVisible()).isTrue();
            assertThat(parts.imgPreview().getImage()).isNotNull();
            assertThat(parts.imgPreview().getImage().isError()).isFalse();

            // Selecting preset then clicking Clear resets back cleanly
            parts.cmbImagePreset().getSelectionModel().select("Rice Bowl (চাল)");
            assertThat(parts.selectedImagePathRef().get()).contains("rice_bowl.png");
            parts.btnClearImage().fire();
            assertThat(parts.selectedImagePathRef().get()).isNull();
            assertThat(parts.cmbImagePreset().getValue()).isEqualTo(ProductFormModal.AUTO_IMAGE_LABEL);

            // Verify responsive layout collapses cleanly on narrow container widths (< 780px)
            javafx.scene.layout.GridPane contentGrid = (javafx.scene.layout.GridPane) parts.bodyScroll().getContent();
            contentGrid.resize(960.0, 620.0);
            assertThat(contentGrid.getColumnConstraints()).hasSize(2);
            contentGrid.resize(700.0, 620.0);
            assertThat(contentGrid.getColumnConstraints()).hasSize(1);
            contentGrid.resize(960.0, 620.0);
            assertThat(contentGrid.getColumnConstraints()).hasSize(2);

            // Live Profit/Margin/Markup calculation using Cost (not MRP)
            parts.txtTp().setText("700");
            parts.txtCost().setText("720");
            parts.txtMrp().setText("900");
            parts.txtSelling().setText("820");
            assertThat(parts.lblEstProfit().getText()).contains("100");
            assertThat(parts.lblEstMargin().getText()).isEqualTo("12.2%");
            assertThat(parts.lblEstMarkup().getText()).isEqualTo("13.9%");
            assertThat(parts.warnBanner().isVisible()).isFalse();

            // Selling below cost triggers warning banner
            parts.txtSelling().setText("650");
            assertThat(parts.warnBanner().isVisible()).isTrue();
            assertThat(parts.lblPriceWarning().getText()).contains("lower than unit cost");

            // Selling above MRP triggers warning banner
            parts.txtSelling().setText("950");
            assertThat(parts.warnBanner().isVisible()).isTrue();
            assertThat(parts.lblPriceWarning().getText()).contains("exceeds MRP");

            // Invalid/negative price validation blocks submit with inline error
            parts.txtSelling().setText("-25");
            parts.btnSave().fire();
            assertThat(parts.errorBanner().isVisible()).isTrue();
            assertThat(parts.lblError().getText()).contains("cannot be negative");
            assertThat(submitted.get()).isNull();

            // Blank product name blocks submit cleanly
            parts.txtSelling().setText("820");
            parts.txtName().setText("   ");
            parts.btnSave().fire();
            assertThat(parts.errorBanner().isVisible()).isTrue();
            assertThat(parts.lblError().getText()).contains("Product Name is required");
            assertThat(submitted.get()).isNull();

            // Valid product submission preserves separated TP, Cost, MRP, Selling, Units, Opening Stock
            parts.txtName().setText("Teer Soybean Oil 5L");
            parts.cmbCategory().getSelectionModel().select(oilCat);
            parts.txtBarcode().setText("894110012345");
            parts.txtSku().setText("OIL-TEER-5L");
            parts.cmbBaseUnit().setValue("pcs");
            parts.cmbPurchaseUnit().setValue("carton");
            parts.txtPackSize().setText("12");
            parts.txtThreshold().setText("6");
            parts.txtOpeningStock().setText("24");
            parts.btnSave().fire();

            assertThat(submitted.get()).isNotNull();
            ProductCreateDto dto = submitted.get().createDto();
            assertThat(dto.name()).isEqualTo("Teer Soybean Oil 5L");
            assertThat(dto.categoryId()).isEqualTo(1L);
            assertThat(dto.tradePrice()).isEqualTo(Money.ofBdt(700));
            assertThat(dto.costPrice()).isEqualTo(Money.ofBdt(720));
            assertThat(dto.mrp()).isEqualTo(Money.ofBdt(900));
            assertThat(dto.sellingPrice()).isEqualTo(Money.ofBdt(820));
            assertThat(dto.unit()).isEqualTo("pcs");
            assertThat(dto.purchaseUnit()).isEqualTo("carton");
            assertThat(dto.packSize()).isEqualTo(12);
            assertThat(dto.lowStockThreshold()).isEqualTo(6);
            assertThat(dto.openingStock()).isEqualTo(24);
            assertThat(parts.lblPackHint().getText()).isEqualTo("1 carton = 12 pcs");

            parts.btnCancel().fire();
            assertThat(closed.get()).isTrue();
        });
    }

    @Test
    void shouldManageMultipleSuppliersEnforceSinglePreferredAndRejectDuplicates() throws Exception {
        runOnFxThread(() -> {
            Supplier supA = new Supplier(10L, 1L, "Meghna Oil Mills", "Shahidul", "01811", "Dhaka", Money.ZERO, false, "", null, null, "SUP-01", null, "Net 15", 15, true);
            Supplier supB = new Supplier(20L, 1L, "City Oil Traders", "Karim", "01711", "Chattogram", Money.ZERO, false, "", null, null, "SUP-02", null, "Cash", 0, true);

            ProductFormModal.ModalParts parts = ProductFormModal.buildModalParts(
                    1L,
                    null,
                    "Add New Product (নতুন পণ্য)",
                    "Subtitle",
                    "Save Product",
                    List.of(),
                    List.of(supA, supB),
                    List.of(),
                    null,
                    null,
                    () -> {},
                    (payload, onError, resetUi) -> resetUi.run()
            );

            // Switch to Suppliers & Trade Prices via Right Card shortcut
            parts.btnJumpToSuppliers().fire();
            assertThat(parts.draftLinks()).isEmpty();

            // Add Supplier A (Preferred, TP 720)
            parts.cmbSupplierPicker().getSelectionModel().select(supA);
            parts.txtSupTp().setText("720");
            parts.txtSupVendorSku().setText("MGN-5L");
            parts.txtSupUnit().setText("carton");
            parts.txtSupPack().setText("4");
            parts.txtSupMoq().setText("2");
            parts.txtSupLeadDays().setText("3");
            parts.chkSupPreferred().setSelected(true);
            parts.btnAddUpdateSupplier().fire();

            assertThat(parts.errorBanner().isVisible()).isFalse();
            assertThat(parts.draftLinks()).hasSize(1);
            assertThat(parts.tabBtnSuppliers().getText()).isEqualTo("Suppliers & Trade Prices (1)");
            assertThat(parts.lblSupInfoCount().getText()).isEqualTo("1 supplier linked");

            // Reject duplicate Supplier A while in Add mode
            parts.cmbSupplierPicker().getSelectionModel().select(supA);
            parts.txtSupTp().setText("730");
            parts.btnAddUpdateSupplier().fire();
            assertThat(parts.errorBanner().isVisible()).isTrue();
            assertThat(parts.lblError().getText()).contains("already linked");
            assertThat(parts.draftLinks()).hasSize(1);

            // Add Supplier B (Preferred, TP 745) -> clears Preferred from Supplier A without altering Supplier A's TP
            parts.cmbSupplierPicker().getSelectionModel().select(supB);
            parts.txtSupTp().setText("745");
            parts.txtSupMoq().setText("5");
            parts.chkSupPreferred().setSelected(true);
            parts.btnAddUpdateSupplier().fire();

            assertThat(parts.errorBanner().isVisible()).isFalse();
            assertThat(parts.draftLinks()).hasSize(2);
            assertThat(parts.tabBtnSuppliers().getText()).isEqualTo("Suppliers & Trade Prices (2)");
            assertThat(parts.lblSupInfoCount().getText()).isEqualTo("2 suppliers linked");

            ProductFormModal.DraftSupplierLink first = parts.draftLinks().get(0);
            ProductFormModal.DraftSupplierLink second = parts.draftLinks().get(1);
            assertThat(first.supplierId()).isEqualTo(10L);
            assertThat(first.tradePrice()).isEqualTo(Money.ofBdt(720));
            assertThat(first.isPreferred()).isFalse();
            assertThat(second.supplierId()).isEqualTo(20L);
            assertThat(second.tradePrice()).isEqualTo(Money.ofBdt(745));
            assertThat(second.isPreferred()).isTrue();
        });
    }

    @Test
    void shouldPrepopulateAndPersistEditProductAndSupplierLinksAgainstSqlite(@TempDir Path tempDir) throws Exception {
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDir.resolve("product_modal_e2e.db").toAbsolutePath());
        DatabaseManager.initializeDatabase();

        long bizId = seedBusinessAndSession();
        InventoryService inventoryService = new SqliteInventoryService();
        SupplierService supplierService = new SqliteSupplierService();

        Category cat = inventoryService.createCategory(bizId, "Oil", "Edible Oils");
        Supplier s1 = supplierService.createSupplier(new SupplierCreateDto(bizId, "Bashundhara Oil", "Kamal", "0171100", "Dhaka", Money.ZERO, ""));
        Supplier s2 = supplierService.createSupplier(new SupplierCreateDto(bizId, "Fresh Mills", "Jamal", "0181100", "Narayanganj", Money.ZERO, ""));

        Product created = inventoryService.createProduct(new ProductCreateDto(
                bizId, cat.id(), "5 Liter Soyabean Oil", "646515216", "SKU-OIL-00001",
                "pkt", Money.ofBdt(780), Money.ofBdt(850), 5, 19,
                "Opening", null, Money.ofBdt(760), Money.ofBdt(880), "box", 4
        ));
        inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                null, bizId, created.id(), s1.id(), Money.ofBdt(760), "BS-OIL", 2, "box", 4, true, 2, true
        ));
        inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                null, bizId, created.id(), s2.id(), Money.ofBdt(775), "FR-OIL", 1, "box", 4, false, 3, true
        ));

        runOnFxThread(() -> {
            List<ProductSupplierViewDto> existingLinks = inventoryService.getProductSuppliers(created.id(), true);
            AtomicReference<Product> updatedRef = new AtomicReference<>();

            ProductFormModal.ModalParts editParts = ProductFormModal.buildModalParts(
                    bizId,
                    created,
                    "Edit Product (পণ্য তথ্য পরিবর্তন)",
                    "Edit mode",
                    "Update Product",
                    inventoryService.getCategories(bizId),
                    List.of(s1, s2),
                    existingLinks,
                    null,
                    null,
                    () -> {},
                    (payload, onError, resetUi) -> {
                        Product upd = inventoryService.updateProduct(payload.updateDto());
                        for (Long delId : payload.deletedMappingIds()) {
                            inventoryService.removeProductSupplier(delId);
                        }
                        for (ProductFormModal.DraftSupplierLink link : payload.supplierLinks()) {
                            inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                                    link.mappingId(), bizId, upd.id(), link.supplierId(),
                                    link.tradePrice(), link.supplierProductCode(), link.minOrderQty(),
                                    link.purchaseUnit(), link.packSize(), link.isPreferred(),
                                    link.leadTimeDays(), link.isActive()
                            ));
                        }
                        updatedRef.set(upd);
                        resetUi.run();
                    }
            );

            assertThat(editParts.btnSave().getText()).isEqualTo("Update Product");
            assertThat(editParts.txtName().getText()).isEqualTo("5 Liter Soyabean Oil");
            assertThat(editParts.txtTp().getText()).isEqualTo("760.00");
            assertThat(editParts.txtCost().getText()).isEqualTo("780.00");
            assertThat(editParts.txtMrp().getText()).isEqualTo("880.00");
            assertThat(editParts.txtSelling().getText()).isEqualTo("850.00");
            assertThat(editParts.tabBtnSuppliers().getText()).isEqualTo("Suppliers & Trade Prices (2)");

            // Update default selling price and save
            editParts.txtSelling().setText("865");
            editParts.btnSave().fire();

            assertThat(editParts.errorBanner().isVisible()).isFalse();
            assertThat(updatedRef.get()).isNotNull();
            assertThat(updatedRef.get().sellingPrice()).isEqualTo(Money.ofBdt(865));
            assertThat(inventoryService.getCurrentStock(created.id())).isEqualTo(19);
            assertThat(inventoryService.getProductSuppliers(created.id(), true)).hasSize(2);
        });
    }

    private static long seedBusinessAndSession() {
        final Business[] bizHolder = new Business[1];
        final User[] userHolder = new User[1];
        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Modal Test Store', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    bizId = rs.getLong(1);
                }
            }
            bizHolder[0] = new Business(bizId, "Modal Test Store", null, "01700", "Dhaka", null, "BDT", null, null);
            long uid;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) VALUES (?, 'owner', 'Owner', 'x', 'y', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, bizId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    uid = rs.getLong(1);
                }
            }
            userHolder[0] = new User(uid, bizId, "owner", "Owner", "x", "y", UserRole.OWNER, true, null, null, null);
        });
        SessionContext.setSession(bizHolder[0], userHolder[0]);
        return bizHolder[0].id();
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Runnable wrapped = () -> {
            try {
                action.run();
            } catch (Throwable t) {
                err.set(t);
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
        if (err.get() != null) {
            throw new AssertionError("JavaFX test failed", err.get());
        }
    }
}

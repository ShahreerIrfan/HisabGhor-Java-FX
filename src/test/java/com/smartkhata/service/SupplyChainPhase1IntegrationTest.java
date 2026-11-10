package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqliteSupplierService;
import com.smartkhata.util.PricingCalculator;
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
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 1 Acceptance Tests:
 * - Test 1: Multiple suppliers mapped to the same product with independent Trade Prices (TP)
 * - Preferred supplier uniqueness per product
 * - Product Master TP / MRP / Default Selling Price / Purchase Unit / Pack Conversion
 * - Gross profit, Margin %, Markup %, and pricing warnings (below cost & above MRP)
 * - Extended Supplier fields (code, email, payment terms, credit period, active status)
 */
class SupplyChainPhase1IntegrationTest {

    private InventoryService inventoryService;
    private SupplierService supplierService;
    private Business biz;
    private User owner;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("scm_phase1_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        inventoryService = new SqliteInventoryService();
        supplierService = new SqliteSupplierService();

        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Bismillah Grocery', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    bizId = rs.getLong(1);
                }
            }
            biz = new Business(bizId, "Bismillah Grocery", null, null, null, null, "BDT", null, null);

            long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) VALUES (?, 'owner1', 'Md. Rahim', 'x', 'y', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, bizId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }
            owner = new User(userId, bizId, "owner1", "Md. Rahim", "x", "y", UserRole.OWNER, true, null, null, null);
        });

        SessionContext.setSession(biz, owner);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void test1_shouldMapMultipleSuppliersToSingleProductWithIndependentTradePrices() {
        Supplier supplierA = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "Meghna Distributors", "Karim Mia", "01711000111", "Dhaka",
                Money.ofBdt(5000), "Primary distributor", null, "orders@meghna.com", "Net 15", 15, true
        ));
        Supplier supplierB = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "City Wholesale Hub", "Jamal Uddin", "01811000222", "Chittagong",
                Money.ZERO, "Backup distributor", "SUP-CITY-01", "sales@city.com", "COD", 0, true
        ));

        assertThat(supplierA.supplierCode()).isNotBlank();
        assertThat(supplierA.paymentTerms()).isEqualTo("Net 15");
        assertThat(supplierA.creditPeriodDays()).isEqualTo(15);
        assertThat(supplierB.supplierCode()).isEqualTo("SUP-CITY-01");

        Product soap = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Lux Soap 100g", "8941100555", "SKU-LUX-100",
                "pcs", Money.ofBdt(40), Money.ofBdt(48), 10, 0, "Opening", null,
                Money.ofBdt(40), Money.ofBdt(50), "box", 12
        ));

        // Link Supplier A (TP = 450 per box of 12 pcs) as Preferred
        ProductSupplier linkA = inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                null, biz.id(), soap.id(), supplierA.id(),
                Money.ofBdt(450), "MEG-LUX-12", 2, "box", 12, true, 2, true
        ));

        // Link Supplier B (TP = 470 per box)
        ProductSupplier linkB = inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                null, biz.id(), soap.id(), supplierB.id(),
                Money.ofBdt(470), "CIT-LUX-12", 1, "box", 12, false, 1, true
        ));

        assertThat(linkA.tradePrice()).isEqualTo(Money.ofBdt(450));
        assertThat(linkB.tradePrice()).isEqualTo(Money.ofBdt(470));

        // Update Supplier A's TP to 460 and verify Supplier B's TP (470) is NEVER overwritten!
        inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                linkA.id(), biz.id(), soap.id(), supplierA.id(),
                Money.ofBdt(460), "MEG-LUX-12", 2, "box", 12, true, 2, true
        ));

        List<ProductSupplierViewDto> productSuppliers = inventoryService.getProductSuppliers(soap.id(), true);
        assertThat(productSuppliers).hasSize(2);
        ProductSupplierViewDto viewA = productSuppliers.stream().filter(s -> s.supplierId() == supplierA.id()).findFirst().orElseThrow();
        ProductSupplierViewDto viewB = productSuppliers.stream().filter(s -> s.supplierId() == supplierB.id()).findFirst().orElseThrow();

        assertThat(viewA.tradePrice()).isEqualTo(Money.ofBdt(460));
        assertThat(viewA.isPreferred()).isTrue();
        assertThat(viewB.tradePrice()).isEqualTo(Money.ofBdt(470));
        assertThat(viewB.isPreferred()).isFalse();

        // Switching preferred to Supplier B automatically clears preferred from Supplier A
        inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                linkB.id(), biz.id(), soap.id(), supplierB.id(),
                Money.ofBdt(470), "CIT-LUX-12", 1, "box", 12, true, 1, true
        ));
        List<ProductSupplierViewDto> afterPrefSwitch = inventoryService.getProductSuppliers(soap.id(), true);
        assertThat(afterPrefSwitch.stream().filter(ProductSupplierViewDto::isPreferred).map(ProductSupplierViewDto::supplierId))
                .containsExactly(supplierB.id());

        // Supplier view returns linked products properly
        assertThat(supplierService.getSupplierProducts(supplierA.id())).hasSize(1);
    }

    @Test
    void shouldCalculateProfitMarginMarkupAndPricingWarningsAccurately() {
        Money cost = Money.ofBdt(80);
        Money mrp = Money.ofBdt(110);
        Money selling = Money.ofBdt(100);

        assertThat(PricingCalculator.calculateProfitAmount(selling, cost)).isEqualTo(Money.ofBdt(20));
        assertThat(PricingCalculator.calculateProfitMarginPercent(selling, cost)).isCloseTo(20.0, within(0.01));
        assertThat(PricingCalculator.calculateMarkupPercent(selling, cost)).isCloseTo(25.0, within(0.01));
        assertThat(PricingCalculator.isSellingBelowCost(selling, cost)).isFalse();
        assertThat(PricingCalculator.isSellingAboveMrp(selling, mrp)).isFalse();

        // Warnings trigger appropriately when selling below cost or above MRP
        assertThat(PricingCalculator.isSellingBelowCost(Money.ofBdt(75), cost)).isTrue();
        assertThat(PricingCalculator.isSellingAboveMrp(Money.ofBdt(115), mrp)).isTrue();

        // Product persisted retains distinct TP, MRP, Selling Price, Purchase Unit & Pack Size
        Product p = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Soyabean Oil 5L", "8941100999", "OIL-5L",
                "bottle", Money.ofBdt(780), Money.ofBdt(840), 5, 10, "Open", null,
                Money.ofBdt(775), Money.ofBdt(860), "carton", 4
        ));
        Product reloaded = inventoryService.getProductById(p.id()).orElseThrow();
        assertThat(reloaded.tradePrice()).isEqualTo(Money.ofBdt(775));
        assertThat(reloaded.mrp()).isEqualTo(Money.ofBdt(860));
        assertThat(reloaded.sellingPrice()).isEqualTo(Money.ofBdt(840));
        assertThat(reloaded.costPrice()).isEqualTo(Money.ofBdt(780));
        assertThat(reloaded.purchaseUnit()).isEqualTo("carton");
        assertThat(reloaded.packSize()).isEqualTo(4);
    }
}

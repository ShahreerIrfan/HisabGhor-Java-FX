package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.*;
import com.smartkhata.service.impl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 Acceptance Tests:
 * - Test 3: Partial GRN (6 then 4 of 10 ordered -> PARTIALLY_RECEIVED -> FULLY_RECEIVED)
 * - Test 4: Moving Weighted Average Cost (10 @ 100 + 10 @ 120 -> 110.00 + cost history)
 * - Test 5: Rejected and Damaged stock excluded from stock & inventory valuation
 * - Test 7: Pack unit conversion (2 boxes x 12 pcs = 24 base pcs)
 * - Test 9: Controlled GRN Reversal & Purchase Return reducing stock & payable cleanly
 */
class SupplyChainPhase3GrnIntegrationTest {

    private GrnService grnService;
    private PurchaseOrderService poService;
    private InventoryService inventoryService;
    private SupplierService supplierService;
    private Business biz;
    private User owner;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("scm_phase3_grn_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        grnService = new SqliteGrnService();
        poService = new SqlitePurchaseOrderService();
        inventoryService = new SqliteInventoryService();
        supplierService = new SqliteSupplierService();

        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('GRN Mart', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    bizId = rs.getLong(1);
                }
            }
            biz = new Business(bizId, "GRN Mart", null, null, null, null, "BDT", null, null);

            long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) VALUES (?, 'owner', 'Owner', 'x', 'y', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, bizId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }
            owner = new User(userId, bizId, "owner", "Owner", "x", "y", UserRole.OWNER, true, null, null, null);
        });
        SessionContext.setSession(biz, owner);
    }

    @AfterEach
    void tearDown() {
        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void test3_partialGrnUpdatesPoStatusesAcrossMultipleReceipts() {
        Supplier sup = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "Pran Foods", "Ali", "01700001111", "Dhaka", Money.ZERO, null));
        Product juice = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Mango Juice 250ml", "894111222", "SKU-JUICE",
                "pcs", Money.ofBdt(20), Money.ofBdt(25), 10, 0, "Open", null,
                Money.ofBdt(20), Money.ofBdt(25), "box", 10));

        PurchaseOrder po = poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                null, biz.id(), sup.id(), LocalDate.now().plusDays(1),
                Money.ZERO, Money.ZERO, Money.ZERO, null, null, true, owner.id(),
                List.of(new PurchaseOrderItemCreateDto(juice.id(), 10, "box", 10, Money.ofBdt(200), Money.ZERO, Money.ZERO, null))
        ));
        PurchaseOrderDetailDto poDetail = poService.getPurchaseOrderDetail(po.id()).orElseThrow();
        long poItemId = poDetail.items().get(0).id();

        // 1. Receive 6 boxes (with pack_size = 10 -> 60 pcs)
        GoodsReceiptNote grn1 = grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), po.id(), sup.id(), Money.ZERO, Money.ZERO, "CH-01", "Part 1", "grn-part-1", owner.id(),
                List.of(new GrnItemCreateDto(poItemId, juice.id(), 6, 6, 0, 0, "box", 10, Money.ofBdt(200), Money.ZERO, null))
        ));
        assertThat(grn1.status()).isEqualTo(GrnStatus.POSTED);
        assertThat(inventoryService.getCurrentStock(juice.id())).isEqualTo(60);
        assertThat(poService.getPurchaseOrderDetail(po.id()).orElseThrow().order().status())
                .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

        // Idempotent replay with same key must NOT duplicate stock!
        GoodsReceiptNote replay = grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), po.id(), sup.id(), Money.ZERO, Money.ZERO, "CH-01", "Part 1", "grn-part-1", owner.id(),
                List.of(new GrnItemCreateDto(poItemId, juice.id(), 6, 6, 0, 0, "box", 10, Money.ofBdt(200), Money.ZERO, null))
        ));
        assertThat(replay.id()).isEqualTo(grn1.id());
        assertThat(inventoryService.getCurrentStock(juice.id())).isEqualTo(60);

        // Over-receipt beyond ordered qty (trying 5 when only 4 left) rejected cleanly
        assertThatThrownBy(() -> grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), po.id(), sup.id(), Money.ZERO, Money.ZERO, "CH-02", null, "grn-over", owner.id(),
                List.of(new GrnItemCreateDto(poItemId, juice.id(), 5, 5, 0, 0, "box", 10, Money.ofBdt(200), Money.ZERO, null))
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Over-receipt prevented");

        // 2. Receive remaining 4 boxes -> PO transitions to FULLY_RECEIVED and stock = 100 pcs
        grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), po.id(), sup.id(), Money.ZERO, Money.ZERO, "CH-03", "Part 2", "grn-part-2", owner.id(),
                List.of(new GrnItemCreateDto(poItemId, juice.id(), 4, 4, 0, 0, "box", 10, Money.ofBdt(200), Money.ZERO, null))
        ));
        assertThat(inventoryService.getCurrentStock(juice.id())).isEqualTo(100);
        assertThat(poService.getPurchaseOrderDetail(po.id()).orElseThrow().order().status())
                .isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
    }

    @Test
    void test4_and_test7_weightedAverageCostAndPackUnitConversion() {
        Supplier sup = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "Square Trading", "Hasan", "01800001111", "Dhaka", Money.ZERO, null));
        Product oil = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Mustard Oil 500ml", "894222333", "SKU-MOIL",
                "pcs", Money.ofBdt(100), Money.ofBdt(130), 2, 10, "Opening 10 @ 100", null,
                Money.ofBdt(100), Money.ofBdt(135), "pcs", 1));

        // 10 existing units @ 100 BDT + 10 accepted units @ 120 BDT -> Avg Cost = 110.00 BDT
        grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), null, sup.id(), Money.ZERO, Money.ZERO, "DIR-01", "Weighted avg batch", "grn-wac-1", owner.id(),
                List.of(new GrnItemCreateDto(null, oil.id(), 10, 10, 0, 0, "pcs", 1, Money.ofBdt(120), Money.ZERO, null))
        ));
        Product updatedOil = inventoryService.getProductById(oil.id()).orElseThrow();
        assertThat(inventoryService.getCurrentStock(oil.id())).isEqualTo(20);
        assertThat(updatedOil.costPrice()).isEqualTo(Money.ofBdt(110));
        assertThat(updatedOil.lastPurchasePrice()).isEqualTo(Money.ofBdt(120));
        assertThat(grnService.getProductCostHistory(oil.id())).hasSize(1);

        // Test 7: Pack unit conversion (2 boxes @ 12 pcs/box @ BDT 1,200/box -> +24 pcs at 100 BDT/pc)
        Product soap = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Dettol Soap", "894333444", "SKU-SOAP",
                "pcs", Money.ofBdt(90), Money.ofBdt(120), 5, 0, "Open", null,
                Money.ofBdt(1200), Money.ofBdt(125), "box", 12));
        grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), null, sup.id(), Money.ZERO, Money.ZERO, "DIR-02", "Box to base pcs", "grn-pack-1", owner.id(),
                List.of(new GrnItemCreateDto(null, soap.id(), 2, 2, 0, 0, "box", 12, Money.ofBdt(1200), Money.ZERO, null))
        ));
        assertThat(inventoryService.getCurrentStock(soap.id())).isEqualTo(24);
        assertThat(inventoryService.getProductById(soap.id()).orElseThrow().costPrice()).isEqualTo(Money.ofBdt(100));
    }

    @Test
    void test5_and_test9_rejectedDamagedExclusionAndReversalAndReturn() {
        Supplier sup = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "Bashundhara Mills", "Tariq", "01900001111", "Dhaka", Money.ofBdt(5000), null));
        Product flour = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Atta 2kg", "894444555", "SKU-ATTA",
                "pkt", Money.ofBdt(110), Money.ofBdt(130), 2, 0, "Open"));

        // Test 5: Received 10, Accepted 8, Rejected 1, Damaged 1 -> Only 8 pkt enter stock & valuation!
        GoodsReceiptNote grn = grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), null, sup.id(), Money.ZERO, Money.ZERO, "CH-DMG", null, "grn-dmg-1", owner.id(),
                List.of(new GrnItemCreateDto(null, flour.id(), 10, 8, 1, 1, "pkt", 1, Money.ofBdt(110), Money.ZERO, "2 bad packs"))
        ));
        assertThat(inventoryService.getCurrentStock(flour.id())).isEqualTo(8);
        assertThat(grn.totalCost()).isEqualTo(Money.ofBdt(880)); // 8 * 110, NOT 10 * 110

        // Test 9a: Authorized Purchase Return of 3 pkt (with supplier payable debit) -> stock drops 8 -> 5, payable drops 5000 -> 4670
        grnService.createPurchaseReturn(new PurchaseReturnCreateDto(
                biz.id(), sup.id(), grn.id(), true, "Damaged seam discovered after stocking", owner.id(),
                List.of(new PurchaseReturnItemCreateDto(null, flour.id(), 3, Money.ofBdt(110), null))
        ));
        assertThat(inventoryService.getCurrentStock(flour.id())).isEqualTo(5);
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ofBdt(4670));

        // GRN full reversal when remaining stock < 8 is safely blocked
        assertThatThrownBy(() -> grnService.reverseGrn(grn.id(), "Cannot reverse partial sold", owner.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only has 5 units left");

        // Fresh GRN reversed cleanly restores stock to exact prior level without deleting records
        GoodsReceiptNote grn2 = grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), null, sup.id(), Money.ZERO, Money.ZERO, "CH-REV", null, "grn-rev-2", owner.id(),
                List.of(new GrnItemCreateDto(null, flour.id(), 5, 5, 0, 0, "pkt", 1, Money.ofBdt(110), Money.ZERO, null))
        ));
        assertThat(inventoryService.getCurrentStock(flour.id())).isEqualTo(10);
        grnService.reverseGrn(grn2.id(), "Wrong supplier entry", owner.id());
        assertThat(inventoryService.getCurrentStock(flour.id())).isEqualTo(5);
        assertThat(grnService.getGrnDetail(grn2.id()).orElseThrow().grn().status()).isEqualTo(GrnStatus.REVERSED);
    }
}


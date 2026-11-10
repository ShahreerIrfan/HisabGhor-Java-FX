package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.PurchaseOrderService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import com.smartkhata.service.impl.SqliteInventoryService;
import com.smartkhata.service.impl.SqlitePurchaseOrderService;
import com.smartkhata.service.impl.SqliteSupplierService;
import com.smartkhata.util.PurchaseOrderPdfExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 Acceptance Tests:
 * - Test 2: Creating and confirming a Purchase Order does NOT increase inventory or affect AP payable balance.
 * - Supplier-specific Trade Price auto-resolution from product_suppliers.
 * - Draft edit, cancel, close rules & PO PDF export.
 */
class SupplyChainPhase2PoIntegrationTest {

    private PurchaseOrderService poService;
    private InventoryService inventoryService;
    private SupplierService supplierService;
    private Business biz;
    private User owner;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("scm_phase2_po_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        poService = new SqlitePurchaseOrderService();
        inventoryService = new SqliteInventoryService();
        supplierService = new SqliteSupplierService();

        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, address, currency_code) VALUES ('Bismillah Grocery', 'Dhaka', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    bizId = rs.getLong(1);
                }
            }
            biz = new Business(bizId, "Bismillah Grocery", null, "01711111111", "Dhaka", null, "BDT", null, null);

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
    void test2_shouldCreateConfirmExportPoWithoutAffectingStockOrPayables() throws IOException {
        Supplier supplier = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "ACI Consumer Brands", "Rashid", "01712345678", "Dhaka",
                Money.ZERO, "Official Distributor", "SUP-ACI-1", "aci@example.com", "Net 15", 15, true
        ));

        Product rice = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Aarong Chinigura Rice 1kg", "894001122", "SKU-RICE-1",
                "pkt", Money.ofBdt(130), Money.ofBdt(155), 5, 10, "Opening", null,
                Money.ofBdt(130), Money.ofBdt(160), "sack", 25
        ));

        // Supplier-specific TP: BDT 3,150 per sack of 25 pkt (126 BDT/pkt vs default 130)
        inventoryService.saveProductSupplier(new ProductSupplierUpsertDto(
                null, biz.id(), rice.id(), supplier.id(),
                Money.ofBdt(3150), "ACI-CHINI-25", 2, "sack", 25, true, 3, true
        ));

        ProductSupplierViewDto resolved = poService.resolveProductPricingForSupplier(rice.id(), supplier.id());
        assertThat(resolved.tradePrice()).isEqualTo(Money.ofBdt(3150));
        assertThat(resolved.purchaseUnit()).isEqualTo("sack");
        assertThat(resolved.packSize()).isEqualTo(25);

        // 1. Save as Draft first
        PurchaseOrder draft = poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                null, biz.id(), supplier.id(), LocalDate.now().plusDays(3),
                Money.ofBdt(100), Money.ZERO, Money.ofBdt(50), "QT-2026-88", "First order",
                false, owner.id(),
                List.of(new PurchaseOrderItemCreateDto(
                        rice.id(), 4, resolved.purchaseUnit(), resolved.packSize(),
                        resolved.tradePrice(), Money.ZERO, Money.ZERO, null
                ))
        ));
        assertThat(draft.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(draft.subtotal()).isEqualTo(Money.ofBdt(12600)); // 4 * 3150
        assertThat(draft.grandTotal()).isEqualTo(Money.ofBdt(12550)); // 12600 - 100 + 50

        // Verify zero impact on stock & payables in Draft state
        assertThat(inventoryService.getCurrentStock(rice.id())).isEqualTo(10);
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ZERO);

        // 2. Update draft qty to 5 sacks and confirm PO
        PurchaseOrder updatedDraft = poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                draft.id(), biz.id(), supplier.id(), LocalDate.now().plusDays(2),
                Money.ZERO, Money.ZERO, Money.ZERO, "QT-2026-88", "Confirmed qty 5",
                true, owner.id(),
                List.of(new PurchaseOrderItemCreateDto(
                        rice.id(), 5, "sack", 25, Money.ofBdt(3150), Money.ZERO, Money.ZERO, null
                ))
        ));
        assertThat(updatedDraft.poNumber()).isEqualTo(draft.poNumber());
        assertThat(updatedDraft.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
        assertThat(updatedDraft.grandTotal()).isEqualTo(Money.ofBdt(15750));

        // 3. Confirmed PO STILL has ZERO impact on stock, avg cost, and supplier payable!
        assertThat(inventoryService.getCurrentStock(rice.id())).isEqualTo(10);
        assertThat(inventoryService.getProductById(rice.id()).orElseThrow().costPrice()).isEqualTo(Money.ofBdt(130));
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ZERO);

        // 4. Editing a Confirmed PO fails cleanly
        assertThatThrownBy(() -> poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                updatedDraft.id(), biz.id(), supplier.id(), LocalDate.now(),
                Money.ZERO, Money.ZERO, Money.ZERO, null, null, false, owner.id(),
                List.of(new PurchaseOrderItemCreateDto(rice.id(), 1, "sack", 25, Money.ofBdt(3150), Money.ZERO, Money.ZERO, null))
        ))).isInstanceOf(IllegalStateException.class);

        // 5. PDF export produces valid non-empty PDF bytes (%PDF-)
        PurchaseOrderDetailDto detail = poService.getPurchaseOrderDetail(updatedDraft.id()).orElseThrow();
        byte[] pdfBytes = PurchaseOrderPdfExporter.exportToPdf(biz, detail);
        assertThat(pdfBytes.length).isGreaterThan(500);
        assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");
    }
}


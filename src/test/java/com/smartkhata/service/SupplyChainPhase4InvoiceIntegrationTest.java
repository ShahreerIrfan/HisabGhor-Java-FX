package com.smartkhata.service;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
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
 * Phase 4 Acceptance Tests:
 * - Test 6: Supplier Invoice, 3-Way Match (PO <-> GRN <-> Invoice), Received-Not-Invoiced tracking,
 *   duplicate invoice guard, single supplier_ledger entry, and FIFO partial/full supplier payments.
 */
class SupplyChainPhase4InvoiceIntegrationTest {

    private PurchaseOrderService poService;
    private GrnService grnService;
    private SupplierInvoiceService invoiceService;
    private PaymentService paymentService;
    private SupplierService supplierService;
    private InventoryService inventoryService;
    private Business biz;
    private User owner;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("scm_phase4_invoice_test.db");
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        poService = new SqlitePurchaseOrderService();
        grnService = new SqliteGrnService();
        invoiceService = new SqliteSupplierInvoiceService();
        paymentService = new SqlitePaymentService();
        supplierService = new SqliteSupplierService();
        inventoryService = new SqliteInventoryService();

        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('Three-Way Mart', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    bizId = rs.getLong(1);
                }
            }
            biz = new Business(bizId, "Three-Way Mart", null, null, null, null, "BDT", null, null);

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
    void test6_threeWayMatchSingleLedgerPayableAndPaymentReconciliation() {
        Supplier sup = supplierService.createSupplier(new SupplierCreateDto(
                biz.id(), "Olympic Industries", "Reza", "01711220099", "Gazipur",
                Money.ZERO, null, "SUP-OLY", null, "Net 10", 10, true));
        Product biscuit = inventoryService.createProduct(new ProductCreateDto(
                biz.id(), null, "Energy Plus Biscuit", "894555666", "SKU-BISC",
                "pkt", Money.ofBdt(20), Money.ofBdt(25), 10, 0, "Open"));

        // 1. Confirmed PO (100 pkt @ BDT 20 = BDT 2000) -> 0 stock, 0 payable
        PurchaseOrder po = poService.savePurchaseOrder(new PurchaseOrderCreateDto(
                null, biz.id(), sup.id(), LocalDate.now().plusDays(1),
                Money.ZERO, Money.ZERO, Money.ZERO, null, null, true, owner.id(),
                List.of(new PurchaseOrderItemCreateDto(biscuit.id(), 100, "pkt", 1, Money.ofBdt(20), Money.ZERO, Money.ZERO, null))
        ));
        long poItemId = poService.getPurchaseOrderDetail(po.id()).orElseThrow().items().get(0).id();

        // 2. Post GRN for 100 pkt -> Stock = 100, Received-Not-Invoiced = 2000, Supplier Ledger Payable STILL 0!
        GoodsReceiptNote grn = grnService.postGoodsReceipt(new GrnCreateDto(
                biz.id(), po.id(), sup.id(), Money.ZERO, Money.ZERO, "CH-OLY-1", "Delivered", "grn-oly-1", owner.id(),
                List.of(new GrnItemCreateDto(poItemId, biscuit.id(), 100, 100, 0, 0, "pkt", 1, Money.ofBdt(20), Money.ZERO, null))
        ));
        assertThat(inventoryService.getCurrentStock(biscuit.id())).isEqualTo(100);
        assertThat(invoiceService.getTotalUninvoicedGrnLiability(biz.id())).isEqualTo(Money.ofBdt(2000));
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ZERO);

        GrnDetailDto grnDetail = grnService.getGrnDetail(grn.id()).orElseThrow();
        long grnItemId = grnDetail.items().get(0).id();

        // 3. Mismatched variance (> tolerance 0 without owner override) is cleanly rejected
        assertThatThrownBy(() -> invoiceService.postSupplierInvoice(new SupplierInvoiceCreateDto(
                biz.id(), sup.id(), po.id(), grn.id(), "BILL-901", null,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, "CASH", null,
                false, null, "inv-fail-var", owner.id(),
                List.of(new SupplierInvoiceItemCreateDto(grnItemId, poItemId, biscuit.id(), 100, Money.ofBdt(22), null))
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3-Way Match Variance");

        // 4. Exact 3-way matched Supplier Invoice posts -> Uninvoiced drops to 0, Payable rises to 2000, stock remains 100!
        SupplierInvoice invoice = invoiceService.postSupplierInvoice(new SupplierInvoiceCreateDto(
                biz.id(), sup.id(), po.id(), grn.id(), "BILL-901", null,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, "CASH", null,
                false, "Verified 3-way match", "inv-ok-1", owner.id(),
                List.of(new SupplierInvoiceItemCreateDto(grnItemId, poItemId, biscuit.id(), 100, Money.ofBdt(20), null))
        ));
        assertThat(invoice.matchingStatus()).isEqualTo("MATCHED");
        assertThat(invoice.netAmount()).isEqualTo(Money.ofBdt(2000));
        assertThat(invoiceService.getTotalUninvoicedGrnLiability(biz.id())).isEqualTo(Money.ZERO);
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ofBdt(2000));
        assertThat(inventoryService.getCurrentStock(biscuit.id())).isEqualTo(100); // Zero duplicate stock!

        // Duplicate billing on already-invoiced GRN rejected!
        assertThatThrownBy(() -> invoiceService.postSupplierInvoice(new SupplierInvoiceCreateDto(
                biz.id(), sup.id(), po.id(), grn.id(), "BILL-902", null,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, "CASH", null,
                false, null, "inv-dup-grn", owner.id(),
                List.of(new SupplierInvoiceItemCreateDto(grnItemId, poItemId, biscuit.id(), 100, Money.ofBdt(20), null))
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("already linked");

        // 5. Partial payment BDT 750 -> Invoice PARTIAL, Due 1250, Supplier Ledger Payable 1250
        paymentService.makeSupplierPayment(new PaymentCreateDto(
                biz.id(), "SUPPLIER", sup.id(), Money.ofBdt(750), "CASH", "TRX-1", "Part payment", "pay-1", owner.id()
        ));
        SupplierInvoice afterPartPay = invoiceService.getSupplierInvoiceDetail(invoice.id()).orElseThrow().invoice();
        assertThat(afterPartPay.paymentStatus()).isEqualTo("PARTIAL");
        assertThat(afterPartPay.paidAmount()).isEqualTo(Money.ofBdt(750));
        assertThat(afterPartPay.dueAmount()).isEqualTo(Money.ofBdt(1250));
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ofBdt(1250));

        // 6. Full remaining payment BDT 1250 -> PAID, Payable = 0
        paymentService.makeSupplierPayment(new PaymentCreateDto(
                biz.id(), "SUPPLIER", sup.id(), Money.ofBdt(1250), "BANK", "TRX-2", "Final payment", "pay-2", owner.id()
        ));
        SupplierInvoice afterFullPay = invoiceService.getSupplierInvoiceDetail(invoice.id()).orElseThrow().invoice();
        assertThat(afterFullPay.paymentStatus()).isEqualTo("PAID");
        assertThat(afterFullPay.dueAmount()).isEqualTo(Money.ZERO);
        assertThat(supplierService.getTotalOutstandingPayables(biz.id())).isEqualTo(Money.ZERO);
    }
}

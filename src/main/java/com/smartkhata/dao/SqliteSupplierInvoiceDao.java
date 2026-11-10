package com.smartkhata.dao;

import com.smartkhata.model.dto.GrnListItemDto;
import com.smartkhata.model.dto.SupplierInvoiceItemViewDto;
import com.smartkhata.model.dto.SupplierInvoiceListItemDto;
import com.smartkhata.model.entity.SupplierInvoice;
import com.smartkhata.model.entity.SupplierInvoiceItem;
import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteSupplierInvoiceDao implements SupplierInvoiceDao {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public SupplierInvoice saveHeader(Connection conn, SupplierInvoice inv) throws SQLException {
        String sql = "INSERT INTO supplier_invoices (business_id, invoice_number, supplier_bill_no, supplier_id, " +
                "purchase_order_id, grn_id, invoice_date, due_date, subtotal_paisha, discount_paisha, tax_paisha, " +
                "additional_charges_paisha, net_amount_paisha, paid_amount_paisha, due_amount_paisha, " +
                "payment_status, matching_status, variance_paisha, status, notes, idempotency_key, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, inv.businessId());
            ps.setString(2, inv.invoiceNumber());
            setNullableString(ps, 3, inv.supplierBillNo());
            ps.setLong(4, inv.supplierId());
            if (inv.purchaseOrderId() != null) ps.setLong(5, inv.purchaseOrderId()); else ps.setNull(5, Types.INTEGER);
            if (inv.grnId() != null) ps.setLong(6, inv.grnId()); else ps.setNull(6, Types.INTEGER);
            ps.setString(7, (inv.invoiceDate() != null ? inv.invoiceDate() : LocalDateTime.now()).format(ISO_DT));
            if (inv.dueDate() != null) ps.setString(8, inv.dueDate().toString()); else ps.setNull(8, Types.VARCHAR);
            ps.setLong(9, inv.subtotal().paisha());
            ps.setLong(10, inv.discount().paisha());
            ps.setLong(11, inv.tax().paisha());
            ps.setLong(12, inv.additionalCharges().paisha());
            ps.setLong(13, inv.netAmount().paisha());
            ps.setLong(14, inv.paidAmount().paisha());
            ps.setLong(15, inv.dueAmount().paisha());
            ps.setString(16, inv.paymentStatus());
            ps.setString(17, inv.matchingStatus());
            ps.setLong(18, inv.variance().paisha());
            ps.setString(19, inv.status());
            setNullableString(ps, 20, inv.notes());
            setNullableString(ps, 21, inv.idempotencyKey());
            ps.setLong(22, inv.createdByUserId());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return findById(conn, rs.getLong(1)).orElse(inv.withId(rs.getLong(1)));
            }
        }
        return inv;
    }

    @Override
    public void saveItems(Connection conn, long invoiceId, List<SupplierInvoiceItem> items) throws SQLException {
        String sql = "INSERT INTO supplier_invoice_items (supplier_invoice_id, grn_item_id, po_item_id, product_id, " +
                "invoiced_base_qty, unit_price_paisha, variance_paisha, line_total_paisha, notes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (SupplierInvoiceItem item : items) {
                ps.setLong(1, invoiceId);
                if (item.grnItemId() != null) ps.setLong(2, item.grnItemId()); else ps.setNull(2, Types.INTEGER);
                if (item.poItemId() != null) ps.setLong(3, item.poItemId()); else ps.setNull(3, Types.INTEGER);
                ps.setLong(4, item.productId());
                ps.setInt(5, item.invoicedBaseQty());
                ps.setLong(6, item.unitPrice().paisha());
                ps.setLong(7, item.variance().paisha());
                ps.setLong(8, item.lineTotal().paisha());
                setNullableString(ps, 9, item.notes());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public void linkGrn(Connection conn, long grnId, long supplierInvoiceId, Money allocatedAmount) throws SQLException {
        String sql = "INSERT INTO grn_invoice_links (grn_id, supplier_invoice_id, allocated_amount_paisha) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, grnId);
            ps.setLong(2, supplierInvoiceId);
            ps.setLong(3, allocatedAmount != null ? allocatedAmount.paisha() : 0L);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean isGrnActivelyInvoiced(Connection conn, long grnId) throws SQLException {
        String sql = "SELECT 1 FROM grn_invoice_links gil JOIN supplier_invoices si ON si.id = gil.supplier_invoice_id " +
                "WHERE gil.grn_id = ? AND si.status = 'POSTED' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, grnId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public boolean hasActiveSupplierBillNo(Connection conn, long businessId, long supplierId, String supplierBillNo) throws SQLException {
        if (supplierBillNo == null || supplierBillNo.isBlank()) return false;
        String sql = "SELECT 1 FROM supplier_invoices WHERE business_id = ? AND supplier_id = ? " +
                "AND LOWER(supplier_bill_no) = LOWER(?) AND status = 'POSTED' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, supplierId);
            ps.setString(3, supplierBillNo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Optional<SupplierInvoice> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM supplier_invoices WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<SupplierInvoice> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM supplier_invoices WHERE business_id = ? AND idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, idempotencyKey.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<SupplierInvoiceItemViewDto> findItemViewsByInvoiceId(Connection conn, long invoiceId) throws SQLException {
        String sql = "SELECT sii.*, p.name AS product_name, p.unit AS base_unit " +
                "FROM supplier_invoice_items sii JOIN products p ON p.id = sii.product_id " +
                "WHERE sii.supplier_invoice_id = ? ORDER BY sii.id ASC";
        List<SupplierInvoiceItemViewDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new SupplierInvoiceItemViewDto(
                            rs.getLong("id"), rs.getLong("supplier_invoice_id"),
                            rs.getObject("grn_item_id") != null ? rs.getLong("grn_item_id") : null,
                            rs.getObject("po_item_id") != null ? rs.getLong("po_item_id") : null,
                            rs.getLong("product_id"), rs.getString("product_name"), rs.getString("base_unit"),
                            rs.getInt("invoiced_base_qty"),
                            Money.ofPaisha(rs.getLong("unit_price_paisha")),
                            Money.ofPaisha(rs.getLong("variance_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha")),
                            rs.getString("notes")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<SupplierInvoiceListItemDto> searchInvoices(Connection conn, long businessId, String query,
                                                           Long supplierId, String paymentStatus,
                                                           LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT si.*, s.name AS supplier_name, g.grn_number, po.po_number " +
                "FROM supplier_invoices si " +
                "JOIN suppliers s ON s.id = si.supplier_id " +
                "LEFT JOIN goods_receipt_notes g ON g.id = si.grn_id " +
                "LEFT JOIN purchase_orders po ON po.id = si.purchase_order_id " +
                "WHERE si.business_id = ?"
        );
        if (supplierId != null) sql.append(" AND si.supplier_id = ?");
        if (paymentStatus != null && !paymentStatus.isBlank()) sql.append(" AND si.payment_status = ?");
        if (startDate != null) sql.append(" AND date(si.invoice_date) >= ?");
        if (endDate != null) sql.append(" AND date(si.invoice_date) <= ?");
        boolean hasQ = query != null && !query.trim().isBlank();
        if (hasQ) {
            sql.append(" AND (si.invoice_number LIKE ? OR COALESCE(si.supplier_bill_no, '') LIKE ? OR s.name LIKE ? OR COALESCE(g.grn_number, '') LIKE ?)");
        }
        sql.append(" ORDER BY si.invoice_date DESC, si.id DESC");

        List<SupplierInvoiceListItemDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (supplierId != null) ps.setLong(idx++, supplierId);
            if (paymentStatus != null && !paymentStatus.isBlank()) ps.setString(idx++, paymentStatus);
            if (startDate != null) ps.setString(idx++, startDate.toString());
            if (endDate != null) ps.setString(idx++, endDate.toString());
            if (hasQ) {
                String pat = "%" + query.trim() + "%";
                ps.setString(idx++, pat);
                ps.setString(idx++, pat);
                ps.setString(idx++, pat);
                ps.setString(idx++, pat);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dueStr = rs.getString("due_date");
                    list.add(new SupplierInvoiceListItemDto(
                            rs.getLong("id"), rs.getString("invoice_number"), rs.getString("supplier_bill_no"),
                            rs.getLong("supplier_id"), rs.getString("supplier_name"),
                            rs.getObject("grn_id") != null ? rs.getLong("grn_id") : null,
                            rs.getString("grn_number"),
                            rs.getObject("purchase_order_id") != null ? rs.getLong("purchase_order_id") : null,
                            rs.getString("po_number"),
                            parseDateTime(rs.getString("invoice_date")),
                            dueStr != null && !dueStr.isBlank() ? LocalDate.parse(dueStr) : null,
                            Money.ofPaisha(rs.getLong("net_amount_paisha")),
                            Money.ofPaisha(rs.getLong("paid_amount_paisha")),
                            Money.ofPaisha(rs.getLong("due_amount_paisha")),
                            rs.getString("payment_status"), rs.getString("matching_status"),
                            Money.ofPaisha(rs.getLong("variance_paisha")), rs.getString("status")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<SupplierInvoice> findUnpaidBySupplierId(Connection conn, long supplierId) throws SQLException {
        String sql = "SELECT * FROM supplier_invoices WHERE supplier_id = ? AND status = 'POSTED' AND due_amount_paisha > 0 " +
                "ORDER BY invoice_date ASC, id ASC";
        List<SupplierInvoice> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    @Override
    public void updatePaymentStatus(Connection conn, long invoiceId, Money paidAmount, Money dueAmount, String paymentStatus) throws SQLException {
        String sql = "UPDATE supplier_invoices SET paid_amount_paisha = ?, due_amount_paisha = ?, payment_status = ?, " +
                "updated_at = datetime('now', 'localtime') WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paidAmount.paisha());
            ps.setLong(2, dueAmount.paisha());
            ps.setString(3, paymentStatus);
            ps.setLong(4, invoiceId);
            ps.executeUpdate();
        }
    }

    @Override
    public void voidInvoice(Connection conn, long invoiceId, long businessId, String reason) throws SQLException {
        String sql = "UPDATE supplier_invoices SET status = 'VOIDED', notes = COALESCE(notes || ' | ', '') || ?, " +
                "updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "VOIDED: " + reason);
            ps.setLong(2, invoiceId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public int getNextInvoiceSequence(Connection conn, long businessId, String datePrefix) throws SQLException {
        String sql = "SELECT COUNT(*) + 1 FROM supplier_invoices WHERE business_id = ? AND invoice_number LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, "PINV-" + datePrefix + "-%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 1;
    }

    @Override
    public List<GrnListItemDto> findUninvoicedGrns(Connection conn, long businessId, Long supplierId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT g.id, g.grn_number, g.purchase_order_id, po.po_number, g.supplier_id, s.name AS supplier_name, " +
                "g.receipt_date, g.status, g.total_cost_paisha, " +
                "COALESCE((SELECT SUM(accepted_base_qty) FROM goods_receipt_items gri WHERE gri.grn_id = g.id), 0) AS tot_acc, " +
                "COALESCE((SELECT SUM(rejected_qty + damaged_qty) FROM goods_receipt_items gri WHERE gri.grn_id = g.id), 0) AS tot_bad " +
                "FROM goods_receipt_notes g " +
                "JOIN suppliers s ON s.id = g.supplier_id " +
                "LEFT JOIN purchase_orders po ON po.id = g.purchase_order_id " +
                "WHERE g.business_id = ? AND g.status = 'POSTED' " +
                "AND NOT EXISTS(SELECT 1 FROM grn_invoice_links gil JOIN supplier_invoices si ON si.id = gil.supplier_invoice_id WHERE gil.grn_id = g.id AND si.status = 'POSTED')"
        );
        if (supplierId != null) sql.append(" AND g.supplier_id = ?");
        sql.append(" ORDER BY g.receipt_date ASC, g.id ASC");

        List<GrnListItemDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, businessId);
            if (supplierId != null) ps.setLong(2, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new GrnListItemDto(
                            rs.getLong("id"), rs.getString("grn_number"),
                            rs.getObject("purchase_order_id") != null ? rs.getLong("purchase_order_id") : null,
                            rs.getString("po_number"), rs.getLong("supplier_id"), rs.getString("supplier_name"),
                            parseDateTime(rs.getString("receipt_date")), GrnStatus.POSTED,
                            Money.ofPaisha(rs.getLong("total_cost_paisha")),
                            rs.getInt("tot_acc"), rs.getInt("tot_bad"), false
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public Money getTotalUninvoicedGrnLiability(Connection conn, long businessId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(g.total_cost_paisha), 0) FROM goods_receipt_notes g " +
                "WHERE g.business_id = ? AND g.status = 'POSTED' " +
                "AND NOT EXISTS(SELECT 1 FROM grn_invoice_links gil JOIN supplier_invoices si ON si.id = gil.supplier_invoice_id WHERE gil.grn_id = g.id AND si.status = 'POSTED')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Money.ofPaisha(rs.getLong(1));
            }
        }
        return Money.ZERO;
    }

    private SupplierInvoice mapRow(ResultSet rs) throws SQLException {
        String dueStr = rs.getString("due_date");
        return new SupplierInvoice(
                rs.getLong("id"), rs.getLong("business_id"), rs.getString("invoice_number"),
                rs.getString("supplier_bill_no"), rs.getLong("supplier_id"),
                rs.getObject("purchase_order_id") != null ? rs.getLong("purchase_order_id") : null,
                rs.getObject("grn_id") != null ? rs.getLong("grn_id") : null,
                parseDateTime(rs.getString("invoice_date")),
                dueStr != null && !dueStr.isBlank() ? LocalDate.parse(dueStr) : null,
                Money.ofPaisha(rs.getLong("subtotal_paisha")),
                Money.ofPaisha(rs.getLong("discount_paisha")),
                Money.ofPaisha(rs.getLong("tax_paisha")),
                Money.ofPaisha(rs.getLong("additional_charges_paisha")),
                Money.ofPaisha(rs.getLong("net_amount_paisha")),
                Money.ofPaisha(rs.getLong("paid_amount_paisha")),
                Money.ofPaisha(rs.getLong("due_amount_paisha")),
                rs.getString("payment_status"), rs.getString("matching_status"),
                Money.ofPaisha(rs.getLong("variance_paisha")), rs.getString("status"),
                rs.getString("notes"), rs.getString("idempotency_key"),
                rs.getLong("created_by_user_id"),
                parseDateTime(rs.getString("created_at")),
                parseDateTime(rs.getString("updated_at"))
        );
    }

    private static void setNullableString(PreparedStatement ps, int idx, String val) throws SQLException {
        if (val != null && !val.trim().isBlank()) ps.setString(idx, val.trim());
        else ps.setNull(idx, Types.VARCHAR);
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        return LocalDateTime.parse(s.trim().replace(" ", "T"));
    }
}


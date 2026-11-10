package com.smartkhata.dao;

import com.smartkhata.model.dto.GrnItemViewDto;
import com.smartkhata.model.dto.GrnListItemDto;
import com.smartkhata.model.entity.GoodsReceiptItem;
import com.smartkhata.model.entity.GoodsReceiptNote;
import com.smartkhata.model.entity.InventoryCostHistory;
import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteGrnDao implements GrnDao {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public GoodsReceiptNote saveHeader(Connection conn, GoodsReceiptNote grn) throws SQLException {
        String sql = "INSERT INTO goods_receipt_notes (business_id, grn_number, purchase_order_id, supplier_id, " +
                "receipt_date, status, subtotal_paisha, discount_paisha, freight_other_cost_paisha, total_cost_paisha, " +
                "challan_reference, notes, idempotency_key, posted_by_user_id, posted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, grn.businessId());
            ps.setString(2, grn.grnNumber());
            if (grn.purchaseOrderId() != null) ps.setLong(3, grn.purchaseOrderId()); else ps.setNull(3, Types.INTEGER);
            ps.setLong(4, grn.supplierId());
            ps.setString(5, (grn.receiptDate() != null ? grn.receiptDate() : LocalDateTime.now()).format(ISO_DT));
            ps.setString(6, grn.status().name());
            ps.setLong(7, grn.subtotal().paisha());
            ps.setLong(8, grn.discount().paisha());
            ps.setLong(9, grn.freightAndOtherCosts().paisha());
            ps.setLong(10, grn.totalCost().paisha());
            setNullableString(ps, 11, grn.challanReference());
            setNullableString(ps, 12, grn.notes());
            setNullableString(ps, 13, grn.idempotencyKey());
            ps.setLong(14, grn.postedByUserId());
            ps.setString(15, (grn.postedAt() != null ? grn.postedAt() : LocalDateTime.now()).format(ISO_DT));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return findById(conn, rs.getLong(1)).orElse(grn.withId(rs.getLong(1)));
                }
            }
        }
        return grn;
    }

    @Override
    public List<GoodsReceiptItem> saveItems(Connection conn, long grnId, List<GoodsReceiptItem> items) throws SQLException {
        String sql = "INSERT INTO goods_receipt_items (grn_id, po_item_id, product_id, ordered_qty, received_qty, " +
                "accepted_qty, rejected_qty, damaged_qty, purchase_unit, pack_size, accepted_base_qty, " +
                "unit_trade_price_paisha, discount_paisha, allocated_extra_cost_paisha, effective_base_unit_cost_paisha, " +
                "line_total_paisha, batch_or_remark) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<GoodsReceiptItem> savedList = new ArrayList<>(items.size());
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (GoodsReceiptItem item : items) {
                ps.setLong(1, grnId);
                if (item.poItemId() != null) ps.setLong(2, item.poItemId()); else ps.setNull(2, Types.INTEGER);
                ps.setLong(3, item.productId());
                ps.setInt(4, item.orderedQty());
                ps.setInt(5, item.receivedQty());
                ps.setInt(6, item.acceptedQty());
                ps.setInt(7, item.rejectedQty());
                ps.setInt(8, item.damagedQty());
                ps.setString(9, item.purchaseUnit() != null ? item.purchaseUnit() : "pcs");
                ps.setInt(10, Math.max(1, item.packSize()));
                ps.setInt(11, item.acceptedBaseQty());
                ps.setLong(12, item.unitTradePrice().paisha());
                ps.setLong(13, item.discount().paisha());
                ps.setLong(14, item.allocatedExtraCost().paisha());
                ps.setLong(15, item.effectiveBaseUnitCost().paisha());
                ps.setLong(16, item.lineTotal().paisha());
                setNullableString(ps, 17, item.batchOrRemark());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    long itemId = rs.next() ? rs.getLong(1) : 0L;
                    savedList.add(new GoodsReceiptItem(
                            itemId > 0 ? itemId : null, grnId, item.poItemId(), item.productId(),
                            item.orderedQty(), item.receivedQty(), item.acceptedQty(), item.rejectedQty(),
                            item.damagedQty(), item.purchaseUnit(), item.packSize(), item.acceptedBaseQty(),
                            item.unitTradePrice(), item.discount(), item.allocatedExtraCost(),
                            item.effectiveBaseUnitCost(), item.lineTotal(), item.batchOrRemark()
                    ));
                }
            }
        }
        return savedList;
    }

    @Override
    public Optional<GoodsReceiptNote> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM goods_receipt_notes WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapHeader(rs));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<GoodsReceiptNote> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM goods_receipt_notes WHERE business_id = ? AND idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, idempotencyKey.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapHeader(rs));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<GoodsReceiptItem> findItemsByGrnId(Connection conn, long grnId) throws SQLException {
        String sql = "SELECT * FROM goods_receipt_items WHERE grn_id = ? ORDER BY id ASC";
        List<GoodsReceiptItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, grnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long poItemId = rs.getObject("po_item_id") != null ? rs.getLong("po_item_id") : null;
                    list.add(new GoodsReceiptItem(
                            rs.getLong("id"), rs.getLong("grn_id"), poItemId, rs.getLong("product_id"),
                            rs.getInt("ordered_qty"), rs.getInt("received_qty"), rs.getInt("accepted_qty"),
                            rs.getInt("rejected_qty"), rs.getInt("damaged_qty"), rs.getString("purchase_unit"),
                            rs.getInt("pack_size"), rs.getInt("accepted_base_qty"),
                            Money.ofPaisha(rs.getLong("unit_trade_price_paisha")),
                            Money.ofPaisha(rs.getLong("discount_paisha")),
                            Money.ofPaisha(rs.getLong("allocated_extra_cost_paisha")),
                            Money.ofPaisha(rs.getLong("effective_base_unit_cost_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha")),
                            rs.getString("batch_or_remark")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<GrnItemViewDto> findItemViewsByGrnId(Connection conn, long grnId) throws SQLException {
        String sql = "SELECT gri.*, p.name AS product_name, p.unit AS base_unit " +
                "FROM goods_receipt_items gri JOIN products p ON p.id = gri.product_id " +
                "WHERE gri.grn_id = ? ORDER BY gri.id ASC";
        List<GrnItemViewDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, grnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long poItemId = rs.getObject("po_item_id") != null ? rs.getLong("po_item_id") : null;
                    list.add(new GrnItemViewDto(
                            rs.getLong("id"), rs.getLong("grn_id"), poItemId, rs.getLong("product_id"),
                            rs.getString("product_name"), rs.getString("base_unit"),
                            rs.getInt("ordered_qty"), rs.getInt("received_qty"), rs.getInt("accepted_qty"),
                            rs.getInt("rejected_qty"), rs.getInt("damaged_qty"), rs.getString("purchase_unit"),
                            rs.getInt("pack_size"), rs.getInt("accepted_base_qty"),
                            Money.ofPaisha(rs.getLong("unit_trade_price_paisha")),
                            Money.ofPaisha(rs.getLong("discount_paisha")),
                            Money.ofPaisha(rs.getLong("allocated_extra_cost_paisha")),
                            Money.ofPaisha(rs.getLong("effective_base_unit_cost_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha")),
                            rs.getString("batch_or_remark")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<GrnListItemDto> searchGrns(Connection conn, long businessId, String query, Long supplierId,
                                           Long poId, GrnStatus status, LocalDate startDate, LocalDate endDate) throws SQLException {
        boolean hasInvoiceLinks = tableExists(conn, "grn_invoice_links");
        String invoiceExistsExpr = hasInvoiceLinks
                ? "EXISTS(SELECT 1 FROM grn_invoice_links gil JOIN supplier_invoices si ON si.id = gil.supplier_invoice_id WHERE gil.grn_id = g.id AND si.status = 'POSTED')"
                : "0";

        StringBuilder sql = new StringBuilder(
                "SELECT g.id, g.grn_number, g.purchase_order_id, po.po_number, g.supplier_id, s.name AS supplier_name, " +
                "g.receipt_date, g.status, g.total_cost_paisha, " +
                "COALESCE((SELECT SUM(accepted_base_qty) FROM goods_receipt_items gri WHERE gri.grn_id = g.id), 0) AS tot_acc, " +
                "COALESCE((SELECT SUM(rejected_qty + damaged_qty) FROM goods_receipt_items gri WHERE gri.grn_id = g.id), 0) AS tot_bad, " +
                invoiceExistsExpr + " AS is_invoiced " +
                "FROM goods_receipt_notes g " +
                "JOIN suppliers s ON s.id = g.supplier_id " +
                "LEFT JOIN purchase_orders po ON po.id = g.purchase_order_id " +
                "WHERE g.business_id = ?"
        );
        if (supplierId != null) sql.append(" AND g.supplier_id = ?");
        if (poId != null) sql.append(" AND g.purchase_order_id = ?");
        if (status != null) sql.append(" AND g.status = ?");
        if (startDate != null) sql.append(" AND date(g.receipt_date) >= ?");
        if (endDate != null) sql.append(" AND date(g.receipt_date) <= ?");
        boolean hasQ = query != null && !query.trim().isBlank();
        if (hasQ) {
            sql.append(" AND (g.grn_number LIKE ? OR s.name LIKE ? OR COALESCE(po.po_number, '') LIKE ? OR COALESCE(g.challan_reference, '') LIKE ?)");
        }
        sql.append(" ORDER BY g.receipt_date DESC, g.id DESC");

        List<GrnListItemDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (supplierId != null) ps.setLong(idx++, supplierId);
            if (poId != null) ps.setLong(idx++, poId);
            if (status != null) ps.setString(idx++, status.name());
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
                    Long purchaseOrderId = rs.getObject("purchase_order_id") != null ? rs.getLong("purchase_order_id") : null;
                    list.add(new GrnListItemDto(
                            rs.getLong("id"), rs.getString("grn_number"), purchaseOrderId, rs.getString("po_number"),
                            rs.getLong("supplier_id"), rs.getString("supplier_name"),
                            parseDateTime(rs.getString("receipt_date")),
                            GrnStatus.valueOf(rs.getString("status")),
                            Money.ofPaisha(rs.getLong("total_cost_paisha")),
                            rs.getInt("tot_acc"), rs.getInt("tot_bad"), rs.getInt("is_invoiced") == 1
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public int getNextGrnSequence(Connection conn, long businessId, String datePrefix) throws SQLException {
        String pattern = "GRN-" + datePrefix + "-%";
        String sql = "SELECT COUNT(*) + 1 FROM goods_receipt_notes WHERE business_id = ? AND grn_number LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 1;
    }

    @Override
    public void markReversed(Connection conn, long grnId, long businessId, long userId, String reason, LocalDateTime reversedAt) throws SQLException {
        String sql = "UPDATE goods_receipt_notes SET status = 'REVERSED', reversed_by_user_id = ?, " +
                "reversal_reason = ?, reversed_at = ?, updated_at = datetime('now', 'localtime') " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, reason);
            ps.setString(3, (reversedAt != null ? reversedAt : LocalDateTime.now()).format(ISO_DT));
            ps.setLong(4, grnId);
            ps.setLong(5, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void saveCostHistory(Connection conn, InventoryCostHistory h) throws SQLException {
        String sql = "INSERT INTO inventory_cost_history (business_id, product_id, grn_id, grn_item_id, " +
                "previous_qty, previous_avg_cost_paisha, received_base_qty, received_unit_cost_paisha, " +
                "new_qty, new_avg_cost_paisha, changed_at, changed_by_user_id, notes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, h.businessId());
            ps.setLong(2, h.productId());
            if (h.grnId() != null) ps.setLong(3, h.grnId()); else ps.setNull(3, Types.INTEGER);
            if (h.grnItemId() != null) ps.setLong(4, h.grnItemId()); else ps.setNull(4, Types.INTEGER);
            ps.setInt(5, h.previousQty());
            ps.setLong(6, h.previousAvgCost().paisha());
            ps.setInt(7, h.receivedBaseQty());
            ps.setLong(8, h.receivedUnitCost().paisha());
            ps.setInt(9, h.newQty());
            ps.setLong(10, h.newAvgCost().paisha());
            ps.setString(11, (h.changedAt() != null ? h.changedAt() : LocalDateTime.now()).format(ISO_DT));
            if (h.changedByUserId() != null) ps.setLong(12, h.changedByUserId()); else ps.setNull(12, Types.INTEGER);
            setNullableString(ps, 13, h.notes());
            ps.executeUpdate();
        }
    }

    @Override
    public List<InventoryCostHistory> findCostHistoryByProduct(Connection conn, long businessId, long productId) throws SQLException {
        String sql = "SELECT * FROM inventory_cost_history WHERE business_id = ? AND product_id = ? ORDER BY changed_at DESC, id DESC";
        List<InventoryCostHistory> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new InventoryCostHistory(
                            rs.getLong("id"), rs.getLong("business_id"), rs.getLong("product_id"),
                            rs.getObject("grn_id") != null ? rs.getLong("grn_id") : null,
                            rs.getObject("grn_item_id") != null ? rs.getLong("grn_item_id") : null,
                            rs.getInt("previous_qty"),
                            Money.ofPaisha(rs.getLong("previous_avg_cost_paisha")),
                            rs.getInt("received_base_qty"),
                            Money.ofPaisha(rs.getLong("received_unit_cost_paisha")),
                            rs.getInt("new_qty"),
                            Money.ofPaisha(rs.getLong("new_avg_cost_paisha")),
                            parseDateTime(rs.getString("changed_at")),
                            rs.getObject("changed_by_user_id") != null ? rs.getLong("changed_by_user_id") : null,
                            rs.getString("notes")
                    ));
                }
            }
        }
        return list;
    }

    private GoodsReceiptNote mapHeader(ResultSet rs) throws SQLException {
        String postedAtStr = rs.getString("posted_at");
        String revAtStr = rs.getString("reversed_at");
        return new GoodsReceiptNote(
                rs.getLong("id"), rs.getLong("business_id"), rs.getString("grn_number"),
                rs.getObject("purchase_order_id") != null ? rs.getLong("purchase_order_id") : null,
                rs.getLong("supplier_id"), parseDateTime(rs.getString("receipt_date")),
                GrnStatus.valueOf(rs.getString("status")),
                Money.ofPaisha(rs.getLong("subtotal_paisha")),
                Money.ofPaisha(rs.getLong("discount_paisha")),
                Money.ofPaisha(rs.getLong("freight_other_cost_paisha")),
                Money.ofPaisha(rs.getLong("total_cost_paisha")),
                rs.getString("challan_reference"), rs.getString("notes"), rs.getString("idempotency_key"),
                rs.getLong("posted_by_user_id"),
                postedAtStr != null && !postedAtStr.isBlank() ? parseDateTime(postedAtStr) : null,
                rs.getObject("reversed_by_user_id") != null ? rs.getLong("reversed_by_user_id") : null,
                rs.getString("reversal_reason"),
                revAtStr != null && !revAtStr.isBlank() ? parseDateTime(revAtStr) : null,
                parseDateTime(rs.getString("created_at")),
                parseDateTime(rs.getString("updated_at"))
        );
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
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


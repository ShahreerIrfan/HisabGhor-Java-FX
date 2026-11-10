package com.smartkhata.dao;

import com.smartkhata.model.dto.PurchaseOrderItemViewDto;
import com.smartkhata.model.dto.PurchaseOrderListItemDto;
import com.smartkhata.model.entity.PurchaseOrder;
import com.smartkhata.model.entity.PurchaseOrderItem;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqlitePurchaseOrderDao implements PurchaseOrderDao {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PurchaseOrder saveHeader(Connection conn, PurchaseOrder order) throws SQLException {
        if (order.id() == null) {
            String sql = "INSERT INTO purchase_orders (business_id, po_number, supplier_id, po_date, " +
                    "expected_delivery_date, status, subtotal_paisha, discount_paisha, tax_paisha, " +
                    "additional_charges_paisha, grand_total_paisha, supplier_reference, notes, created_by_user_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, order.businessId());
                ps.setString(2, order.poNumber());
                ps.setLong(3, order.supplierId());
                ps.setString(4, (order.poDate() != null ? order.poDate() : LocalDateTime.now()).format(ISO_DT));
                if (order.expectedDeliveryDate() != null) {
                    ps.setString(5, order.expectedDeliveryDate().toString());
                } else {
                    ps.setNull(5, Types.VARCHAR);
                }
                ps.setString(6, order.status().name());
                ps.setLong(7, order.subtotal().paisha());
                ps.setLong(8, order.discount().paisha());
                ps.setLong(9, order.tax().paisha());
                ps.setLong(10, order.additionalCharges().paisha());
                ps.setLong(11, order.grandTotal().paisha());
                setNullableString(ps, 12, order.supplierReference());
                setNullableString(ps, 13, order.notes());
                ps.setLong(14, order.createdByUserId());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return findById(conn, rs.getLong(1)).orElse(order.withId(rs.getLong(1)));
                    }
                }
            }
        } else {
            String sql = "UPDATE purchase_orders SET supplier_id = ?, expected_delivery_date = ?, status = ?, " +
                    "subtotal_paisha = ?, discount_paisha = ?, tax_paisha = ?, additional_charges_paisha = ?, " +
                    "grand_total_paisha = ?, supplier_reference = ?, notes = ?, updated_at = datetime('now', 'localtime') " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, order.supplierId());
                if (order.expectedDeliveryDate() != null) {
                    ps.setString(2, order.expectedDeliveryDate().toString());
                } else {
                    ps.setNull(2, Types.VARCHAR);
                }
                ps.setString(3, order.status().name());
                ps.setLong(4, order.subtotal().paisha());
                ps.setLong(5, order.discount().paisha());
                ps.setLong(6, order.tax().paisha());
                ps.setLong(7, order.additionalCharges().paisha());
                ps.setLong(8, order.grandTotal().paisha());
                setNullableString(ps, 9, order.supplierReference());
                setNullableString(ps, 10, order.notes());
                ps.setLong(11, order.id());
                ps.setLong(12, order.businessId());
                ps.executeUpdate();
                return findById(conn, order.id()).orElse(order);
            }
        }
        return order;
    }

    @Override
    public void replaceItems(Connection conn, long poId, List<PurchaseOrderItem> items) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM purchase_order_items WHERE purchase_order_id = ?")) {
            del.setLong(1, poId);
            del.executeUpdate();
        }
        String sql = "INSERT INTO purchase_order_items (purchase_order_id, product_id, ordered_qty, received_qty, " +
                "purchase_unit, pack_size, trade_price_paisha, discount_paisha, tax_paisha, line_total_paisha, notes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PurchaseOrderItem item : items) {
                ps.setLong(1, poId);
                ps.setLong(2, item.productId());
                ps.setInt(3, item.orderedQty());
                ps.setInt(4, item.receivedQty());
                ps.setString(5, item.purchaseUnit() != null && !item.purchaseUnit().isBlank() ? item.purchaseUnit() : "pcs");
                ps.setInt(6, Math.max(1, item.packSize()));
                ps.setLong(7, item.tradePrice().paisha());
                ps.setLong(8, item.discount().paisha());
                ps.setLong(9, item.tax().paisha());
                ps.setLong(10, item.lineTotal().paisha());
                setNullableString(ps, 11, item.notes());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public Optional<PurchaseOrder> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM purchase_orders WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapHeader(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<PurchaseOrderItem> findItemsByOrderId(Connection conn, long poId) throws SQLException {
        String sql = "SELECT * FROM purchase_order_items WHERE purchase_order_id = ? ORDER BY id ASC";
        List<PurchaseOrderItem> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, poId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new PurchaseOrderItem(
                            rs.getLong("id"),
                            rs.getLong("purchase_order_id"),
                            rs.getLong("product_id"),
                            rs.getInt("ordered_qty"),
                            rs.getInt("received_qty"),
                            rs.getString("purchase_unit"),
                            rs.getInt("pack_size"),
                            Money.ofPaisha(rs.getLong("trade_price_paisha")),
                            Money.ofPaisha(rs.getLong("discount_paisha")),
                            Money.ofPaisha(rs.getLong("tax_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha")),
                            rs.getString("notes")
                    ));
                }
            }
        }
        return items;
    }

    @Override
    public List<PurchaseOrderItemViewDto> findItemViewsByOrderId(Connection conn, long poId) throws SQLException {
        String sql = "SELECT i.*, p.name AS product_name, p.sku AS product_sku, p.unit AS base_unit " +
                "FROM purchase_order_items i " +
                "JOIN products p ON p.id = i.product_id " +
                "WHERE i.purchase_order_id = ? ORDER BY i.id ASC";
        List<PurchaseOrderItemViewDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, poId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PurchaseOrderItemViewDto(
                            rs.getLong("id"),
                            rs.getLong("purchase_order_id"),
                            rs.getLong("product_id"),
                            rs.getString("product_name"),
                            rs.getString("product_sku"),
                            rs.getString("base_unit"),
                            rs.getInt("ordered_qty"),
                            rs.getInt("received_qty"),
                            rs.getString("purchase_unit"),
                            rs.getInt("pack_size"),
                            Money.ofPaisha(rs.getLong("trade_price_paisha")),
                            Money.ofPaisha(rs.getLong("discount_paisha")),
                            Money.ofPaisha(rs.getLong("tax_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha")),
                            rs.getString("notes")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<PurchaseOrderListItemDto> searchOrders(Connection conn, long businessId, String query,
                                                       Long supplierId, PurchaseOrderStatus status,
                                                       LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT po.id, po.po_number, po.supplier_id, " +
                "COALESCE(s.name, 'Supplier #' || po.supplier_id) AS supplier_name, po.po_date, " +
                "po.expected_delivery_date, po.status, po.grand_total_paisha, " +
                "COALESCE((SELECT SUM(ordered_qty) FROM purchase_order_items poi WHERE poi.purchase_order_id = po.id), 0) AS tot_ord, " +
                "COALESCE((SELECT SUM(received_qty) FROM purchase_order_items poi WHERE poi.purchase_order_id = po.id), 0) AS tot_rec " +
                "FROM purchase_orders po " +
                "LEFT JOIN suppliers s ON s.id = po.supplier_id " +
                "WHERE po.business_id = ?"
        );
        if (supplierId != null) sql.append(" AND po.supplier_id = ?");
        if (status != null) sql.append(" AND po.status = ?");
        if (startDate != null) sql.append(" AND date(po.po_date) >= ?");
        if (endDate != null) sql.append(" AND date(po.po_date) <= ?");
        boolean hasQ = query != null && !query.trim().isBlank();
        if (hasQ) {
            sql.append(" AND (po.po_number LIKE ? OR s.name LIKE ? OR COALESCE(po.supplier_reference, '') LIKE ?)");
        }
        sql.append(" ORDER BY po.po_date DESC, po.id DESC");

        List<PurchaseOrderListItemDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (supplierId != null) ps.setLong(idx++, supplierId);
            if (status != null) ps.setString(idx++, status.name());
            if (startDate != null) ps.setString(idx++, startDate.toString());
            if (endDate != null) ps.setString(idx++, endDate.toString());
            if (hasQ) {
                String pat = "%" + query.trim() + "%";
                ps.setString(idx++, pat);
                ps.setString(idx++, pat);
                ps.setString(idx++, pat);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String expStr = rs.getString("expected_delivery_date");
                    list.add(new PurchaseOrderListItemDto(
                            rs.getLong("id"),
                            rs.getString("po_number"),
                            rs.getLong("supplier_id"),
                            rs.getString("supplier_name"),
                            parseDateTime(rs.getString("po_date")),
                            expStr != null && !expStr.isBlank() ? LocalDate.parse(expStr) : null,
                            PurchaseOrderStatus.valueOf(rs.getString("status")),
                            Money.ofPaisha(rs.getLong("grand_total_paisha")),
                            rs.getInt("tot_ord"),
                            rs.getInt("tot_rec")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public int getNextPoSequence(Connection conn, long businessId, String datePrefix) throws SQLException {
        String pattern = "PO-" + datePrefix + "-%";
        String sql = "SELECT po_number FROM purchase_orders WHERE business_id = ? AND po_number LIKE ?";
        int maxSeq = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String num = rs.getString(1);
                    if (num != null) {
                        int dash = num.lastIndexOf('-');
                        if (dash >= 0 && dash + 1 < num.length()) {
                            try {
                                int parsed = Integer.parseInt(num.substring(dash + 1).trim());
                                if (parsed > maxSeq) maxSeq = parsed;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        }
        return maxSeq + 1;
    }

    @Override
    public void updateStatus(Connection conn, long poId, long businessId, PurchaseOrderStatus status) throws SQLException {
        String sql = "UPDATE purchase_orders SET status = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, poId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void addItemReceivedQty(Connection conn, long poItemId, int deltaReceivedQty) throws SQLException {
        String sql = "UPDATE purchase_order_items SET received_qty = received_qty + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, deltaReceivedQty);
            ps.setLong(2, poItemId);
            ps.executeUpdate();
        }
    }

    private PurchaseOrder mapHeader(ResultSet rs) throws SQLException {
        String expStr = rs.getString("expected_delivery_date");
        return new PurchaseOrder(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("po_number"),
                rs.getLong("supplier_id"),
                parseDateTime(rs.getString("po_date")),
                expStr != null && !expStr.isBlank() ? LocalDate.parse(expStr) : null,
                PurchaseOrderStatus.valueOf(rs.getString("status")),
                Money.ofPaisha(rs.getLong("subtotal_paisha")),
                Money.ofPaisha(rs.getLong("discount_paisha")),
                Money.ofPaisha(rs.getLong("tax_paisha")),
                Money.ofPaisha(rs.getLong("additional_charges_paisha")),
                Money.ofPaisha(rs.getLong("grand_total_paisha")),
                rs.getString("supplier_reference"),
                rs.getString("notes"),
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


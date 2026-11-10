package com.smartkhata.dao;

import com.smartkhata.model.dto.PurchaseListItemDto;
import com.smartkhata.model.entity.Purchase;
import com.smartkhata.model.entity.PurchaseItem;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqlitePurchaseDao implements PurchaseDao {

    @Override
    public Purchase save(Connection conn, Purchase purchase) throws SQLException {
        if (purchase.id() == null) {
            String sql = "INSERT INTO purchases (business_id, purchase_number, supplier_id, total_amount_paisha, discount_paisha, " +
                    "net_amount_paisha, paid_amount_paisha, due_amount_paisha, payment_status, idempotency_key, purchase_date, notes, created_by_user_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, purchase.businessId());
                ps.setString(2, purchase.purchaseNumber());
                ps.setLong(3, purchase.supplierId());
                ps.setLong(4, purchase.totalAmount().paisha());
                ps.setLong(5, purchase.discount() != null ? purchase.discount().paisha() : 0L);
                ps.setLong(6, purchase.netAmount().paisha());
                ps.setLong(7, purchase.paidAmount().paisha());
                ps.setLong(8, purchase.dueAmount().paisha());
                ps.setString(9, purchase.paymentStatus());
                ps.setString(10, purchase.idempotencyKey());
                ps.setString(11, purchase.purchaseDate() != null ? purchase.purchaseDate().toString().replace("T", " ") : null);
                ps.setString(12, purchase.notes());
                ps.setLong(13, purchase.createdByUserId());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return purchase.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE purchases SET is_reversed = ?, reversal_reason = ?, reversed_at = ?, updated_at = datetime('now', 'localtime') " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, purchase.isReversed() ? 1 : 0);
                ps.setString(2, purchase.reversalReason());
                ps.setString(3, purchase.reversedAt() != null ? purchase.reversedAt().toString().replace("T", " ") : null);
                ps.setLong(4, purchase.id());
                ps.setLong(5, purchase.businessId());
                ps.executeUpdate();
            }
        }
        return purchase;
    }

    @Override
    public void saveItems(Connection conn, List<PurchaseItem> items) throws SQLException {
        if (items == null || items.isEmpty()) return;

        String sql = "INSERT INTO purchase_items (purchase_id, product_id, quantity, unit_cost_paisha, line_total_paisha) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PurchaseItem item : items) {
                ps.setLong(1, item.purchaseId());
                ps.setLong(2, item.productId());
                ps.setInt(3, item.quantity());
                ps.setLong(4, item.unitCost().paisha());
                ps.setLong(5, item.lineTotal().paisha());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public Optional<Purchase> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, business_id, purchase_number, supplier_id, total_amount_paisha, discount_paisha, net_amount_paisha, " +
                "paid_amount_paisha, due_amount_paisha, payment_status, idempotency_key, purchase_date, notes, created_by_user_id, " +
                "is_reversed, reversal_reason, reversed_at, created_at, updated_at FROM purchases WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Purchase> findByPurchaseNumber(Connection conn, String purchaseNumber) throws SQLException {
        String sql = "SELECT id, business_id, purchase_number, supplier_id, total_amount_paisha, discount_paisha, net_amount_paisha, " +
                "paid_amount_paisha, due_amount_paisha, payment_status, idempotency_key, purchase_date, notes, created_by_user_id, " +
                "is_reversed, reversal_reason, reversed_at, created_at, updated_at FROM purchases WHERE purchase_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, purchaseNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Purchase> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException {
        String sql = "SELECT id, business_id, purchase_number, supplier_id, total_amount_paisha, discount_paisha, net_amount_paisha, " +
                "paid_amount_paisha, due_amount_paisha, payment_status, idempotency_key, purchase_date, notes, created_by_user_id, " +
                "is_reversed, reversal_reason, reversed_at, created_at, updated_at FROM purchases WHERE business_id = ? AND idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<PurchaseItem> findItemsByPurchaseId(Connection conn, long purchaseId) throws SQLException {
        List<PurchaseItem> items = new ArrayList<>();
        String sql = "SELECT id, purchase_id, product_id, quantity, unit_cost_paisha, line_total_paisha FROM purchase_items WHERE purchase_id = ? ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new PurchaseItem(
                            rs.getLong("id"),
                            rs.getLong("purchase_id"),
                            rs.getLong("product_id"),
                            rs.getInt("quantity"),
                            Money.ofPaisha(rs.getLong("unit_cost_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha"))
                    ));
                }
            }
        }
        return items;
    }

    @Override
    public List<PurchaseListItemDto> searchPurchases(Connection conn, long businessId, String query, Long supplierId,
                                                    String paymentStatus, LocalDate startDate, LocalDate endDate) throws SQLException {
        List<PurchaseListItemDto> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.purchase_number, p.supplier_id, s.name as supplier_name, p.purchase_date, " +
                        "p.total_amount_paisha, p.discount_paisha, p.net_amount_paisha, p.paid_amount_paisha, " +
                        "p.due_amount_paisha, p.payment_status, p.is_reversed, " +
                        "(SELECT COUNT(*) FROM purchase_items pi WHERE pi.purchase_id = p.id) as item_count " +
                        "FROM purchases p " +
                        "JOIN suppliers s ON p.supplier_id = s.id " +
                        "WHERE p.business_id = ? "
        );

        if (supplierId != null) {
            sql.append("AND p.supplier_id = ? ");
        }
        if (paymentStatus != null && !paymentStatus.isBlank() && !"ALL".equalsIgnoreCase(paymentStatus)) {
            sql.append("AND p.payment_status = ? ");
        }
        if (startDate != null) {
            sql.append("AND date(p.purchase_date) >= ? ");
        }
        if (endDate != null) {
            sql.append("AND date(p.purchase_date) <= ? ");
        }
        boolean hasQuery = query != null && !query.trim().isBlank();
        if (hasQuery) {
            sql.append("AND (p.purchase_number LIKE ? OR s.name LIKE ?) ");
        }
        sql.append("ORDER BY p.purchase_date DESC, p.id DESC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (supplierId != null) {
                ps.setLong(idx++, supplierId);
            }
            if (paymentStatus != null && !paymentStatus.isBlank() && !"ALL".equalsIgnoreCase(paymentStatus)) {
                ps.setString(idx++, paymentStatus.toUpperCase());
            }
            if (startDate != null) {
                ps.setString(idx++, startDate.toString());
            }
            if (endDate != null) {
                ps.setString(idx++, endDate.toString());
            }
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PurchaseListItemDto(
                            rs.getLong("id"),
                            rs.getString("purchase_number"),
                            rs.getLong("supplier_id"),
                            rs.getString("supplier_name"),
                            LocalDateTime.parse(rs.getString("purchase_date").replace(" ", "T")),
                            Money.ofPaisha(rs.getLong("total_amount_paisha")),
                            Money.ofPaisha(rs.getLong("discount_paisha")),
                            Money.ofPaisha(rs.getLong("net_amount_paisha")),
                            Money.ofPaisha(rs.getLong("paid_amount_paisha")),
                            Money.ofPaisha(rs.getLong("due_amount_paisha")),
                            rs.getString("payment_status"),
                            rs.getInt("is_reversed") == 1,
                            rs.getInt("item_count")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public int getNextPurchaseSequence(Connection conn, long businessId, String datePrefix) throws SQLException {
        String pattern = "PUR-" + datePrefix + "-%";
        String sql = "SELECT COUNT(*) FROM purchases WHERE business_id = ? AND purchase_number LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) + 1;
                }
            }
        }
        return 1;
    }

    @Override
    public void voidPurchase(Connection conn, long purchaseId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException {
        String sql = "UPDATE purchases SET is_reversed = 1, reversal_reason = ?, reversed_at = ?, updated_at = datetime('now', 'localtime') " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, reversedAt.toString().replace("T", " "));
            ps.setLong(3, purchaseId);
            ps.setLong(4, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void updateNotes(Connection conn, long purchaseId, long businessId, String notes) throws SQLException {
        String sql = "UPDATE purchases SET notes = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (notes != null && !notes.isBlank()) {
                ps.setString(1, notes.trim());
            } else {
                ps.setNull(1, Types.VARCHAR);
            }
            ps.setLong(2, purchaseId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean hasActiveLinkedPayments(Connection conn, long purchaseId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM payment_allocations pa " +
                "JOIN payments py ON pa.payment_id = py.id " +
                "WHERE pa.purchase_id = ? AND py.is_reversed = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    @Override
    public boolean deleteVoided(Connection conn, long purchaseId, long businessId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payment_allocations WHERE purchase_id = ?")) {
            ps.setLong(1, purchaseId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM stock_movements WHERE business_id = ? AND reference_type = 'PURCHASE' AND reference_id = ?")) {
            ps.setLong(1, businessId);
            ps.setLong(2, purchaseId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM supplier_ledger WHERE business_id = ? AND reference_id = ? AND entry_type IN ('PURCHASE_BILL', 'BILL_REVERSAL')")) {
            ps.setLong(1, businessId);
            ps.setLong(2, purchaseId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM purchases WHERE id = ? AND business_id = ? AND is_reversed = 1")) {
            ps.setLong(1, purchaseId);
            ps.setLong(2, businessId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public List<Purchase> findUnpaidPurchasesBySupplierId(Connection conn, long supplierId) throws SQLException {
        List<Purchase> purchases = new ArrayList<>();
        String sql = "SELECT id, business_id, purchase_number, supplier_id, total_amount_paisha, discount_paisha, net_amount_paisha, " +
                "paid_amount_paisha, due_amount_paisha, payment_status, idempotency_key, purchase_date, notes, created_by_user_id, " +
                "is_reversed, reversal_reason, reversed_at, created_at, updated_at FROM purchases " +
                "WHERE supplier_id = ? AND is_reversed = 0 AND due_amount_paisha > 0 " +
                "ORDER BY purchase_date ASC, id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    purchases.add(mapRow(rs));
                }
            }
        }
        return purchases;
    }

    @Override
    public void updatePaymentStatus(Connection conn, long purchaseId, Money paidAmount, Money dueAmount, String paymentStatus) throws SQLException {
        String sql = "UPDATE purchases SET paid_amount_paisha = ?, due_amount_paisha = ?, payment_status = ?, updated_at = datetime('now', 'localtime') WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paidAmount.paisha());
            ps.setLong(2, dueAmount.paisha());
            ps.setString(3, paymentStatus);
            ps.setLong(4, purchaseId);
            ps.executeUpdate();
        }
    }

    private Purchase mapRow(ResultSet rs) throws SQLException {
        String revAtStr = rs.getString("reversed_at");
        LocalDateTime revAt = (revAtStr != null && !revAtStr.isBlank()) ? LocalDateTime.parse(revAtStr.replace(" ", "T")) : null;

        return new Purchase(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("purchase_number"),
                rs.getLong("supplier_id"),
                Money.ofPaisha(rs.getLong("total_amount_paisha")),
                Money.ofPaisha(rs.getLong("discount_paisha")),
                Money.ofPaisha(rs.getLong("net_amount_paisha")),
                Money.ofPaisha(rs.getLong("paid_amount_paisha")),
                Money.ofPaisha(rs.getLong("due_amount_paisha")),
                rs.getString("payment_status"),
                rs.getString("idempotency_key"),
                LocalDateTime.parse(rs.getString("purchase_date").replace(" ", "T")),
                rs.getString("notes"),
                rs.getLong("created_by_user_id"),
                rs.getInt("is_reversed") == 1,
                rs.getString("reversal_reason"),
                revAt,
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T"))
        );
    }
}


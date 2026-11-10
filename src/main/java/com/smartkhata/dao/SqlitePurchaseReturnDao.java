package com.smartkhata.dao;

import com.smartkhata.model.dto.PurchaseReturnListItemDto;
import com.smartkhata.model.entity.PurchaseReturn;
import com.smartkhata.model.entity.PurchaseReturnItem;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class SqlitePurchaseReturnDao implements PurchaseReturnDao {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PurchaseReturn saveHeader(Connection conn, PurchaseReturn pr) throws SQLException {
        String sql = "INSERT INTO purchase_returns (business_id, return_number, supplier_id, grn_id, " +
                "return_date, total_return_paisha, reason, status, created_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, pr.businessId());
            ps.setString(2, pr.returnNumber());
            ps.setLong(3, pr.supplierId());
            if (pr.grnId() != null) ps.setLong(4, pr.grnId()); else ps.setNull(4, Types.INTEGER);
            ps.setString(5, (pr.returnDate() != null ? pr.returnDate() : LocalDateTime.now()).format(ISO_DT));
            ps.setLong(6, pr.totalReturnAmount().paisha());
            ps.setString(7, pr.reason());
            ps.setString(8, pr.status() != null ? pr.status() : "POSTED");
            ps.setLong(9, pr.createdByUserId());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return pr.withId(rs.getLong(1));
            }
        }
        return pr;
    }

    @Override
    public void saveItems(Connection conn, long returnId, List<PurchaseReturnItem> items) throws SQLException {
        String sql = "INSERT INTO purchase_return_items (purchase_return_id, grn_item_id, product_id, " +
                "return_base_qty, unit_cost_paisha, line_total_paisha, notes) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PurchaseReturnItem item : items) {
                ps.setLong(1, returnId);
                if (item.grnItemId() != null) ps.setLong(2, item.grnItemId()); else ps.setNull(2, Types.INTEGER);
                ps.setLong(3, item.productId());
                ps.setInt(4, item.returnBaseQty());
                ps.setLong(5, item.unitCost().paisha());
                ps.setLong(6, item.lineTotal().paisha());
                if (item.notes() != null && !item.notes().isBlank()) ps.setString(7, item.notes().trim()); else ps.setNull(7, Types.VARCHAR);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public List<PurchaseReturnListItemDto> findAll(Connection conn, long businessId) throws SQLException {
        String sql = "SELECT pr.*, s.name AS supplier_name, g.grn_number " +
                "FROM purchase_returns pr " +
                "JOIN suppliers s ON s.id = pr.supplier_id " +
                "LEFT JOIN goods_receipt_notes g ON g.id = pr.grn_id " +
                "WHERE pr.business_id = ? ORDER BY pr.return_date DESC, pr.id DESC";
        List<PurchaseReturnListItemDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PurchaseReturnListItemDto(
                            rs.getLong("id"), rs.getString("return_number"), rs.getLong("supplier_id"),
                            rs.getString("supplier_name"),
                            rs.getObject("grn_id") != null ? rs.getLong("grn_id") : null,
                            rs.getString("grn_number"),
                            LocalDateTime.parse(rs.getString("return_date").replace(" ", "T")),
                            Money.ofPaisha(rs.getLong("total_return_paisha")),
                            rs.getString("reason")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public int getNextSequence(Connection conn, long businessId, String datePrefix) throws SQLException {
        String sql = "SELECT COUNT(*) + 1 FROM purchase_returns WHERE business_id = ? AND return_number LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, "PRN-" + datePrefix + "-%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 1;
    }
}


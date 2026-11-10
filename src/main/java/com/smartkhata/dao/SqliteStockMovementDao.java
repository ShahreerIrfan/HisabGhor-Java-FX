package com.smartkhata.dao;

import com.smartkhata.model.entity.StockMovement;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteStockMovementDao implements StockMovementDao {

    @Override
    public StockMovement save(Connection conn, StockMovement movement) throws SQLException {
        String sql = "INSERT INTO stock_movements (business_id, product_id, movement_type, quantity, unit_cost_paisha, " +
                "reference_type, reference_id, notes, created_by_user_id, source_doc_type, source_doc_number, grn_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, movement.businessId());
            ps.setLong(2, movement.productId());
            ps.setString(3, movement.movementType());
            ps.setInt(4, movement.quantity());
            ps.setLong(5, movement.unitCost() != null ? movement.unitCost().paisha() : 0L);
            ps.setString(6, movement.referenceType());
            if (movement.referenceId() != null) ps.setLong(7, movement.referenceId()); else ps.setNull(7, Types.INTEGER);
            ps.setString(8, movement.notes());
            if (movement.createdByUserId() != null) ps.setLong(9, movement.createdByUserId()); else ps.setNull(9, Types.INTEGER);
            if (movement.sourceDocType() != null) ps.setString(10, movement.sourceDocType()); else ps.setNull(10, Types.VARCHAR);
            if (movement.sourceDocNumber() != null) ps.setString(11, movement.sourceDocNumber()); else ps.setNull(11, Types.VARCHAR);
            if (movement.grnId() != null) ps.setLong(12, movement.grnId()); else ps.setNull(12, Types.INTEGER);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return movement.withId(rs.getLong(1));
                }
            }
        }
        return movement;
    }

    @Override
    public Optional<StockMovement> findById(Connection conn, long movementId) throws SQLException {
        String sql = "SELECT id, business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, " +
                "reference_id, notes, created_by_user_id, created_at, source_doc_type, source_doc_number, grn_id " +
                "FROM stock_movements WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movementId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<StockMovement> findByProductId(Connection conn, long productId) throws SQLException {
        List<StockMovement> list = new ArrayList<>();
        String sql = "SELECT id, business_id, product_id, movement_type, quantity, unit_cost_paisha, reference_type, " +
                "reference_id, notes, created_by_user_id, created_at, source_doc_type, source_doc_number, grn_id " +
                "FROM stock_movements WHERE product_id = ? ORDER BY created_at ASC, id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    @Override
    public void updateNotes(Connection conn, long movementId, long businessId, String notes) throws SQLException {
        String sql = "UPDATE stock_movements SET notes = ? WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, notes);
            ps.setLong(2, movementId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    private StockMovement mapRow(ResultSet rs) throws SQLException {
        Long refId = rs.getObject("reference_id") != null ? rs.getLong("reference_id") : null;
        Long userId = rs.getObject("created_by_user_id") != null ? rs.getLong("created_by_user_id") : null;
        Long grnId = rs.getObject("grn_id") != null ? rs.getLong("grn_id") : null;
        return new StockMovement(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getLong("product_id"),
                rs.getString("movement_type"),
                rs.getInt("quantity"),
                Money.ofPaisha(rs.getLong("unit_cost_paisha")),
                rs.getString("reference_type"),
                refId,
                rs.getString("notes"),
                userId,
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                rs.getString("source_doc_type"),
                rs.getString("source_doc_number"),
                grnId
        );
    }
}

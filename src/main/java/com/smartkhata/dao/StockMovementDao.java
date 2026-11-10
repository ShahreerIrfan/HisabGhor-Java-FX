package com.smartkhata.dao;

import com.smartkhata.model.entity.StockMovement;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface StockMovementDao {
    StockMovement save(Connection conn, StockMovement movement) throws SQLException;
    Optional<StockMovement> findById(Connection conn, long movementId) throws SQLException;
    List<StockMovement> findByProductId(Connection conn, long productId) throws SQLException;
    void updateNotes(Connection conn, long movementId, long businessId, String notes) throws SQLException;
}

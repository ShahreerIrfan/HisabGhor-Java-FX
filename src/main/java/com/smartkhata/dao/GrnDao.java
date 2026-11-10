package com.smartkhata.dao;

import com.smartkhata.model.dto.GrnItemViewDto;
import com.smartkhata.model.dto.GrnListItemDto;
import com.smartkhata.model.entity.GoodsReceiptItem;
import com.smartkhata.model.entity.GoodsReceiptNote;
import com.smartkhata.model.entity.InventoryCostHistory;
import com.smartkhata.model.enums.GrnStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GrnDao {
    GoodsReceiptNote saveHeader(Connection conn, GoodsReceiptNote grn) throws SQLException;
    List<GoodsReceiptItem> saveItems(Connection conn, long grnId, List<GoodsReceiptItem> items) throws SQLException;
    Optional<GoodsReceiptNote> findById(Connection conn, long id) throws SQLException;
    Optional<GoodsReceiptNote> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException;
    List<GoodsReceiptItem> findItemsByGrnId(Connection conn, long grnId) throws SQLException;
    List<GrnItemViewDto> findItemViewsByGrnId(Connection conn, long grnId) throws SQLException;
    List<GrnListItemDto> searchGrns(Connection conn, long businessId, String query, Long supplierId,
                                    Long poId, GrnStatus status, LocalDate startDate, LocalDate endDate) throws SQLException;
    int getNextGrnSequence(Connection conn, long businessId, String datePrefix) throws SQLException;
    void markReversed(Connection conn, long grnId, long businessId, long userId, String reason, LocalDateTime reversedAt) throws SQLException;
    void saveCostHistory(Connection conn, InventoryCostHistory history) throws SQLException;
    List<InventoryCostHistory> findCostHistoryByProduct(Connection conn, long businessId, long productId) throws SQLException;
}


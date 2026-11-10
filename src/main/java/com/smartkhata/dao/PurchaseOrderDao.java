package com.smartkhata.dao;

import com.smartkhata.model.dto.PurchaseOrderItemViewDto;
import com.smartkhata.model.dto.PurchaseOrderListItemDto;
import com.smartkhata.model.entity.PurchaseOrder;
import com.smartkhata.model.entity.PurchaseOrderItem;
import com.smartkhata.model.enums.PurchaseOrderStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderDao {
    PurchaseOrder saveHeader(Connection conn, PurchaseOrder order) throws SQLException;
    void replaceItems(Connection conn, long poId, List<PurchaseOrderItem> items) throws SQLException;
    Optional<PurchaseOrder> findById(Connection conn, long id) throws SQLException;
    List<PurchaseOrderItem> findItemsByOrderId(Connection conn, long poId) throws SQLException;
    List<PurchaseOrderItemViewDto> findItemViewsByOrderId(Connection conn, long poId) throws SQLException;
    List<PurchaseOrderListItemDto> searchOrders(Connection conn, long businessId, String query,
                                                Long supplierId, PurchaseOrderStatus status,
                                                LocalDate startDate, LocalDate endDate) throws SQLException;
    int getNextPoSequence(Connection conn, long businessId, String datePrefix) throws SQLException;
    void updateStatus(Connection conn, long poId, long businessId, PurchaseOrderStatus status) throws SQLException;
    void addItemReceivedQty(Connection conn, long poItemId, int deltaReceivedQty) throws SQLException;
}


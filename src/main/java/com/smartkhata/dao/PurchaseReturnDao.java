package com.smartkhata.dao;

import com.smartkhata.model.dto.PurchaseReturnListItemDto;
import com.smartkhata.model.entity.PurchaseReturn;
import com.smartkhata.model.entity.PurchaseReturnItem;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface PurchaseReturnDao {
    PurchaseReturn saveHeader(Connection conn, PurchaseReturn pr) throws SQLException;
    void saveItems(Connection conn, long returnId, List<PurchaseReturnItem> items) throws SQLException;
    List<PurchaseReturnListItemDto> findAll(Connection conn, long businessId) throws SQLException;
    int getNextSequence(Connection conn, long businessId, String datePrefix) throws SQLException;
}


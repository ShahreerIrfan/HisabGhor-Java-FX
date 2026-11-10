package com.smartkhata.dao;

import com.smartkhata.model.dto.AuditLogListItemDto;
import com.smartkhata.model.entity.AuditLog;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface AuditLogDao {
    AuditLog save(Connection conn, AuditLog log) throws SQLException;
    List<AuditLogListItemDto> search(Connection conn, long businessId, String query, int limit) throws SQLException;
    void delete(Connection conn, long id, long businessId) throws SQLException;
    void clear(Connection conn, long businessId) throws SQLException;
}

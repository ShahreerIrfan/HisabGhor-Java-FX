package com.smartkhata.dao;

import com.smartkhata.model.dto.AuditLogListItemDto;
import com.smartkhata.model.entity.AuditLog;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SqliteAuditLogDao implements AuditLogDao {

    @Override
    public AuditLog save(Connection conn, AuditLog log) throws SQLException {
        String sql = "INSERT INTO audit_logs (business_id, user_id, action_type, entity_name, entity_id, change_summary) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, log.businessId());
            if (log.userId() != null) {
                ps.setLong(2, log.userId());
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setString(3, log.actionType());
            ps.setString(4, log.entityName());
            if (log.entityId() != null) {
                ps.setLong(5, log.entityId());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setString(6, log.changeSummary());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return log.withId(rs.getLong(1));
                }
            }
        }
        return log;
    }

    @Override
    public List<AuditLogListItemDto> search(Connection conn, long businessId, String query, int limit) throws SQLException {
        List<AuditLogListItemDto> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT al.id, al.action_type, al.entity_name, al.entity_id, al.change_summary, al.created_at, u.username " +
                "FROM audit_logs al LEFT JOIN users u ON al.user_id = u.id " +
                "WHERE al.business_id = ?"
        );

        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            sql.append(" AND (al.action_type LIKE ? OR al.entity_name LIKE ? OR al.change_summary LIKE ? OR u.username LIKE ?)");
        }
        sql.append(" ORDER BY al.id DESC LIMIT ?");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
            }
            ps.setInt(idx, Math.max(1, limit));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String actionType = rs.getString("action_type");
                    String entityName = rs.getString("entity_name");
                    long entityIdVal = rs.getLong("entity_id");
                    Long entityId = rs.wasNull() ? null : entityIdVal;
                    String changeSummary = rs.getString("change_summary");
                    String username = rs.getString("username");
                    String createdAtStr = rs.getString("created_at");
                    LocalDateTime createdAt = (createdAtStr != null && !createdAtStr.isBlank())
                            ? LocalDateTime.parse(createdAtStr.replace(" ", "T"))
                            : LocalDateTime.now();

                    list.add(new AuditLogListItemDto(id, actionType, entityName, entityId, changeSummary, username, createdAt));
                }
            }
        }
        return list;
    }

    @Override
    public void delete(Connection conn, long id, long businessId) throws SQLException {
        String sql = "DELETE FROM audit_logs WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void clear(Connection conn, long businessId) throws SQLException {
        String sql = "DELETE FROM audit_logs WHERE business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.executeUpdate();
        }
    }
}

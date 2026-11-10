package com.smartkhata.model.entity;

import java.time.LocalDateTime;

public record AuditLog(
        Long id,
        long businessId,
        Long userId,
        String actionType,
        String entityName,
        Long entityId,
        String changeSummary,
        LocalDateTime createdAt
) {
    public AuditLog withId(Long newId) {
        return new AuditLog(newId, businessId, userId, actionType, entityName, entityId, changeSummary, createdAt);
    }
}

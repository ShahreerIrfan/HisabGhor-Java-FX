package com.smartkhata.model.dto;

import java.time.LocalDateTime;

public record AuditLogListItemDto(
        Long id,
        String actionType,
        String entityName,
        Long entityId,
        String changeSummary,
        String username,
        LocalDateTime createdAt
) {}

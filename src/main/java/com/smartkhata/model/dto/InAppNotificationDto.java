package com.smartkhata.model.dto;

import java.time.LocalDateTime;

/**
 * Represents an in-app proactive notification (e.g. low-stock warnings, customer due reminders).
 */
public record InAppNotificationDto(
        String id,
        String type,        // "LOW_STOCK", "DUE_REMINDER", "SYSTEM"
        String title,
        String message,
        String severity,    // "WARNING", "INFO", "DANGER"
        LocalDateTime timestamp,
        Long targetEntityId // Optional reference e.g. productId or customerId
) {}


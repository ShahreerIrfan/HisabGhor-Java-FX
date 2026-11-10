package com.smartkhata.model.dto;

/**
 * Encapsulates a manual stock adjustment.
 * A positive quantityDelta increases stock; a negative quantityDelta decreases stock.
 * Reason and actingUserId are strictly mandatory for auditable accountability.
 */
public record StockAdjustmentDto(
        long productId,
        int quantityDelta,
        String reason,
        long actingUserId
) {}


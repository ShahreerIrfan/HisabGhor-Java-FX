package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record StockMovement(
        Long id,
        long businessId,
        long productId,
        String movementType, // OPENING, PURCHASE_IN, SALE_OUT, MANUAL_ADJUSTMENT, RETURN_IN, RETURN_OUT
        int quantity,
        Money unitCost,
        String referenceType,
        Long referenceId,
        String notes,
        Long createdByUserId,
        LocalDateTime createdAt,
        String sourceDocType,
        String sourceDocNumber,
        Long grnId
) {
    public StockMovement(
            Long id,
            long businessId,
            long productId,
            String movementType,
            int quantity,
            Money unitCost,
            String referenceType,
            Long referenceId,
            String notes,
            Long createdByUserId,
            LocalDateTime createdAt
    ) {
        this(id, businessId, productId, movementType, quantity, unitCost,
                referenceType, referenceId, notes, createdByUserId, createdAt,
                null, null, null);
    }

    public StockMovement withId(Long newId) {
        return new StockMovement(newId, businessId, productId, movementType, quantity, unitCost,
                referenceType, referenceId, notes, createdByUserId, createdAt,
                sourceDocType, sourceDocNumber, grnId);
    }
}

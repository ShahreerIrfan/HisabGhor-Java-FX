package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

import java.time.LocalDateTime;

public record PurchaseReturn(
        Long id,
        long businessId,
        String returnNumber,
        long supplierId,
        Long grnId,
        LocalDateTime returnDate,
        Money totalReturnAmount,
        String reason,
        String status,
        long createdByUserId,
        LocalDateTime createdAt
) {
    public PurchaseReturn withId(Long newId) {
        return new PurchaseReturn(newId, businessId, returnNumber, supplierId, grnId,
                returnDate, totalReturnAmount, reason, status, createdByUserId, createdAt);
    }
}


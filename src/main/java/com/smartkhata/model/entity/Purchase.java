package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record Purchase(
        Long id,
        long businessId,
        String purchaseNumber,
        long supplierId,
        Money totalAmount,
        Money discount,
        Money netAmount,
        Money paidAmount,
        Money dueAmount,
        String paymentStatus, // PAID, PARTIAL, DUE
        String idempotencyKey,
        LocalDateTime purchaseDate,
        String notes,
        long createdByUserId,
        boolean isReversed,
        String reversalReason,
        LocalDateTime reversedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public Purchase withId(Long newId) {
        return new Purchase(newId, businessId, purchaseNumber, supplierId, totalAmount, discount, netAmount,
                paidAmount, dueAmount, paymentStatus, idempotencyKey, purchaseDate, notes, createdByUserId,
                isReversed, reversalReason, reversedAt, createdAt, updatedAt);
    }
}


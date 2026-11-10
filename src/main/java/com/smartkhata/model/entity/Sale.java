package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record Sale(
        Long id,
        long businessId,
        String invoiceNumber,
        Long customerId,
        Money totalAmount,
        Money discount,
        Money netAmount,
        Money paidAmount,
        Money dueAmount,
        String paymentStatus, // PAID, PARTIAL, BAKI
        String idempotencyKey,
        LocalDateTime invoiceDate,
        String notes,
        long createdByUserId,
        boolean isReversed,
        String reversalReason,
        LocalDateTime reversedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Money totalCogs,
        Money grossProfit
) {
    public Sale(
            Long id,
            long businessId,
            String invoiceNumber,
            Long customerId,
            Money totalAmount,
            Money discount,
            Money netAmount,
            Money paidAmount,
            Money dueAmount,
            String paymentStatus,
            String idempotencyKey,
            LocalDateTime invoiceDate,
            String notes,
            long createdByUserId,
            boolean isReversed,
            String reversalReason,
            LocalDateTime reversedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(id, businessId, invoiceNumber, customerId, totalAmount, discount, netAmount, paidAmount,
                dueAmount, paymentStatus, idempotencyKey, invoiceDate, notes, createdByUserId,
                isReversed, reversalReason, reversedAt, createdAt, updatedAt, Money.ZERO, netAmount);
    }

    public Sale withId(Long newId) {
        return new Sale(newId, businessId, invoiceNumber, customerId, totalAmount, discount, netAmount,
                paidAmount, dueAmount, paymentStatus, idempotencyKey, invoiceDate, notes, createdByUserId,
                isReversed, reversalReason, reversedAt, createdAt, updatedAt, totalCogs, grossProfit);
    }
}

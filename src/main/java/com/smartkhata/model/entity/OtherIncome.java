package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

/**
 * Auxiliary business income record (e.g. scrap sales, service fee, commissions).
 * Separated completely from sales POS invoices or customer credit receivables.
 */
public record OtherIncome(
        Long id,
        long businessId,
        String sourceName,
        Money amount,
        LocalDateTime incomeDate,
        String paymentMethod,
        String notes,
        long createdByUserId,
        boolean isReversed,
        String reversalReason,
        LocalDateTime reversedAt,
        LocalDateTime createdAt
) {
    public OtherIncome {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Source name cannot be empty");
        }
        if (amount == null || amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Income amount must be positive");
        }
        if (incomeDate == null) {
            throw new IllegalArgumentException("Income date cannot be null");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("Payment method cannot be empty");
        }
    }
}


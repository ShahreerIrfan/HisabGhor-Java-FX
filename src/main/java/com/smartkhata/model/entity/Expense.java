package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

/**
 * Business expense record representing operational overhead.
 * Separated completely from supplier purchase payables or inventory.
 */
public record Expense(
        Long id,
        long businessId,
        String category,
        Money amount,
        LocalDateTime expenseDate,
        String paymentMethod,
        String payeeName,
        String notes,
        long createdByUserId,
        boolean isReversed,
        String reversalReason,
        LocalDateTime reversedAt,
        LocalDateTime createdAt
) {
    public Expense {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Expense category cannot be empty");
        }
        if (amount == null || amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Expense amount must be positive");
        }
        if (expenseDate == null) {
            throw new IllegalArgumentException("Expense date cannot be null");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("Payment method cannot be empty");
        }
    }
}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record ExpenseCreateDto(
        long businessId,
        String category,
        Money amount,
        LocalDateTime expenseDate,
        String paymentMethod,
        String payeeName,
        String notes,
        long actingUserId
) {
    public ExpenseCreateDto {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Expense category is required");
        }
        if (amount == null || amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Expense amount must be positive");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("Payment method is required");
        }
    }
}


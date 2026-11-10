package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record ExpenseUpdateDto(
        long expenseId,
        String category,
        Money amount,
        LocalDateTime expenseDate,
        String paymentMethod,
        String payeeName,
        String notes
) {
    public ExpenseUpdateDto {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Expense category is required");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Expense amount must be positive");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("Payment method is required");
        }
    }
}


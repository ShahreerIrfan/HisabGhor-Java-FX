package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record OtherIncomeCreateDto(
        long businessId,
        String sourceName,
        Money amount,
        LocalDateTime incomeDate,
        String paymentMethod,
        String notes,
        long actingUserId
) {
    public OtherIncomeCreateDto {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Source name is required");
        }
        if (amount == null || amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Income amount must be positive");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("Payment method is required");
        }
    }
}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record ExpenseCategorySummaryDto(
        String category,
        Money totalAmount,
        int count
) {
}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record OutstandingDuesSummary(
        Money totalDues,
        int customersWithDueCount
) {
    public static OutstandingDuesSummary empty() {
        return new OutstandingDuesSummary(Money.ZERO, 0);
    }
}


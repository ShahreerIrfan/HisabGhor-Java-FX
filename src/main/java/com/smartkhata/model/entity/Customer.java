package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record Customer(
        Long id,
        long businessId,
        String name,
        String phone,
        String address,
        Money creditLimit,
        Money openingBalance,
        boolean isArchived,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public Customer withId(Long newId) {
        return new Customer(newId, businessId, name, phone, address, creditLimit, openingBalance, isArchived, notes, createdAt, updatedAt);
    }
}


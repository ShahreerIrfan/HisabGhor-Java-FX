package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record Supplier(
        Long id,
        long businessId,
        String name,
        String contactPerson,
        String phone,
        String address,
        Money openingBalance,
        boolean isArchived,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String supplierCode,
        String email,
        String paymentTerms,
        int creditPeriodDays,
        boolean isActive
) {
    /**
     * Backward-compatible constructor preserving V1/V2 signatures.
     */
    public Supplier(
            Long id,
            long businessId,
            String name,
            String contactPerson,
            String phone,
            String address,
            Money openingBalance,
            boolean isArchived,
            String notes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(id, businessId, name, contactPerson, phone, address, openingBalance, isArchived, notes,
                createdAt, updatedAt, null, null, null, 0, !isArchived);
    }

    public Supplier withId(Long newId) {
        return new Supplier(newId, businessId, name, contactPerson, phone, address, openingBalance, isArchived,
                notes, createdAt, updatedAt, supplierCode, email, paymentTerms, creditPeriodDays, isActive);
    }

    public Supplier withSupplierCode(String newCode) {
        return new Supplier(id, businessId, name, contactPerson, phone, address, openingBalance, isArchived,
                notes, createdAt, updatedAt, newCode, email, paymentTerms, creditPeriodDays, isActive);
    }
}

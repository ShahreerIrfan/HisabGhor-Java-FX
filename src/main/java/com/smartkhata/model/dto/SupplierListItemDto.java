package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record SupplierListItemDto(
        long id,
        String name,
        String contactPerson,
        String phone,
        String address,
        Money currentPayable,
        boolean isArchived,
        LocalDateTime createdAt,
        String supplierCode,
        String email,
        String paymentTerms,
        int creditPeriodDays,
        boolean isActive
) {
    public SupplierListItemDto(
            long id,
            String name,
            String contactPerson,
            String phone,
            String address,
            Money currentPayable,
            boolean isArchived,
            LocalDateTime createdAt
    ) {
        this(id, name, contactPerson, phone, address, currentPayable, isArchived, createdAt,
                null, null, null, 0, !isArchived);
    }
}

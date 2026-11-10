package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record SupplierCreateDto(
        long businessId,
        String name,
        String contactPerson,
        String phone,
        String address,
        Money openingBalance,
        String notes,
        String supplierCode,
        String email,
        String paymentTerms,
        int creditPeriodDays,
        boolean isActive
) {
    public SupplierCreateDto(
            long businessId,
            String name,
            String contactPerson,
            String phone,
            String address,
            Money openingBalance,
            String notes
    ) {
        this(businessId, name, contactPerson, phone, address, openingBalance, notes,
                null, null, null, 0, true);
    }
}

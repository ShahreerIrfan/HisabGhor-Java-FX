package com.smartkhata.model.dto;

public record SupplierUpdateDto(
        long supplierId,
        String name,
        String contactPerson,
        String phone,
        String address,
        String notes,
        String supplierCode,
        String email,
        String paymentTerms,
        int creditPeriodDays,
        boolean isActive
) {
    public SupplierUpdateDto(
            long supplierId,
            String name,
            String contactPerson,
            String phone,
            String address,
            String notes
    ) {
        this(supplierId, name, contactPerson, phone, address, notes,
                null, null, null, 0, true);
    }
}

package com.smartkhata.model.entity;

import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PurchaseOrder(
        Long id,
        long businessId,
        String poNumber,
        long supplierId,
        LocalDateTime poDate,
        LocalDate expectedDeliveryDate,
        PurchaseOrderStatus status,
        Money subtotal,
        Money discount,
        Money tax,
        Money additionalCharges,
        Money grandTotal,
        String supplierReference,
        String notes,
        long createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public PurchaseOrder withId(Long newId) {
        return new PurchaseOrder(newId, businessId, poNumber, supplierId, poDate, expectedDeliveryDate,
                status, subtotal, discount, tax, additionalCharges, grandTotal, supplierReference,
                notes, createdByUserId, createdAt, updatedAt);
    }
}


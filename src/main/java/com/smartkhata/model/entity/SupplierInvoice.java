package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SupplierInvoice(
        Long id,
        long businessId,
        String invoiceNumber,
        String supplierBillNo,
        long supplierId,
        Long purchaseOrderId,
        Long grnId,
        LocalDateTime invoiceDate,
        LocalDate dueDate,
        Money subtotal,
        Money discount,
        Money tax,
        Money additionalCharges,
        Money netAmount,
        Money paidAmount,
        Money dueAmount,
        String paymentStatus,
        String matchingStatus,
        Money variance,
        String status,
        String notes,
        String idempotencyKey,
        long createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public SupplierInvoice withId(Long newId) {
        return new SupplierInvoice(newId, businessId, invoiceNumber, supplierBillNo, supplierId,
                purchaseOrderId, grnId, invoiceDate, dueDate, subtotal, discount, tax, additionalCharges,
                netAmount, paidAmount, dueAmount, paymentStatus, matchingStatus, variance, status,
                notes, idempotencyKey, createdByUserId, createdAt, updatedAt);
    }
}


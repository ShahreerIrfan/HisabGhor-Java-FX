package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.util.List;

public record SupplierInvoiceCreateDto(
        long businessId,
        long supplierId,
        Long purchaseOrderId,
        Long grnId,
        String supplierBillNo,
        LocalDate dueDate,
        Money headerDiscount,
        Money tax,
        Money additionalCharges,
        Money initialPaymentAmount,
        String paymentMethod,
        String referenceTrxId,
        boolean approveVariance,
        String notes,
        String idempotencyKey,
        long actingUserId,
        List<SupplierInvoiceItemCreateDto> items
) {}


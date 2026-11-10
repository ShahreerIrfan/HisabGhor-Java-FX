package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SupplierInvoiceListItemDto(
        long id,
        String invoiceNumber,
        String supplierBillNo,
        long supplierId,
        String supplierName,
        Long grnId,
        String grnNumber,
        Long purchaseOrderId,
        String poNumber,
        LocalDateTime invoiceDate,
        LocalDate dueDate,
        Money netAmount,
        Money paidAmount,
        Money dueAmount,
        String paymentStatus,
        String matchingStatus,
        Money variance,
        String status
) {}


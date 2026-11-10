package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record SupplierInvoiceItemViewDto(
        long id,
        long supplierInvoiceId,
        Long grnItemId,
        Long poItemId,
        long productId,
        String productName,
        String baseUnit,
        int invoicedBaseQty,
        Money unitPrice,
        Money variance,
        Money lineTotal,
        String notes
) {}


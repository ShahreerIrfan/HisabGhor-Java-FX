package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record SupplierInvoiceItemCreateDto(
        Long grnItemId,
        Long poItemId,
        long productId,
        int invoicedBaseQty,
        Money unitPrice,
        String notes
) {}


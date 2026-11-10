package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

public record SupplierInvoiceItem(
        Long id,
        long supplierInvoiceId,
        Long grnItemId,
        Long poItemId,
        long productId,
        int invoicedBaseQty,
        Money unitPrice,
        Money variance,
        Money lineTotal,
        String notes
) {
    public SupplierInvoiceItem withInvoiceId(long newInvId) {
        return new SupplierInvoiceItem(id, newInvId, grnItemId, poItemId, productId,
                invoicedBaseQty, unitPrice, variance, lineTotal, notes);
    }
}


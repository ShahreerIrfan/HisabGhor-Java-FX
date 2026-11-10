package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record PaymentAllocationDto(
        long id,
        Long saleId,
        String invoiceNumber,
        Long purchaseId,
        String purchaseNumber,
        Money allocatedAmount,
        Long supplierInvoiceId,
        String supplierInvoiceNumber
) {
    public PaymentAllocationDto(
            long id,
            Long saleId,
            String invoiceNumber,
            Long purchaseId,
            String purchaseNumber,
            Money allocatedAmount
    ) {
        this(id, saleId, invoiceNumber, purchaseId, purchaseNumber, allocatedAmount, null, null);
    }
}

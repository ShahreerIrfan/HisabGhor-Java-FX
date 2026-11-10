package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record PaymentAllocation(
        Long id,
        long paymentId,
        Long saleId,
        Long purchaseId,
        Money allocatedAmount,
        LocalDateTime createdAt,
        Long supplierInvoiceId
) {
    public PaymentAllocation(
            Long id,
            long paymentId,
            Long saleId,
            Long purchaseId,
            Money allocatedAmount,
            LocalDateTime createdAt
    ) {
        this(id, paymentId, saleId, purchaseId, allocatedAmount, createdAt, null);
    }

    public PaymentAllocation withId(Long newId) {
        return new PaymentAllocation(newId, paymentId, saleId, purchaseId, allocatedAmount, createdAt, supplierInvoiceId);
    }
}

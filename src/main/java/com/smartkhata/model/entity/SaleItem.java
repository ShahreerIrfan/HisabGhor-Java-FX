package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

public record SaleItem(
        Long id,
        long saleId,
        long productId,
        int quantity,
        Money unitPrice,
        Money unitCost,
        Money discount,
        Money lineTotal,
        Money grossProfit,
        int returnedQty
) {
    public SaleItem(
            Long id,
            long saleId,
            long productId,
            int quantity,
            Money unitPrice,
            Money unitCost,
            Money discount,
            Money lineTotal
    ) {
        this(id, saleId, productId, quantity, unitPrice, unitCost, discount, lineTotal,
                lineTotal.subtract((unitCost != null ? unitCost : Money.ZERO).multiply(quantity)), 0);
    }

    public SaleItem withSaleId(long newSaleId) {
        return new SaleItem(id, newSaleId, productId, quantity, unitPrice, unitCost, discount, lineTotal, grossProfit, returnedQty);
    }
}

package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

public record PurchaseItem(
        Long id,
        long purchaseId,
        long productId,
        int quantity,
        Money unitCost,
        Money lineTotal
) {
    public PurchaseItem withPurchaseId(long newPurchaseId) {
        return new PurchaseItem(id, newPurchaseId, productId, quantity, unitCost, lineTotal);
    }
}


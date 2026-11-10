package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

public record PurchaseReturnItem(
        Long id,
        long purchaseReturnId,
        Long grnItemId,
        long productId,
        int returnBaseQty,
        Money unitCost,
        Money lineTotal,
        String notes
) {
    public PurchaseReturnItem withReturnId(long newReturnId) {
        return new PurchaseReturnItem(id, newReturnId, grnItemId, productId,
                returnBaseQty, unitCost, lineTotal, notes);
    }
}


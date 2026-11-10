package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

public record PurchaseOrderItem(
        Long id,
        long purchaseOrderId,
        long productId,
        int orderedQty,
        int receivedQty,
        String purchaseUnit,
        int packSize,
        Money tradePrice,
        Money discount,
        Money tax,
        Money lineTotal,
        String notes
) {
    public PurchaseOrderItem withPurchaseOrderId(long orderId) {
        return new PurchaseOrderItem(id, orderId, productId, orderedQty, receivedQty,
                purchaseUnit, packSize, tradePrice, discount, tax, lineTotal, notes);
    }

    public int pendingQty() {
        return Math.max(0, orderedQty - receivedQty);
    }
}


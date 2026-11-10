package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record PurchaseOrderItemViewDto(
        long id,
        long purchaseOrderId,
        long productId,
        String productName,
        String productSku,
        String baseUnit,
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
    public int pendingQty() {
        return Math.max(0, orderedQty - receivedQty);
    }
}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record PurchaseItemDto(
        long productId,
        String productName,
        int quantity,
        Money unitCost,
        Money lineTotal
) {
    public static PurchaseItemDto of(long productId, String productName, int quantity, Money unitCost) {
        Money lineTotal = unitCost.multiply(quantity);
        return new PurchaseItemDto(productId, productName, quantity, unitCost, lineTotal);
    }
}


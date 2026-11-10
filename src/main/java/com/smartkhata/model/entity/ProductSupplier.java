package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

/**
 * Many-to-Many mapping between a Product and a Supplier with independent Trade Price (TP),
 * supplier SKU, MOQ, purchase unit / pack size conversion, preferred flag, and last purchase telemetry.
 */
public record ProductSupplier(
        Long id,
        long businessId,
        long productId,
        long supplierId,
        Money tradePrice,
        String supplierProductCode,
        int minOrderQty,
        String purchaseUnit,
        int packSize,
        boolean isPreferred,
        int leadTimeDays,
        Money lastPurchasePrice,
        LocalDateTime lastPurchaseDate,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public ProductSupplier withId(Long newId) {
        return new ProductSupplier(newId, businessId, productId, supplierId, tradePrice, supplierProductCode,
                minOrderQty, purchaseUnit, packSize, isPreferred, leadTimeDays, lastPurchasePrice,
                lastPurchaseDate, isActive, createdAt, updatedAt);
    }
}


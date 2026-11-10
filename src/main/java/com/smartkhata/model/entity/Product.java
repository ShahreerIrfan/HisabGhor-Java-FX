package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

/**
 * Product master record supporting Base Unit + Purchase Unit / Pack Conversion,
 * Trade Price (TP), Maximum Retail Price (MRP), Default Selling Price,
 * Moving Weighted Average Cost (stored in {@code costPrice}), and Last Purchase Price.
 */
public record Product(
        Long id,
        long businessId,
        Long categoryId,
        String name,
        String barcode,
        String sku,
        String unit,
        Money costPrice,
        Money sellingPrice,
        int lowStockThreshold,
        boolean isArchived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String imagePath,
        Money tradePrice,
        Money mrp,
        Money lastPurchasePrice,
        String purchaseUnit,
        int packSize
) {
    /**
     * Legacy V1 constructor.
     */
    public Product(
            Long id,
            long businessId,
            Long categoryId,
            String name,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int lowStockThreshold,
            boolean isArchived,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(id, businessId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, isArchived, createdAt, updatedAt, null);
    }

    /**
     * Legacy V2 constructor preserving existing tests and seeders.
     */
    public Product(
            Long id,
            long businessId,
            Long categoryId,
            String name,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int lowStockThreshold,
            boolean isArchived,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String imagePath
    ) {
        this(id, businessId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, isArchived, createdAt, updatedAt, imagePath,
                costPrice, sellingPrice, costPrice, unit, 1);
    }

    public Product withId(Long newId) {
        return new Product(newId, businessId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, isArchived, createdAt, updatedAt, imagePath,
                tradePrice, mrp, lastPurchasePrice, purchaseUnit, packSize);
    }
}

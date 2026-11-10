package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

/**
 * DTO for creating a product with Trade Price (TP), MRP, Default Selling Price,
 * Base & Purchase Unit conversion, optional opening stock, and optional image path.
 */
public record ProductCreateDto(
        long businessId,
        Long categoryId,
        String name,
        String barcode,
        String sku,
        String unit,
        Money costPrice,
        Money sellingPrice,
        int lowStockThreshold,
        int openingStock,
        String notes,
        String imagePath,
        Money tradePrice,
        Money mrp,
        String purchaseUnit,
        int packSize
) {
    public ProductCreateDto(
            long businessId,
            Long categoryId,
            String name,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int lowStockThreshold,
            int openingStock,
            String notes
    ) {
        this(businessId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, openingStock, notes, null);
    }

    public ProductCreateDto(
            long businessId,
            Long categoryId,
            String name,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int lowStockThreshold,
            int openingStock,
            String notes,
            String imagePath
    ) {
        this(businessId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, openingStock, notes, imagePath,
                costPrice, sellingPrice, unit, 1);
    }
}

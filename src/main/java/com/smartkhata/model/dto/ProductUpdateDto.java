package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record ProductUpdateDto(
        long productId,
        Long categoryId,
        String name,
        String barcode,
        String sku,
        String unit,
        Money costPrice,
        Money sellingPrice,
        int lowStockThreshold,
        String imagePath,
        Money tradePrice,
        Money mrp,
        String purchaseUnit,
        int packSize
) {
    public ProductUpdateDto(
            long productId,
            Long categoryId,
            String name,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int lowStockThreshold
    ) {
        this(productId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, null);
    }

    public ProductUpdateDto(
            long productId,
            Long categoryId,
            String name,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int lowStockThreshold,
            String imagePath
    ) {
        this(productId, categoryId, name, barcode, sku, unit, costPrice, sellingPrice,
                lowStockThreshold, imagePath, costPrice, sellingPrice, unit, 1);
    }
}

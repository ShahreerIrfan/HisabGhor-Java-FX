package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import com.smartkhata.util.PricingCalculator;

import java.time.LocalDateTime;

public record ProductListItemDto(
        long id,
        String name,
        Long categoryId,
        String categoryName,
        String barcode,
        String sku,
        String unit,
        Money costPrice,
        Money sellingPrice,
        int currentStock,
        int lowStockThreshold,
        boolean isLowStock,
        boolean isArchived,
        LocalDateTime createdAt,
        String imagePath,
        Money tradePrice,
        Money mrp,
        Money lastPurchasePrice,
        String purchaseUnit,
        int packSize
) {
    public ProductListItemDto(
            long id,
            String name,
            Long categoryId,
            String categoryName,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int currentStock,
            int lowStockThreshold,
            boolean isLowStock,
            boolean isArchived,
            LocalDateTime createdAt
    ) {
        this(id, name, categoryId, categoryName, barcode, sku, unit, costPrice, sellingPrice,
                currentStock, lowStockThreshold, isLowStock, isArchived, createdAt, null);
    }

    public ProductListItemDto(
            long id,
            String name,
            Long categoryId,
            String categoryName,
            String barcode,
            String sku,
            String unit,
            Money costPrice,
            Money sellingPrice,
            int currentStock,
            int lowStockThreshold,
            boolean isLowStock,
            boolean isArchived,
            LocalDateTime createdAt,
            String imagePath
    ) {
        this(id, name, categoryId, categoryName, barcode, sku, unit, costPrice, sellingPrice,
                currentStock, lowStockThreshold, isLowStock, isArchived, createdAt, imagePath,
                costPrice, sellingPrice, costPrice, unit, 1);
    }

    public Money effectiveCostBasis() {
        if (costPrice != null && costPrice.isPositive()) {
            return costPrice;
        }
        return tradePrice != null ? tradePrice : Money.ZERO;
    }

    public Money profitAmount() {
        return PricingCalculator.calculateProfitAmount(sellingPrice, effectiveCostBasis());
    }

    public double profitMarginPercent() {
        return PricingCalculator.calculateProfitMarginPercent(sellingPrice, effectiveCostBasis());
    }

    public double markupPercent() {
        return PricingCalculator.calculateMarkupPercent(sellingPrice, effectiveCostBasis());
    }
}

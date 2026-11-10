package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record InventorySummaryDto(
        int totalProducts,
        int totalActiveProducts,
        int totalStockQuantity,
        int lowStockAlertCount,
        int outOfStockCount,
        Money totalInventoryValue
) {
    public InventorySummaryDto(
            int totalActiveProducts,
            int totalStockQuantity,
            int lowStockAlertCount,
            Money totalInventoryValue
    ) {
        this(totalActiveProducts, totalActiveProducts, totalStockQuantity, lowStockAlertCount, 0, totalInventoryValue);
    }
}

package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record DashboardKPIs(
        Money totalSales,
        double salesGrowthPercent,
        Money dueCollection,
        double collectionGrowthPercent,
        Money expenses,
        double expenseGrowthPercent,
        Money inventoryValue,
        int totalStockItemsCount
) {
    public static DashboardKPIs empty() {
        return new DashboardKPIs(Money.ZERO, 0.0, Money.ZERO, 0.0, Money.ZERO, 0.0, Money.ZERO, 0);
    }
}


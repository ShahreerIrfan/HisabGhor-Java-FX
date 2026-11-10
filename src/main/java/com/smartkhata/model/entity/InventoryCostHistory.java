package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

import java.time.LocalDateTime;

public record InventoryCostHistory(
        Long id,
        long businessId,
        long productId,
        Long grnId,
        Long grnItemId,
        int previousQty,
        Money previousAvgCost,
        int receivedBaseQty,
        Money receivedUnitCost,
        int newQty,
        Money newAvgCost,
        LocalDateTime changedAt,
        Long changedByUserId,
        String notes
) {}


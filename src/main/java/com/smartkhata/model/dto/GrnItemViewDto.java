package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record GrnItemViewDto(
        long id,
        long grnId,
        Long poItemId,
        long productId,
        String productName,
        String baseUnit,
        int orderedQty,
        int receivedQty,
        int acceptedQty,
        int rejectedQty,
        int damagedQty,
        String purchaseUnit,
        int packSize,
        int acceptedBaseQty,
        Money unitTradePrice,
        Money discount,
        Money allocatedExtraCost,
        Money effectiveBaseUnitCost,
        Money lineTotal,
        String batchOrRemark
) {}


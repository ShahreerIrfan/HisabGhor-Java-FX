package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record PurchaseOrderItemCreateDto(
        long productId,
        int orderedQty,
        String purchaseUnit,
        int packSize,
        Money tradePrice,
        Money discount,
        Money tax,
        String notes
) {}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record GrnItemCreateDto(
        Long poItemId,
        long productId,
        int receivedQty,
        int acceptedQty,
        int rejectedQty,
        int damagedQty,
        String purchaseUnit,
        int packSize,
        Money unitTradePrice,
        Money discount,
        String batchOrRemark
) {}


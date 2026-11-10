package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record PurchaseReturnItemCreateDto(
        Long grnItemId,
        long productId,
        int returnBaseQty,
        Money unitCost,
        String notes
) {}


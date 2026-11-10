package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record SaleItemDto(
        long productId,
        String productName,
        int quantity,
        Money unitPrice,
        Money unitCost,
        Money discount,
        Money lineTotal
) {}


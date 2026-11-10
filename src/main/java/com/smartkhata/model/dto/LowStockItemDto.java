package com.smartkhata.model.dto;

public record LowStockItemDto(
        long productId,
        String productName,
        int currentStock,
        String unit
) {}


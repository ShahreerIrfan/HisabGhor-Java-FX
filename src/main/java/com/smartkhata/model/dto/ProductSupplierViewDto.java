package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record ProductSupplierViewDto(
        long id,
        long businessId,
        long productId,
        String productName,
        String productSku,
        String baseUnit,
        long supplierId,
        String supplierName,
        String supplierCode,
        Money tradePrice,
        String supplierProductCode,
        int minOrderQty,
        String purchaseUnit,
        int packSize,
        boolean isPreferred,
        int leadTimeDays,
        Money lastPurchasePrice,
        LocalDateTime lastPurchaseDate,
        boolean isActive
) {}


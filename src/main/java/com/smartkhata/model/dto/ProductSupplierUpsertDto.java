package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record ProductSupplierUpsertDto(
        Long id,
        long businessId,
        long productId,
        long supplierId,
        Money tradePrice,
        String supplierProductCode,
        int minOrderQty,
        String purchaseUnit,
        int packSize,
        boolean isPreferred,
        int leadTimeDays,
        boolean isActive
) {}


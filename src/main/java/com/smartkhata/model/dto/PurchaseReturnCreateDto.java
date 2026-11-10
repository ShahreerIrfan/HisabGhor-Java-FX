package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.util.List;

public record PurchaseReturnCreateDto(
        long businessId,
        long supplierId,
        Long grnId,
        boolean creditSupplierLedger,
        String reason,
        long actingUserId,
        List<PurchaseReturnItemCreateDto> items
) {}


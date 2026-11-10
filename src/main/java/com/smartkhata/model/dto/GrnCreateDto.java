package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.util.List;

public record GrnCreateDto(
        long businessId,
        Long purchaseOrderId,
        long supplierId,
        Money headerDiscount,
        Money freightAndOtherCosts,
        String challanReference,
        String notes,
        String idempotencyKey,
        long actingUserId,
        List<GrnItemCreateDto> items
) {}


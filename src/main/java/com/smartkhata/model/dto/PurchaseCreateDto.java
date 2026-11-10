package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.util.List;

public record PurchaseCreateDto(
        long businessId,
        long supplierId,
        List<PurchaseItemDto> items,
        Money discount,
        Money paidAmount,
        String paymentMethod,
        String notes,
        String idempotencyKey,
        long actingUserId
) {}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.util.List;

public record SaleCreateDto(
        long businessId,
        Long customerId,
        List<SaleItemDto> items,
        Money discount,
        Money paidAmount,
        String paymentMethod,
        String idempotencyKey,
        String notes,
        long actingUserId
) {}


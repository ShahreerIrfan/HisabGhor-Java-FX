package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

import java.time.LocalDateTime;

public record SaleReturn(
        Long id,
        long businessId,
        String returnNumber,
        long saleId,
        Long customerId,
        LocalDateTime returnDate,
        Money totalRefundAmount,
        Money totalCogsRestored,
        String reason,
        long createdByUserId,
        LocalDateTime createdAt
) {}


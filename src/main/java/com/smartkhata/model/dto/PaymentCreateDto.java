package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record PaymentCreateDto(
        long businessId,
        String partyType, // CUSTOMER, SUPPLIER
        long partyId,
        Money amount,
        String paymentMethod,
        String referenceTrxId,
        String notes,
        String idempotencyKey,
        long actingUserId
) {}


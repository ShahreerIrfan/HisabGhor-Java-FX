package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record Payment(
        Long id,
        long businessId,
        String paymentNumber,
        String partyType, // CUSTOMER, SUPPLIER
        long partyId,
        Money amount,
        LocalDateTime paymentDate,
        String paymentMethod, // CASH, BANK, BKASH, NAGAD, ROCKET, OTHER
        String referenceTrxId,
        String notes,
        long createdByUserId,
        String idempotencyKey,
        boolean isReversed,
        String reversalReason,
        LocalDateTime reversedAt,
        LocalDateTime createdAt
) {
    public Payment withId(Long newId) {
        return new Payment(newId, businessId, paymentNumber, partyType, partyId, amount, paymentDate,
                paymentMethod, referenceTrxId, notes, createdByUserId, idempotencyKey,
                isReversed, reversalReason, reversedAt, createdAt);
    }
}


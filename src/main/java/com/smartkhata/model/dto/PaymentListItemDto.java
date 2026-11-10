package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record PaymentListItemDto(
        long id,
        String paymentNumber,
        String partyType,
        long partyId,
        String partyName,
        Money amount,
        LocalDateTime paymentDate,
        String paymentMethod,
        String referenceTrxId,
        boolean isReversed,
        int allocationCount
) {}


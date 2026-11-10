package com.smartkhata.model.dto;

import com.smartkhata.model.entity.Payment;
import java.util.List;

public record PaymentDetailDto(
        Payment payment,
        String partyName,
        String partyPhone,
        List<PaymentAllocationDto> allocations,
        String createdByName
) {}


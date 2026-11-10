package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.time.LocalDateTime;

public record PurchaseReturnListItemDto(
        long id,
        String returnNumber,
        long supplierId,
        String supplierName,
        Long grnId,
        String grnNumber,
        LocalDateTime returnDate,
        Money totalReturnAmount,
        String reason
) {}


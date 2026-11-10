package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record PurchaseListItemDto(
        long id,
        String purchaseNumber,
        long supplierId,
        String supplierName,
        LocalDateTime purchaseDate,
        Money totalAmount,
        Money discount,
        Money netAmount,
        Money paidAmount,
        Money dueAmount,
        String paymentStatus,
        boolean isReversed,
        int itemCount
) {}

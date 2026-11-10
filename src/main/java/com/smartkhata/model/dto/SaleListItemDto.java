package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record SaleListItemDto(
        long id,
        String invoiceNumber,
        Long customerId,
        String customerName,
        LocalDateTime invoiceDate,
        Money netAmount,
        Money paidAmount,
        Money dueAmount,
        String paymentStatus,
        boolean isReversed,
        int itemCount
) {}


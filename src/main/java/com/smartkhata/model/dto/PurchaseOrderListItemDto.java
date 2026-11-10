package com.smartkhata.model.dto;

import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PurchaseOrderListItemDto(
        long id,
        String poNumber,
        long supplierId,
        String supplierName,
        LocalDateTime poDate,
        LocalDate expectedDeliveryDate,
        PurchaseOrderStatus status,
        Money grandTotal,
        int totalOrderedQty,
        int totalReceivedQty
) {}


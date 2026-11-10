package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.util.List;

public record PurchaseOrderCreateDto(
        Long id,
        long businessId,
        long supplierId,
        LocalDate expectedDeliveryDate,
        Money headerDiscount,
        Money headerTax,
        Money additionalCharges,
        String supplierReference,
        String notes,
        boolean confirmImmediately,
        long actingUserId,
        List<PurchaseOrderItemCreateDto> items
) {}


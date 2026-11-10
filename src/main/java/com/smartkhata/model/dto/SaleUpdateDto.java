package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

import java.util.List;

/**
 * Payload for updating an existing active POS sale order (customer, line items,
 * discount, paid settlement, payment method, and invoice notes) atomically.
 */
public record SaleUpdateDto(
        long saleId,
        long businessId,
        Long customerId,
        List<SaleItemDto> items,
        Money discount,
        Money paidAmount,
        String paymentMethod,
        String notes,
        long actingUserId
) {}


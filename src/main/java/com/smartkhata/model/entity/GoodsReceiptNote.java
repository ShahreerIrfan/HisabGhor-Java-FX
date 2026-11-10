package com.smartkhata.model.entity;

import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.vo.Money;

import java.time.LocalDateTime;

public record GoodsReceiptNote(
        Long id,
        long businessId,
        String grnNumber,
        Long purchaseOrderId,
        long supplierId,
        LocalDateTime receiptDate,
        GrnStatus status,
        Money subtotal,
        Money discount,
        Money freightAndOtherCosts,
        Money totalCost,
        String challanReference,
        String notes,
        String idempotencyKey,
        long postedByUserId,
        LocalDateTime postedAt,
        Long reversedByUserId,
        String reversalReason,
        LocalDateTime reversedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public GoodsReceiptNote withId(Long newId) {
        return new GoodsReceiptNote(newId, businessId, grnNumber, purchaseOrderId, supplierId,
                receiptDate, status, subtotal, discount, freightAndOtherCosts, totalCost,
                challanReference, notes, idempotencyKey, postedByUserId, postedAt,
                reversedByUserId, reversalReason, reversedAt, createdAt, updatedAt);
    }
}


package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;

public record GoodsReceiptItem(
        Long id,
        long grnId,
        Long poItemId,
        long productId,
        int orderedQty,
        int receivedQty,
        int acceptedQty,
        int rejectedQty,
        int damagedQty,
        String purchaseUnit,
        int packSize,
        int acceptedBaseQty,
        Money unitTradePrice,
        Money discount,
        Money allocatedExtraCost,
        Money effectiveBaseUnitCost,
        Money lineTotal,
        String batchOrRemark
) {
    public GoodsReceiptItem withGrnId(long newGrnId) {
        return new GoodsReceiptItem(id, newGrnId, poItemId, productId, orderedQty,
                receivedQty, acceptedQty, rejectedQty, damagedQty, purchaseUnit, packSize,
                acceptedBaseQty, unitTradePrice, discount, allocatedExtraCost,
                effectiveBaseUnitCost, lineTotal, batchOrRemark);
    }
}


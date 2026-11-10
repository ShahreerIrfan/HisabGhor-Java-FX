package com.smartkhata.model.enums;

public enum PurchaseOrderStatus {
    DRAFT,
    CONFIRMED,
    PARTIALLY_RECEIVED,
    FULLY_RECEIVED,
    CANCELLED,
    CLOSED;

    public boolean canReceiveGoods() {
        return this == CONFIRMED || this == PARTIALLY_RECEIVED;
    }

    public boolean canEdit() {
        return this == DRAFT;
    }

    public boolean canCancel() {
        return this == DRAFT || this == CONFIRMED;
    }

    public boolean canClose() {
        return this == CONFIRMED || this == PARTIALLY_RECEIVED;
    }
}


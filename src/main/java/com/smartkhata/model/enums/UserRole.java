package com.smartkhata.model.enums;

public enum UserRole {
    OWNER,
    CASHIER;

    public boolean isOwner() {
        return this == OWNER;
    }
}


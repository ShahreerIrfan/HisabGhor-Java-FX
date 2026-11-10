package com.smartkhata.model.entity;

import java.time.LocalDateTime;

public record Business(
        Long id,
        String name,
        String legalName,
        String phone,
        String address,
        String tradeLicense,
        String currencyCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public Business withId(Long newId) {
        return new Business(newId, name, legalName, phone, address, tradeLicense, currencyCode, createdAt, updatedAt);
    }
}


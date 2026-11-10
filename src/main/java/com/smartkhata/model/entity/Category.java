package com.smartkhata.model.entity;

import java.time.LocalDateTime;

public record Category(
        Long id,
        long businessId,
        String name,
        String description,
        LocalDateTime createdAt
) {
    public Category withId(Long newId) {
        return new Category(newId, businessId, name, description, createdAt);
    }
}


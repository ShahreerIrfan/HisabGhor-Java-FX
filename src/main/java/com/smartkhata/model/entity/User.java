package com.smartkhata.model.entity;

import com.smartkhata.model.enums.UserRole;
import java.time.LocalDateTime;

public record User(
        Long id,
        long businessId,
        String username,
        String fullName,
        String passwordHash,
        String salt,
        UserRole role,
        boolean isActive,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public User withId(Long newId) {
        return new User(newId, businessId, username, fullName, passwordHash, salt, role, isActive, lastLoginAt, createdAt, updatedAt);
    }

    public User withLastLoginAt(LocalDateTime loginTime) {
        return new User(id, businessId, username, fullName, passwordHash, salt, role, isActive, loginTime, createdAt, updatedAt);
    }
}


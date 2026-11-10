package com.smartkhata.model.dto;

import com.smartkhata.model.enums.UserRole;

public record StaffUpdateDto(
        long userId,
        String fullName,
        UserRole role,
        boolean isActive,
        char[] newPasswordOrPinOptional
) {}

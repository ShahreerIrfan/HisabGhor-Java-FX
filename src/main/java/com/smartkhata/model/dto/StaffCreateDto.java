package com.smartkhata.model.dto;

import com.smartkhata.model.enums.UserRole;

public record StaffCreateDto(
        long businessId,
        String username,
        String fullName,
        UserRole role,
        char[] passwordOrPin
) {}

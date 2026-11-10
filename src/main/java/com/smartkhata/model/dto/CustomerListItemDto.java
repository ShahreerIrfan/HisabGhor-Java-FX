package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record CustomerListItemDto(
        long id,
        String name,
        String phone,
        String address,
        Money creditLimit,
        Money currentDue,
        boolean isArchived,
        LocalDateTime createdAt
) {}


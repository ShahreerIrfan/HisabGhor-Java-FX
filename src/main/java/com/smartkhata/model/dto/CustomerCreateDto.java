package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record CustomerCreateDto(
        long businessId,
        String name,
        String phone,
        String address,
        Money creditLimit,
        Money openingBalance,
        String notes
) {}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record CustomerUpdateDto(
        long customerId,
        String name,
        String phone,
        String address,
        Money creditLimit,
        String notes
) {}


package com.smartkhata.model.dto;

import java.util.List;

public record SaleReturnCreateDto(
        long businessId,
        long saleId,
        String reason,
        long actingUserId,
        List<SaleReturnItemCreateDto> items
) {}


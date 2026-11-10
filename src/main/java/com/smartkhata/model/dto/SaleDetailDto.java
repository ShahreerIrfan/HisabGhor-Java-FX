package com.smartkhata.model.dto;

import com.smartkhata.model.entity.Sale;
import java.util.List;

public record SaleDetailDto(
        Sale sale,
        String customerName,
        String customerPhone,
        String customerAddress,
        List<SaleItemDto> items,
        String cashierName
) {}


package com.smartkhata.model.dto;

import com.smartkhata.model.entity.Purchase;
import java.util.List;

public record PurchaseDetailDto(
        Purchase purchase,
        String supplierName,
        String supplierPhone,
        String supplierAddress,
        List<PurchaseItemDto> items,
        String createdByName
) {}


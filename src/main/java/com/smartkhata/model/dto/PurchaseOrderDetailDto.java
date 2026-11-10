package com.smartkhata.model.dto;

import com.smartkhata.model.entity.PurchaseOrder;
import com.smartkhata.model.entity.Supplier;

import java.util.List;

public record PurchaseOrderDetailDto(
        PurchaseOrder order,
        Supplier supplier,
        List<PurchaseOrderItemViewDto> items
) {}


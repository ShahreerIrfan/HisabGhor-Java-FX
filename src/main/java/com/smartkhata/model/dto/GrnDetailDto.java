package com.smartkhata.model.dto;

import com.smartkhata.model.entity.GoodsReceiptNote;
import com.smartkhata.model.entity.Supplier;

import java.util.List;

public record GrnDetailDto(
        GoodsReceiptNote grn,
        String poNumber,
        Supplier supplier,
        List<GrnItemViewDto> items
) {}


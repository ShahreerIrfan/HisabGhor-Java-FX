package com.smartkhata.model.dto;

import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.vo.Money;

import java.time.LocalDateTime;

public record GrnListItemDto(
        long id,
        String grnNumber,
        Long purchaseOrderId,
        String poNumber,
        long supplierId,
        String supplierName,
        LocalDateTime receiptDate,
        GrnStatus status,
        Money totalCost,
        int totalAcceptedBaseQty,
        int totalRejectedOrDamagedQty,
        boolean invoiced
) {}


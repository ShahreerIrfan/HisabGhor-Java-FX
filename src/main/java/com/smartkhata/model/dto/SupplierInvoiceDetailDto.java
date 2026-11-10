package com.smartkhata.model.dto;

import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.entity.SupplierInvoice;

import java.util.List;

public record SupplierInvoiceDetailDto(
        SupplierInvoice invoice,
        String poNumber,
        String grnNumber,
        Supplier supplier,
        List<SupplierInvoiceItemViewDto> items
) {}


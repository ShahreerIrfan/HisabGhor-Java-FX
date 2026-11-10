package com.smartkhata.model.dto;

import com.smartkhata.ui.ViewRouter;

/**
 * Result item returned by multi-entity global search across
 * Customers, Products, Suppliers, and Invoices.
 */
public record SearchResultItemDto(
        String category,      // "Customer", "Product", "Supplier", "Invoice"
        String title,         // e.g. "Rahim Mia (01711223344)"
        String subtitle,      // e.g. "Current Due: BDT 500.00"
        ViewRouter.ViewId targetView,
        Long recordId
) {}


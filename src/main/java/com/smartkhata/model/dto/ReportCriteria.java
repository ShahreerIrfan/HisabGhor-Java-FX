package com.smartkhata.model.dto;

import com.smartkhata.model.enums.ReportType;
import java.time.LocalDate;

public record ReportCriteria(
        long businessId,
        ReportType reportType,
        LocalDate startDate,
        LocalDate endDate,
        Long partyId,       // Specific customerId or supplierId if applicable
        String category,    // Expense or Product category if applicable
        String status,      // e.g. PAID, PARTIAL, DUE, VOIDED
        boolean lowStockOnly
) {
    public ReportCriteria {
        if (reportType == null) {
            throw new IllegalArgumentException("Report type is required");
        }
    }
}

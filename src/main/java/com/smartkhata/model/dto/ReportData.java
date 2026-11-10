package com.smartkhata.model.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ReportData(
        String title,
        String businessName,
        String businessAddress,
        String businessPhone,
        String periodOrAsOfLabel,
        List<String> headers,
        List<List<String>> rows,
        Map<String, String> summaryCards,
        LocalDateTime generatedAt
) {
    public int rowCount() {
        return rows != null ? rows.size() : 0;
    }
}

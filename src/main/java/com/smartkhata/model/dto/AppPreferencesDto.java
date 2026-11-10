package com.smartkhata.model.dto;

/**
 * Immutable data transfer object representing configurable user and application preferences.
 */
public record AppPreferencesDto(
        long businessId,
        String theme,                // "LIGHT", "DARK"
        String language,             // "en", "bn"
        String dateFormat,           // "YYYY-MM-DD", "DD/MM/YYYY", "DD-MM-YYYY"
        String numberFormat,         // "SOUTH_ASIAN", "STANDARD"
        String tableRowSize,         // "COMPACT", "STANDARD", "RELAXED"
        int defaultLowStockThreshold,// e.g. 10
        String defaultReportPeriod,  // "THIS_MONTH", "TODAY", "LAST_30_DAYS", "ALL_TIME"
        String invoiceHeader,
        String invoiceFooter,
        String logoPath,
        String bdtDisplayFormat,     // "BDT", "TK"
        boolean inAppAlertsEnabled
) {
    public static AppPreferencesDto defaults(long businessId) {
        return new AppPreferencesDto(
                businessId,
                "LIGHT",
                "en",
                "YYYY-MM-DD",
                "SOUTH_ASIAN",
                "STANDARD",
                10,
                "THIS_MONTH",
                "",
                "Thank you for your business!",
                "",
                "BDT",
                true
        );
    }
}


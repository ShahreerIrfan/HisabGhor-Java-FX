package com.smartkhata.model.dto;

import java.time.LocalDateTime;

/**
 * Metadata manifest for a SmartKhata backup package.
 * Contains schema version, cryptographic SHA-256 hash, record tallies, and integrity verification state.
 */
public record BackupMetadataDto(
        String fileName,
        String appVersion,
        int schemaVersion,
        LocalDateTime createdAt,
        String sha256,
        long fileSizeBytes,
        String businessName,
        long businessId,
        long customerCount,
        long supplierCount,
        long productCount,
        long saleCount,
        long purchaseCount,
        boolean integrityPassed,
        String note
) {
    public String formattedSize() {
        if (fileSizeBytes < 1024) return fileSizeBytes + " B";
        if (fileSizeBytes < 1024 * 1024) return String.format("%.1f KB", fileSizeBytes / 1024.0);
        return String.format("%.2f MB", fileSizeBytes / (1024.0 * 1024.0));
    }
}


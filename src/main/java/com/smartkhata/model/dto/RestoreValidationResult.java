package com.smartkhata.model.dto;

import java.nio.file.Path;

/**
 * Result of validating a candidate backup file prior to initiating restore.
 */
public record RestoreValidationResult(
        boolean isValid,
        String errorMessage,
        BackupMetadataDto metadata,
        Path stagedDatabasePath
) {
    public static RestoreValidationResult valid(BackupMetadataDto metadata, Path stagedDatabasePath) {
        return new RestoreValidationResult(true, null, metadata, stagedDatabasePath);
    }

    public static RestoreValidationResult invalid(String errorMessage) {
        return new RestoreValidationResult(false, errorMessage, null, null);
    }
}


package com.smartkhata.service.api;

import com.smartkhata.model.dto.BackupMetadataDto;
import com.smartkhata.model.dto.RestoreValidationResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Service managing transactional SQLite backups, manifest generation,
 * SHA-256 integrity verification, and safe atomic recovery.
 */
public interface BackupService {

    /**
     * Produces a coherent, transactionally clean backup package.
     * Uses SQLite VACUUM INTO to incorporate all WAL journal commits into a vacuumed database.
     *
     * @param targetFile Optional custom destination; if null, defaults to AppConfig.getBackupDir()
     * @param note Optional user note recorded in the backup manifest
     * @return Metadata describing the completed backup
     */
    BackupMetadataDto createBackup(Path targetFile, String note);

    /**
     * Inspects and validates a candidate backup file without modifying the active database.
     * Verifies SQLite physical integrity, schema version compatibility, and table existence.
     *
     * @param candidateFile Path to .skbak archive or .db database file
     * @return Validation result containing metadata and error details if invalid
     */
    RestoreValidationResult validateBackup(Path candidateFile);

    /**
     * Atomically restores the database from a validated candidate file.
     * Generates a pre-restore safety snapshot of the active database, flushes WAL,
     * validates candidate, replaces active database file, and reinitializes persistence.
     *
     * @param candidateFile Path to .skbak archive or .db database file
     * @param createSafetyBackup Whether to take an automatic safety snapshot before restore
     */
    void restoreBackup(Path candidateFile, boolean createSafetyBackup);

    /**
     * Lists existing backup files found in the default backup directory.
     */
    List<BackupMetadataDto> listAvailableBackups();
}


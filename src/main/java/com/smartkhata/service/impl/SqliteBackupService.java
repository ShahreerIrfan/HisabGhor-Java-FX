package com.smartkhata.service.impl;

import com.smartkhata.app.AppConfig;
import com.smartkhata.dao.BusinessDao;
import com.smartkhata.dao.SqliteBusinessDao;
import com.smartkhata.dao.SqliteUserDao;
import com.smartkhata.dao.UserDao;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.BackupMetadataDto;
import com.smartkhata.model.dto.RestoreValidationResult;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.service.api.BackupService;
import com.smartkhata.service.api.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Transactionally coherent SQLite backup and recovery engine.
 * Employs SQLite VACUUM INTO to incorporate WAL transactions, generates SHA-256 manifests,
 * validates schema versions and physical integrity before restore, and takes automated safety snapshots.
 */
public class SqliteBackupService implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(SqliteBackupService.class);
    private static final byte[] SQLITE_HEADER_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final BusinessDao businessDao;
    private final UserDao userDao;

    public SqliteBackupService() {
        this(new SqliteBusinessDao(), new SqliteUserDao());
    }

    public SqliteBackupService(BusinessDao businessDao, UserDao userDao) {
        this.businessDao = businessDao;
        this.userDao = userDao;
    }

    @Override
    public BackupMetadataDto createBackup(Path targetFile, String note) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();
        Business business = SessionContext.getCurrentBusiness();

        if (targetFile == null) {
            Path backupDir = AppConfig.getBackupDir();
            try {
                Files.createDirectories(backupDir);
            } catch (IOException e) {
                throw new DatabaseException("Could not create backup directory: " + backupDir, e);
            }
            String filename = String.format("smartkhata_backup_%s_%s.skbak",
                    sanitizeFilename(business != null ? business.name() : "store"),
                    LocalDateTime.now().format(FILE_DATE_FMT));
            targetFile = backupDir.resolve(filename);
        }

        try {
            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }
        } catch (IOException e) {
            throw new DatabaseException("Could not create target directories: " + targetFile.getParent(), e);
        }

        Path tempVacuumDb = null;
        try {
            // 1. Create a temporary file path for VACUUM INTO
            tempVacuumDb = Files.createTempFile("sk_vacuum_", ".db");
            // SQLite VACUUM INTO requires target file to NOT exist yet
            Files.deleteIfExists(tempVacuumDb);

            // 2. Execute SQLite VACUUM INTO to atomically copy database including all uncheckpointed WAL pages
            String escapedPath = tempVacuumDb.toAbsolutePath().normalize().toString().replace("'", "''");
            try (Connection conn = DatabaseManager.getConnection();
                 Statement stmt = conn.createStatement()) {
                log.info("Executing SQLite VACUUM INTO '{}'...", escapedPath);
                stmt.executeUpdate("VACUUM INTO '" + escapedPath + "'");
            }

            // 3. Verify physical integrity and inspect metadata on the vacuumed copy
            int schemaVersion = 0;
            long custCount = 0;
            long suppCount = 0;
            long prodCount = 0;
            long saleCount = 0;
            long purCount = 0;

            try (Connection tempConn = DriverManager.getConnection("jdbc:sqlite:" + tempVacuumDb.toAbsolutePath())) {
                DatabaseManager.verifyIntegrity(tempConn);

                try (Statement stmt = tempConn.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
                        if (rs.next()) schemaVersion = rs.getInt(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers WHERE business_id = " + businessId)) {
                        if (rs.next()) custCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM suppliers WHERE business_id = " + businessId)) {
                        if (rs.next()) suppCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM products WHERE business_id = " + businessId)) {
                        if (rs.next()) prodCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sales WHERE business_id = " + businessId + " AND is_reversed = 0")) {
                        if (rs.next()) saleCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM purchases WHERE business_id = " + businessId + " AND is_reversed = 0")) {
                        if (rs.next()) purCount = rs.getLong(1);
                    }
                }
            }

            // 4. Compute SHA-256 checksum of the vacuumed database
            String sha256 = computeSha256(tempVacuumDb);
            long fileSize = Files.size(tempVacuumDb);

            BackupMetadataDto metadata = new BackupMetadataDto(
                    targetFile.getFileName().toString(),
                    AppConfig.getAppVersion(),
                    schemaVersion,
                    LocalDateTime.now(),
                    sha256,
                    fileSize,
                    business != null ? business.name() : "Store",
                    businessId,
                    custCount,
                    suppCount,
                    prodCount,
                    saleCount,
                    purCount,
                    true,
                    note != null ? note : "Manual backup"
            );

            // 5. Package into .skbak ZIP archive (or copy directly if user requested .db)
            if (targetFile.toString().toLowerCase().endsWith(".db")) {
                Files.copy(tempVacuumDb, targetFile, StandardCopyOption.REPLACE_EXISTING);
                Path manifestFile = targetFile.getParent().resolve(targetFile.getFileName() + ".manifest.json");
                Files.writeString(manifestFile, formatManifestJson(metadata), StandardCharsets.UTF_8);
            } else {
                packageZipArchive(targetFile, tempVacuumDb, metadata);
            }

            log.info("Backup created successfully: [{}] (Size: {}, SHA-256: {})",
                    targetFile, metadata.formattedSize(), sha256);
            return metadata;
        } catch (SQLException | IOException e) {
            log.error("Failed to create coherent SQLite backup: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to create database backup: " + e.getMessage(), e);
        } finally {
            if (tempVacuumDb != null) {
                try {
                    Files.deleteIfExists(tempVacuumDb);
                } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public RestoreValidationResult validateBackup(Path candidateFile) {
        if (candidateFile == null || !Files.exists(candidateFile)) {
            return RestoreValidationResult.invalid("Backup candidate file does not exist.");
        }

        try {
            long size = Files.size(candidateFile);
            if (size == 0) {
                return RestoreValidationResult.invalid("Backup file is empty (0 bytes).");
            }
        } catch (IOException e) {
            return RestoreValidationResult.invalid("Cannot read backup file: " + e.getMessage());
        }

        Path stageDir = null;
        try {
            stageDir = Files.createTempDirectory("sk_stage_");
            Path stagedDb = stageDir.resolve("smartkhata.db");
            BackupMetadataDto manifestMetadata = null;

            if (isZipFile(candidateFile)) {
                // Extract smartkhata.db and manifest.json from ZIP archive
                manifestMetadata = extractZipArchive(candidateFile, stagedDb);
            } else {
                // Direct SQLite database file
                Files.copy(candidateFile, stagedDb, StandardCopyOption.REPLACE_EXISTING);
            }

            // Verify SQLite File Header
            if (!verifySqliteHeader(stagedDb)) {
                return RestoreValidationResult.invalid("Corrupt or invalid file: missing valid SQLite database header.");
            }

            // Open connection to candidate database in read-only mode to check physical integrity & schema
            int schemaVersion = 0;
            String bizName = "Unknown Store";
            long bizId = 1L;
            long custCount = 0;
            long suppCount = 0;
            long prodCount = 0;
            long saleCount = 0;
            long purCount = 0;

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + stagedDb.toAbsolutePath())) {
                // 1. Run physical integrity check
                DatabaseManager.verifyIntegrity(conn);

                // 2. Check schema user_version
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
                    if (rs.next()) {
                        schemaVersion = rs.getInt(1);
                    }
                }

                int latestSupported = com.smartkhata.database.MigrationRunner.getLatestVersion();
                if (schemaVersion > latestSupported) {
                    return RestoreValidationResult.invalid(String.format(
                            "Incompatible database schema version %d. This version of SmartKhata only supports schema up to version %d. Please update SmartKhata before restoring.",
                            schemaVersion, latestSupported));
                }

                // 3. Verify essential core tables exist
                List<String> requiredTables = List.of("businesses", "users", "customers", "products", "sales", "settings");
                for (String table : requiredTables) {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                        if (!rs.next()) {
                            return RestoreValidationResult.invalid("Invalid backup database: missing required table '" + table + "'.");
                        }
                    }
                }

                // 4. Read business metadata & tallies
                try (Statement stmt = conn.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("SELECT id, name FROM businesses ORDER BY id ASC LIMIT 1")) {
                        if (rs.next()) {
                            bizId = rs.getLong("id");
                            bizName = rs.getString("name");
                        }
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers WHERE business_id = " + bizId)) {
                        if (rs.next()) custCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM suppliers WHERE business_id = " + bizId)) {
                        if (rs.next()) suppCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM products WHERE business_id = " + bizId)) {
                        if (rs.next()) prodCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sales WHERE business_id = " + bizId + " AND is_reversed = 0")) {
                        if (rs.next()) saleCount = rs.getLong(1);
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM purchases WHERE business_id = " + bizId + " AND is_reversed = 0")) {
                        if (rs.next()) purCount = rs.getLong(1);
                    }
                }
            }

            String sha256 = computeSha256(stagedDb);
            long fileSize = Files.size(stagedDb);

            BackupMetadataDto metadata = new BackupMetadataDto(
                    candidateFile.getFileName().toString(),
                    manifestMetadata != null ? manifestMetadata.appVersion() : AppConfig.getAppVersion(),
                    schemaVersion,
                    manifestMetadata != null ? manifestMetadata.createdAt() : LocalDateTime.now(),
                    sha256,
                    fileSize,
                    bizName,
                    bizId,
                    custCount,
                    suppCount,
                    prodCount,
                    saleCount,
                    purCount,
                    true,
                    manifestMetadata != null ? manifestMetadata.note() : "Validated snapshot"
            );

            return RestoreValidationResult.valid(metadata, stagedDb);
        } catch (Exception e) {
            log.warn("Validation failed for candidate file [{}]: {}", candidateFile, e.getMessage());
            return RestoreValidationResult.invalid("Validation error: " + e.getMessage());
        }
    }

    @Override
    public synchronized void restoreBackup(Path candidateFile, boolean createSafetyBackup) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();

        log.info("Starting restore workflow for candidate [{}]...", candidateFile);

        // 1. Validate candidate file thoroughly
        RestoreValidationResult validation = validateBackup(candidateFile);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Cannot restore database from invalid backup: " + validation.errorMessage());
        }

        // 2. Create automatic pre-restore safety backup of active database if requested
        if (createSafetyBackup) {
            Path backupDir = AppConfig.getBackupDir();
            try {
                Files.createDirectories(backupDir);
            } catch (IOException e) {
                log.warn("Could not create backup directory for safety snapshot: {}", e.getMessage());
            }
            String safetyName = String.format("safety_pre_restore_%s.skbak", LocalDateTime.now().format(FILE_DATE_FMT));
            Path safetyPath = backupDir.resolve(safetyName);
            log.info("Creating pre-restore safety backup at [{}]...", safetyPath);
            createBackup(safetyPath, "Automated safety snapshot taken prior to restore from " + candidateFile.getFileName());
        }

        // 3. Checkpoint WAL and prepare active database replacement
        DatabaseManager.checkpointWal();
        Path activeDb = DatabaseManager.getActiveDatabaseFile();
        Path walFile = Path.of(activeDb.toString() + "-wal");
        Path shmFile = Path.of(activeDb.toString() + "-shm");

        try {
            // Delete sidecar WAL and SHM files to prevent journal conflict
            Files.deleteIfExists(walFile);
            Files.deleteIfExists(shmFile);

            // 4. Atomically replace active database with validated staged database
            Files.copy(validation.stagedDatabasePath(), activeDb, StandardCopyOption.REPLACE_EXISTING);
            log.info("Active database replaced successfully from staged backup");

            // 5. Reset initialization and reinitialize connection manager
            DatabaseManager.resetInitialization();
            DatabaseManager.initializeDatabase();

            // 6. Synchronize active session context with restored business
            try (Connection conn = DatabaseManager.getConnection()) {
                long restoredBizId = validation.metadata().businessId();
                Optional<Business> bizOpt = businessDao.findById(conn, restoredBizId);
                if (bizOpt.isPresent()) {
                    List<User> users = userDao.findAllByBusiness(conn, restoredBizId);
                    User ownerUser = users.stream()
                            .filter(u -> u.role() == UserRole.OWNER)
                            .findFirst()
                            .orElse(SessionContext.getCurrentUser());
                    SessionContext.setSession(bizOpt.get(), ownerUser);
                    log.info("Session restored for business [{}] (ID: {})", bizOpt.get().name(), restoredBizId);
                }
            }

            log.info("Database restore operation completed successfully from [{}]", candidateFile.getFileName());
        } catch (IOException | SQLException e) {
            log.error("Failed to restore database from candidate {}: {}", candidateFile, e.getMessage(), e);
            throw new DatabaseException("Failed to restore database: " + e.getMessage(), e);
        }
    }

    @Override
    public List<BackupMetadataDto> listAvailableBackups() {
        Path backupDir = AppConfig.getBackupDir();
        if (!Files.exists(backupDir) || !Files.isDirectory(backupDir)) {
            return List.of();
        }

        List<BackupMetadataDto> backups = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, "*.{skbak,zip,db}")) {
            for (Path path : stream) {
                try {
                    RestoreValidationResult val = validateBackup(path);
                    if (val.isValid() && val.metadata() != null) {
                        backups.add(val.metadata());
                    }
                } catch (Exception e) {
                    log.debug("Skipping unparseable backup file [{}]: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Error reading backup directory {}: {}", backupDir, e.getMessage());
        }

        backups.sort(Comparator.comparing(BackupMetadataDto::createdAt).reversed());
        return backups;
    }

    private void packageZipArchive(Path targetZip, Path dbFile, BackupMetadataDto metadata) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(targetZip)))) {
            // Entry 1: smartkhata.db
            ZipEntry dbEntry = new ZipEntry("smartkhata.db");
            zos.putNextEntry(dbEntry);
            Files.copy(dbFile, zos);
            zos.closeEntry();

            // Entry 2: manifest.json
            ZipEntry manifestEntry = new ZipEntry("manifest.json");
            zos.putNextEntry(manifestEntry);
            zos.write(formatManifestJson(metadata).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private BackupMetadataDto extractZipArchive(Path zipPath, Path targetDb) throws IOException {
        String manifestContent = null;
        boolean dbFound = false;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("smartkhata.db") || entry.getName().endsWith(".db")) {
                    Files.copy(zis, targetDb, StandardCopyOption.REPLACE_EXISTING);
                    dbFound = true;
                } else if (entry.getName().endsWith("manifest.json")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    zis.transferTo(baos);
                    manifestContent = baos.toString(StandardCharsets.UTF_8);
                }
                zis.closeEntry();
            }
        }

        if (!dbFound) {
            throw new IOException("ZIP backup archive does not contain a valid database file (smartkhata.db).");
        }

        if (manifestContent != null) {
            return parseManifestJson(manifestContent);
        }
        return null;
    }

    private boolean isZipFile(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] header = new byte[4];
            int read = is.read(header);
            return read == 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean verifySqliteHeader(Path dbFile) {
        try (InputStream is = Files.newInputStream(dbFile)) {
            byte[] header = new byte[SQLITE_HEADER_MAGIC.length];
            int read = is.read(header);
            return read == SQLITE_HEADER_MAGIC.length && Arrays.equals(header, SQLITE_HEADER_MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available in JVM", e);
        }
    }

    private String formatManifestJson(BackupMetadataDto m) {
        return "{\n" +
                "  \"fileName\": \"" + escapeJson(m.fileName()) + "\",\n" +
                "  \"appVersion\": \"" + escapeJson(m.appVersion()) + "\",\n" +
                "  \"schemaVersion\": " + m.schemaVersion() + ",\n" +
                "  \"createdAt\": \"" + m.createdAt().toString() + "\",\n" +
                "  \"sha256\": \"" + m.sha256() + "\",\n" +
                "  \"fileSizeBytes\": " + m.fileSizeBytes() + ",\n" +
                "  \"businessName\": \"" + escapeJson(m.businessName()) + "\",\n" +
                "  \"businessId\": " + m.businessId() + ",\n" +
                "  \"customerCount\": " + m.customerCount() + ",\n" +
                "  \"supplierCount\": " + m.supplierCount() + ",\n" +
                "  \"productCount\": " + m.productCount() + ",\n" +
                "  \"saleCount\": " + m.saleCount() + ",\n" +
                "  \"purchaseCount\": " + m.purchaseCount() + ",\n" +
                "  \"integrityPassed\": " + m.integrityPassed() + ",\n" +
                "  \"note\": \"" + escapeJson(m.note()) + "\"\n" +
                "}";
    }

    private BackupMetadataDto parseManifestJson(String json) {
        Map<String, String> map = new HashMap<>();
        String[] lines = json.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("\"") && line.contains(":")) {
                int colon = line.indexOf(':');
                String key = line.substring(1, line.indexOf('"', 1));
                String val = line.substring(colon + 1).trim();
                if (val.endsWith(",")) val = val.substring(0, val.length() - 1);
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                map.put(key, val);
            }
        }

        return new BackupMetadataDto(
                map.getOrDefault("fileName", "backup.skbak"),
                map.getOrDefault("appVersion", AppConfig.getAppVersion()),
                Integer.parseInt(map.getOrDefault("schemaVersion", "1")),
                map.containsKey("createdAt") ? LocalDateTime.parse(map.get("createdAt")) : LocalDateTime.now(),
                map.getOrDefault("sha256", ""),
                Long.parseLong(map.getOrDefault("fileSizeBytes", "0")),
                map.getOrDefault("businessName", "Store"),
                Long.parseLong(map.getOrDefault("businessId", "1")),
                Long.parseLong(map.getOrDefault("customerCount", "0")),
                Long.parseLong(map.getOrDefault("supplierCount", "0")),
                Long.parseLong(map.getOrDefault("productCount", "0")),
                Long.parseLong(map.getOrDefault("saleCount", "0")),
                Long.parseLong(map.getOrDefault("purchaseCount", "0")),
                Boolean.parseBoolean(map.getOrDefault("integrityPassed", "true")),
                map.getOrDefault("note", "")
        );
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }
}

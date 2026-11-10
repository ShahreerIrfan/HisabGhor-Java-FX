package com.smartkhata.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages environment paths, operating system application directories,
 * and persistent storage locations for SmartKhata.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private static final String APP_DIR_NAME = "SmartKhata";
    private static Path appHomeDir;
    private static Path dataDir;
    private static Path logDir;
    private static Path backupDir;
    private static Path productImagesDir;

    private AppConfig() {}

    /**
     * Initializes and creates required application directory trees.
     */
    public static synchronized void initializeDirectories() throws IOException {
        String customHome = System.getProperty("smartkhata.home");
        if (customHome != null && !customHome.isBlank()) {
            appHomeDir = Paths.get(customHome).toAbsolutePath().normalize();
        } else {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                appHomeDir = Paths.get(localAppData, APP_DIR_NAME).toAbsolutePath().normalize();
            } else {
                String userHome = System.getProperty("user.home", ".");
                appHomeDir = Paths.get(userHome, "." + APP_DIR_NAME.toLowerCase()).toAbsolutePath().normalize();
            }
        }

        dataDir = appHomeDir.resolve("data");
        logDir = appHomeDir.resolve("logs");
        backupDir = appHomeDir.resolve("backups");
        productImagesDir = appHomeDir.resolve("images").resolve("products");

        Files.createDirectories(appHomeDir);
        Files.createDirectories(dataDir);
        Files.createDirectories(logDir);
        Files.createDirectories(backupDir);
        Files.createDirectories(productImagesDir);

        // Export system property for Logback file appender resolution
        System.setProperty("SMARTKHATA_LOG_DIR", logDir.toString());

        log.info("SmartKhata directories initialized at: {}", appHomeDir);
    }

    public static Path getAppHomeDir() {
        return appHomeDir;
    }

    public static Path getDataDir() {
        return dataDir;
    }

    public static Path getLogDir() {
        return logDir;
    }

    public static Path getBackupDir() {
        return backupDir;
    }

    public static Path getProductImagesDir() {
        if (productImagesDir == null) {
            Path fallbackBase = appHomeDir != null
                    ? appHomeDir
                    : Paths.get(System.getProperty("java.io.tmpdir"), "smartkhata");
            productImagesDir = fallbackBase.resolve("images").resolve("products");
            try {
                Files.createDirectories(productImagesDir);
            } catch (IOException ignored) {}
        }
        return productImagesDir;
    }

    public static Path getDatabasePath() {
        return dataDir.resolve("smartkhata.db");
    }

    public static String getAppTitle() {
        return "SmartKhata - Bookkeeping & Accounting";
    }

    public static String getAppVersion() {
        return "1.0.0";
    }
}


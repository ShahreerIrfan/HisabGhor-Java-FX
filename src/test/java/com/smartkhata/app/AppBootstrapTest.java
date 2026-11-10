package com.smartkhata.app;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppBootstrapTest {

    @BeforeAll
    static void setup(@TempDir Path tempDir) throws IOException {
        System.setProperty("smartkhata.home", tempDir.toString());
        AppConfig.initializeDirectories();
    }

    @Test
    void shouldInitializeRequiredDirectories() {
        assertThat(AppConfig.getAppHomeDir()).isNotNull();
        assertThat(Files.isDirectory(AppConfig.getDataDir())).isTrue();
        assertThat(Files.isDirectory(AppConfig.getLogDir())).isTrue();
        assertThat(Files.isDirectory(AppConfig.getBackupDir())).isTrue();
        assertThat(AppConfig.getDatabasePath().getFileName().toString()).isEqualTo("smartkhata.db");
    }

    @Test
    void shouldSanitizeSensitiveKeywordsInLogs() {
        String sensitive = "User failed authentication with password=secret123 and pin=4321";
        String sanitized = AppBootstrap.sanitizeMessage(sensitive);

        assertThat(sanitized).doesNotContain("secret123");
        assertThat(sanitized).doesNotContain("4321");
        assertThat(sanitized).contains("password=***");
        assertThat(sanitized).contains("pin=***");
    }

    @Test
    void shouldHandleNullSanitizationGracefully() {
        assertThat(AppBootstrap.sanitizeMessage(null)).isEmpty();
    }
}

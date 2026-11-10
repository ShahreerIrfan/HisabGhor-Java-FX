package com.smartkhata.app;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.ui.ViewRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles application initialization, structured logging setup,
 * global uncaught exception trapping, and clean JVM shutdown.
 */
public final class AppBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AppBootstrap.class);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private AppBootstrap() {}

    /**
     * Initializes all core subsystems before opening the primary JavaFX window.
     */
    public static synchronized void initialize() throws Exception {
        if (initialized.get()) {
            return;
        }

        log.info("Starting SmartKhata desktop application v{}", AppConfig.getAppVersion());

        // 1. Initialize persistent directories
        AppConfig.initializeDirectories();

        // 2. Initialize database persistence and schema migrations
        DatabaseManager.initializeDatabase();

        // 3. Setup global uncaught exception handling
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            log.error("Uncaught exception on thread [{}]: {}", thread.getName(), sanitizeMessage(throwable.getMessage()), throwable);
        });

        // 4. Register JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(AppBootstrap::shutdown, "SmartKhata-ShutdownHook"));

        initialized.set(true);
        log.info("SmartKhata bootstrap sequence completed successfully");
    }

    /**
     * Executes clean shutdown procedures.
     */
    public static synchronized void shutdown() {
        if (shuttingDown.getAndSet(true)) {
            return;
        }

        log.info("Executing SmartKhata clean shutdown...");
        try {
            ViewRouter.shutdownControllers();
            if (DatabaseManager.isInitialized()) {
                DatabaseManager.checkpointWal();
            }
            initialized.set(false);
            shuttingDown.set(false);
            log.info("SmartKhata stopped cleanly");
        } catch (Exception e) {
            log.error("Error during application shutdown: {}", e.getMessage(), e);
        }
    }

    /**
     * Strips potentially sensitive credentials, PINs, or tokens from error logs.
     */
    public static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)(password|pin|secret|token)=[^,;\\s]+", "$1=***");
    }

    public static boolean isInitialized() {
        return initialized.get();
    }
}


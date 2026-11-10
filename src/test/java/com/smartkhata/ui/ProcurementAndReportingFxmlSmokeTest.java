package com.smartkhata.ui;

import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.service.api.SessionContext;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProcurementAndReportingFxmlSmokeTest {

    private final List<Object> loadedControllers = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DatabaseManager.setCustomJdbcUrl("jdbc:sqlite:" + tempDir.resolve("fxml_smoke.db").toAbsolutePath());
        DatabaseManager.initializeDatabase();
        SessionContext.clearSession();

        final Business[] bizHolder = new Business[1];
        final User[] userHolder = new User[1];
        TransactionManager.runInTransaction(conn -> {
            long bizId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO businesses (name, currency_code) VALUES ('FXML Smoke Store', 'BDT')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); bizId = rs.getLong(1); }
            }
            bizHolder[0] = new Business(bizId, "FXML Smoke Store", null, "01700", "Dhaka", null, "BDT", null, null);
            long uid;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (business_id, username, full_name, password_hash, salt, role) VALUES (?, 'owner', 'Owner', 'x', 'y', 'OWNER')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, bizId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); uid = rs.getLong(1); }
            }
            userHolder[0] = new User(uid, bizId, "owner", "Owner", "x", "y", UserRole.OWNER, true, null, null, null);
        });
        SessionContext.setSession(bizHolder[0], userHolder[0]);
    }

    @AfterEach
    void tearDown() {
        for (Object controller : loadedControllers) {
            try {
                controller.getClass().getMethod("shutdown").invoke(controller);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }
        }
        loadedControllers.clear();

        ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS);
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (t.isAlive() && (name.endsWith("-loader") || name.endsWith("-worker"))) {
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        try {
            CountDownLatch fxDrain = new CountDownLatch(1);
            Platform.runLater(fxDrain::countDown);
            fxDrain.await(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            stmt.execute("PRAGMA journal_mode = DELETE;");
        } catch (Exception ignored) {
        }

        SessionContext.clearSession();
        DatabaseManager.resetCustomJdbcUrl();
    }

    @Test
    void shouldLoadAllSupplyChainAndReportingFxmlViewsWithoutInjectionErrors() throws Exception {
        List<String> fxmls = List.of(
                "/com/smartkhata/ui/views/PurchasesView.fxml",
                "/com/smartkhata/ui/views/SupplierListView.fxml",
                "/com/smartkhata/ui/views/InventoryView.fxml",
                "/com/smartkhata/ui/views/SalesView.fxml",
                "/com/smartkhata/ui/views/ReportsView.fxml",
                "/com/smartkhata/ui/views/SettingsView.fxml"
        );

        runOnFxThread(() -> {
            for (String path : fxmls) {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource(path));
                    Parent root = loader.load();
                    assertThat(root).as("Loaded root for " + path).isNotNull();
                    if (loader.getController() != null) {
                        loadedControllers.add(loader.getController());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed loading FXML " + path + ": " + e.getMessage(), e);
                }
            }
        });
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Runnable wrapped = () -> {
            try { action.run(); } catch (Throwable t) { err.set(t); } finally { latch.countDown(); }
        };
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                wrapped.run();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.setImplicitExit(false);
            Platform.runLater(wrapped);
        }
        assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) throw new AssertionError("FX smoke failed", err.get());
    }
}


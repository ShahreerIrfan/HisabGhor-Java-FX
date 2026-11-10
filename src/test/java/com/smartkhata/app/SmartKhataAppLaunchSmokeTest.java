package com.smartkhata.app;

import com.smartkhata.ui.ViewRouter;
import com.smartkhata.ui.icon.AppIconManager;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SmartKhataAppLaunchSmokeTest {

    @Test
    void shouldLaunchAndInitializeJavaFxStageCleanly(@TempDir Path tempDir) throws Exception {
        System.setProperty("smartkhata.home", tempDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        AtomicReference<Stage> testStage = new AtomicReference<>();
        AtomicReference<SmartKhataApp> appRef = new AtomicReference<>();

        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                try {
                    Stage stage = new Stage();
                    testStage.set(stage);
                    SmartKhataApp app = new SmartKhataApp();
                    appRef.set(app);
                    app.init();
                    app.start(stage);
                    latch.countDown();
                } catch (Throwable t) {
                    startupError.set(t);
                    latch.countDown();
                }
            });
        } catch (IllegalStateException e) {
            Platform.setImplicitExit(false);
            // If JavaFX toolkit was already initialized in the JVM
            Platform.runLater(() -> {
                try {
                    Stage stage = new Stage();
                    testStage.set(stage);
                    SmartKhataApp app = new SmartKhataApp();
                    appRef.set(app);
                    app.init();
                    app.start(stage);
                    latch.countDown();
                } catch (Throwable t) {
                    startupError.set(t);
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(startupError.get()).isNull();

        if (testStage.get() != null) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                // Test switching between all application screens
                SmartKhataApp.showLoginScreen();
                SmartKhataApp.showOnboardingWizard();
                SmartKhataApp.showMainApplication();

                // Test switching views via ViewRouter
                for (ViewRouter.ViewId viewId : ViewRouter.ViewId.values()) {
                    ViewRouter.navigateTo(viewId);
                }
                // Switch back to Dashboard
                ViewRouter.navigateTo(ViewRouter.ViewId.DASHBOARD);

                // Verify official multi-resolution SmartKhata storefront icons loaded on primary window
                assertThat(testStage.get().getIcons())
                        .hasSize(AppIconManager.ICON_SIZES.length)
                        .extracting(img -> (int) Math.round(img.getWidth()))
                        .containsExactly(16, 24, 32, 48, 64, 128, 256);

                testStage.get().close();
                if (appRef.get() != null) {
                    appRef.get().stop();
                }
                closeLatch.countDown();
            });
            closeLatch.await(5, TimeUnit.SECONDS);
        }
        System.clearProperty("smartkhata.home");
    }
}


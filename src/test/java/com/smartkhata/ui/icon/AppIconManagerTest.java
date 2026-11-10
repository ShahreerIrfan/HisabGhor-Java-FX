package com.smartkhata.ui.icon;

import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AppIconManagerTest {

    @Test
    void shouldLoadAllMultiResolutionOfficialStorefrontIconsFromClasspath() throws Exception {
        try (InputStream icoStream = AppIconManager.class.getResourceAsStream(AppIconManager.ICO_RESOURCE_PATH)) {
            assertThat(icoStream).isNotNull();
            byte[] header = icoStream.readNBytes(6);
            assertThat(header).hasSize(6);
            // Windows ICO magic header: reserved=0, type=1, count=7
            assertThat(header[0]).isEqualTo((byte) 0);
            assertThat(header[1]).isEqualTo((byte) 0);
            assertThat(header[2]).isEqualTo((byte) 1);
            assertThat(header[3]).isEqualTo((byte) 0);
            int frameCount = (header[4] & 0xFF) | ((header[5] & 0xFF) << 8);
            assertThat(frameCount).isEqualTo(AppIconManager.ICON_SIZES.length);
        }

        runOnFxThread(() -> {
            List<Image> icons = AppIconManager.getWindowIcons();
            assertThat(icons)
                    .hasSize(7)
                    .allSatisfy(img -> assertThat(img.isError()).isFalse())
                    .extracting(img -> (int) Math.round(img.getWidth()))
                    .containsExactly(16, 24, 32, 48, 64, 128, 256);

            // Verify transparency at corner and rich grocery-green hue inside squircle center band
            Image img64 = AppIconManager.getLogoImage(64).orElseThrow();
            assertThat(img64.getPixelReader().getColor(0, 0).getOpacity()).isLessThan(0.1);
            javafx.scene.paint.Color greenEdgeColor = img64.getPixelReader().getColor(6, 32);
            assertThat(greenEdgeColor.getGreen()).isGreaterThan(greenEdgeColor.getBlue());
            assertThat(greenEdgeColor.getGreen()).isGreaterThan(greenEdgeColor.getRed());
        });
    }

    @Test
    void shouldApplyIconsAutomaticallyAcrossDialogsChildStagesAndFutureWindows() throws Exception {
        runOnFxThread(() -> {
            AppIconManager.installGlobalWindowHook();

            // 1. Standard JavaFX Stage with manual apply + idempotency
            Stage mainStage = new Stage();
            AppIconManager.applyToStage(mainStage);
            AppIconManager.applyToStage(mainStage);
            assertThat(mainStage.getIcons()).hasSize(7);

            // 2. SmartKhataDialog (Complete Sale & Payment / PO / GRN / Customer / Confirm / Warning modals)
            Stage saleDialogStage = SmartKhataDialog.<Boolean>builder()
                    .owner(mainStage)
                    .size(SmartKhataDialog.Size.MEDIUM)
                    .title("Complete Sale & Payment")
                    .subtitle("Finalize invoice")
                    .icon(DialogComponents.SVG_RECEIPT, DialogComponents.Tone.SOFT_GREEN)
                    .content(new Label("Payment summary"))
                    .buildStage(new AtomicReference<>());
            assertThat(saleDialogStage.getIcons())
                    .hasSize(7)
                    .extracting(i -> (int) Math.round(i.getWidth()))
                    .containsExactly(16, 24, 32, 48, 64, 128, 256);

            // 3. Legacy/Future native Dialog<?> & Alert automatically configured via configureDialog(...)
            Dialog<String> genericDialog = AppIconManager.configureDialog(new Dialog<>());
            genericDialog.getDialogPane().setContent(new Label("Future dialog"));
            Stage dialogWindow = (Stage) genericDialog.getDialogPane().getScene().getWindow();
            assertThat(dialogWindow.getIcons()).hasSize(7);

            Alert warnAlert = AppIconManager.configureDialog(new Alert(Alert.AlertType.WARNING, "Low stock"));
            Stage alertWindow = (Stage) warnAlert.getDialogPane().getScene().getWindow();
            assertThat(alertWindow.getIcons()).hasSize(7);

            // 4. Unconfigured future native Stage receives icons automatically upon showing via global hook
            Stage futureUnconfiguredStage = new Stage(StageStyle.DECORATED);
            futureUnconfiguredStage.setScene(new Scene(new StackPane(new Label("Preview")), 120, 80));
            futureUnconfiguredStage.setX(-4000);
            futureUnconfiguredStage.setY(-4000);
            assertThat(futureUnconfiguredStage.getIcons()).isEmpty();
            futureUnconfiguredStage.show();
            try {
                assertThat(futureUnconfiguredStage.getIcons())
                        .hasSize(7)
                        .extracting(i -> (int) Math.round(i.getWidth()))
                        .containsExactly(16, 24, 32, 48, 64, 128, 256);
            } finally {
                futureUnconfiguredStage.close();
            }
        });
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Runnable wrapped = () -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
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
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            throw new AssertionError("JavaFX test action failed", error.get());
        }
    }
}


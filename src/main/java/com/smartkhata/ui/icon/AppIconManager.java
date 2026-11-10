package com.smartkhata.ui.icon;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized application and window title-bar icon manager for SmartKhata.
 * Loads multi-resolution official SmartKhata Grocery Storefront icons from
 * packaged classpath resources and automatically applies them across the
 * primary stage, all child modals, and any future windows/dialogs.
 */
public final class AppIconManager {

    private static final Logger log = LoggerFactory.getLogger(AppIconManager.class);

    public static final String ICO_RESOURCE_PATH = "/com/smartkhata/ui/images/icons/smartkhata.ico";
    public static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private static final AtomicBoolean GLOBAL_HOOK_INSTALLED = new AtomicBoolean(false);
    private static volatile List<Image> cachedWindowIcons;

    private AppIconManager() {}

    /**
     * Loads and caches all multi-resolution SmartKhata icons from bundled classpath resources.
     * Safe for packaged builds (EXE/MSI/ZIP) and development runs without relying on file paths.
     */
    public static List<Image> getWindowIcons() {
        List<Image> current = cachedWindowIcons;
        if (current != null) {
            return current;
        }
        synchronized (AppIconManager.class) {
            if (cachedWindowIcons != null) {
                return cachedWindowIcons;
            }
            List<Image> loaded = new ArrayList<>(ICON_SIZES.length);
            for (int size : ICON_SIZES) {
                String path = resourcePathForSize(size);
                loadResourceImage(path).ifPresent(loaded::add);
            }
            if (loaded.isEmpty()) {
                loadResourceImage("/com/smartkhata/ui/images/app-icon.png").ifPresent(loaded::add);
            }
            cachedWindowIcons = Collections.unmodifiableList(loaded);
            if (loaded.isEmpty()) {
                log.warn("No SmartKhata application icon resources could be loaded from classpath.");
            } else {
                log.debug("Loaded {} SmartKhata window icon resolutions.", loaded.size());
            }
            return cachedWindowIcons;
        }
    }

    /**
     * Returns the exact resource path for a given square icon dimension.
     */
    public static String resourcePathForSize(int size) {
        return "/com/smartkhata/ui/images/icons/smartkhata_" + size + ".png";
    }

    /**
     * Returns a specific icon size image from resources (useful for previews or UI brand badges).
     */
    public static Optional<Image> getLogoImage(int preferredSize) {
        return loadResourceImage(resourcePathForSize(preferredSize));
    }

    /**
     * Applies the official multi-resolution SmartKhata icon list onto the specified Stage.
     */
    public static void applyToStage(Stage stage) {
        if (stage == null) {
            return;
        }
        List<Image> icons = getWindowIcons();
        if (icons.isEmpty()) {
            return;
        }
        if (!stage.getIcons().equals(icons)) {
            stage.getIcons().setAll(icons);
        }
    }

    /**
     * Applies icons if the given Window is a Stage (ignored safely for PopupWindow tooltips/menus).
     */
    public static void applyToWindow(Window window) {
        if (window instanceof Stage stage) {
            applyToStage(stage);
        }
    }

    /**
     * Configures any standard JavaFX Dialog or Alert so its underlying Stage automatically
     * inherits the SmartKhata icon before display.
     */
    public static <T extends Dialog<?>> T configureDialog(T dialog) {
        if (dialog == null) {
            return null;
        }
        Runnable attach = () -> {
            if (dialog.getDialogPane() != null && dialog.getDialogPane().getScene() != null) {
                applyToWindow(dialog.getDialogPane().getScene().getWindow());
            }
        };
        dialog.setOnShowing(e -> attach.run());
        if (dialog.getDialogPane() != null) {
            dialog.getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    newScene.windowProperty().addListener((wObs, oldW, newW) -> applyToWindow(newW));
                    applyToWindow(newScene.getWindow());
                }
            });
            attach.run();
        }
        return dialog;
    }

    /**
     * Installs an application-wide listener on JavaFX Window.getWindows() so every
     * current and future Stage (including child dialogs and previews) receives the
     * official SmartKhata icons automatically without per-screen boilerplate.
     */
    public static void installGlobalWindowHook() {
        for (Window existing : List.copyOf(Window.getWindows())) {
            applyToWindow(existing);
        }
        if (!GLOBAL_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window added : change.getAddedSubList()) {
                        applyToWindow(added);
                    }
                }
            }
        });
        log.info("Installed global SmartKhata window icon hook.");
    }

    private static Optional<Image> loadResourceImage(String resourcePath) {
        try (InputStream is = AppIconManager.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Missing icon resource: {}", resourcePath);
                return Optional.empty();
            }
            Image img = new Image(is);
            if (img.isError()) {
                log.warn("Invalid image icon resource: {}", resourcePath);
                return Optional.empty();
            }
            return Optional.of(img);
        } catch (Exception e) {
            log.warn("Failed loading icon resource {}: {}", resourcePath, e.getMessage());
            return Optional.empty();
        }
    }
}


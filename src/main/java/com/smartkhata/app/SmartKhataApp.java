package com.smartkhata.app;

import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.impl.LocalAuthService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.ThemeManager;
import com.smartkhata.ui.icon.AppIconManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * Main JavaFX Application lifecycle coordinator for SmartKhata.
 * Orchestrates scene switching between Onboarding Wizard, Login Screen, and Main Application.
 */
public class SmartKhataApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(SmartKhataApp.class);

    private static Stage primaryStage;
    private static final AuthService authService = new LocalAuthService();

    @Override
    public void init() throws Exception {
        log.info("JavaFX Application init() started");
        AppBootstrap.initialize();
    }

    @Override
    public void start(Stage stage) {
        log.info("JavaFX Application start() on thread: {}", Thread.currentThread().getName());
        primaryStage = stage;
        try {
            ThemeManager.applyDefaultTheme();

            // Install centralized multi-resolution window icon manager across primary & all future stages
            AppIconManager.installGlobalWindowHook();
            AppIconManager.applyToStage(primaryStage);

            primaryStage.setTitle(AppConfig.getAppTitle());
            primaryStage.setMinWidth(1100);
            primaryStage.setMinHeight(720);

            // Determine initial screen based on setup state
            if (authService.isFirstRun()) {
                log.info("No active store found. Launching first-run onboarding wizard...");
                showOnboardingWizard();
            } else if (SessionContext.isAuthenticated()) {
                log.info("Active session detected. Launching main dashboard...");
                showMainApplication();
            } else {
                log.info("Store exists. Launching local login screen...");
                showLoginScreen();
            }

            primaryStage.show();
            log.info("SmartKhata primary window displayed successfully");

        } catch (Throwable t) {
            log.error("Fatal error during JavaFX startup: {}", t.getMessage(), t);
            AlertHelper.showStartupError(primaryStage, "Application Startup Failure",
                    "SmartKhata failed to initialize the main window interface.", t);
        }
    }

    public static void showOnboardingWizard() {
        switchScene("/com/smartkhata/ui/views/OnboardingWizardView.fxml", 1100, 760);
    }

    public static void showLoginScreen() {
        switchScene("/com/smartkhata/ui/views/LoginView.fxml", 1080, 720);
    }

    public static void showMainApplication() {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double targetW = Math.max(1080, Math.min(1440, bounds.getWidth() - 24));
        double targetH = Math.max(700, Math.min(900, bounds.getHeight() - 36));
        switchScene("/com/smartkhata/ui/views/MainShell.fxml", targetW, targetH);
        primaryStage.centerOnScreen();
    }

    private static void switchScene(String fxmlPath, double width, double height) {
        try {
            URL url = SmartKhataApp.class.getResource(fxmlPath);
            if (url == null) {
                throw new IllegalStateException("FXML not found: " + fxmlPath);
            }
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            Scene scene = new Scene(root, width, height);
            primaryStage.setScene(scene);
        } catch (Exception e) {
            log.error("Failed to switch scene to {}: {}", fxmlPath, e.getMessage(), e);
            AlertHelper.showStartupError(primaryStage, "Navigation Error", "Could not load screen: " + fxmlPath, e);
        }
    }

    @Override
    public void stop() {
        log.info("JavaFX Application stop() invoked");
        AppBootstrap.shutdown();
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void main(String[] args) {
        launch(args);
    }
}

package com.smartkhata.controller;

import com.smartkhata.app.SmartKhataApp;
import com.smartkhata.model.dto.OnboardingRequestDto;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.impl.LocalAuthService;
import com.smartkhata.ui.AlertHelper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OnboardingWizardController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingWizardController.class);

    private final AuthService authService = new LocalAuthService();

    @FXML private TextField businessNameField;
    @FXML private TextField ownerNameField;
    @FXML private TextField usernameField;
    @FXML private TextField phoneField;
    @FXML private TextField addressField;
    @FXML private ComboBox<String> languageCombo;

    @FXML private PasswordField pinField;
    @FXML private PasswordField confirmPinField;
    @FXML private CheckBox chkLoadSampleData;

    @FXML private VBox stepOnePane;
    @FXML private VBox stepTwoPane;
    @FXML private Label recoveryCodeLabel;
    @FXML private CheckBox recoveryConfirmedCheck;
    @FXML private Label errorLabel;

    private String generatedRecoveryToken;

    @FXML
    public void initialize() {
        log.info("Initializing OnboardingWizardController...");

        if (languageCombo != null) {
            languageCombo.getItems().setAll("English", "বাংলা (Bengali)");
            languageCombo.setValue("English");
        }

        if (stepTwoPane != null) {
            stepTwoPane.setVisible(false);
            stepTwoPane.setManaged(false);
        }

        if (errorLabel != null) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        }
    }

    @FXML
    private void onProceedToStepTwo() {
        String bizName = businessNameField != null ? businessNameField.getText() : "";
        String ownerName = ownerNameField != null ? ownerNameField.getText() : "";
        String username = usernameField != null ? usernameField.getText() : "";
        String pin = pinField != null ? pinField.getText() : "";
        String confirmPin = confirmPinField != null ? confirmPinField.getText() : "";

        if (bizName.isBlank() || ownerName.isBlank() || username.isBlank()) {
            showError("Please fill in business name, owner name, and username.");
            return;
        }

        if (pin.length() < 4) {
            showError("Security PIN or Password must be at least 4 characters.");
            return;
        }

        if (!pin.equals(confirmPin)) {
            showError("PIN confirmation does not match.");
            return;
        }

        try {
            String lang = "English".equals(languageCombo.getValue()) ? "en" : "bn";
            OnboardingRequestDto request = new OnboardingRequestDto(
                    bizName,
                    ownerName,
                    username,
                    phoneField != null ? phoneField.getText() : null,
                    addressField != null ? addressField.getText() : null,
                    "BDT",
                    lang,
                    pin.toCharArray()
            );

            // Initialize business in SQLite
            generatedRecoveryToken = authService.initializeBusiness(request);

            if (recoveryCodeLabel != null) {
                recoveryCodeLabel.setText(generatedRecoveryToken);
            }

            // Switch to Step 2 (Recovery code display)
            stepOnePane.setVisible(false);
            stepOnePane.setManaged(false);

            stepTwoPane.setVisible(true);
            stepTwoPane.setManaged(true);

            if (errorLabel != null) errorLabel.setVisible(false);

        } catch (Exception e) {
            log.error("Failed to initialize business: {}", e.getMessage(), e);
            showError(e.getMessage());
        }
    }

    @FXML
    private void onFinishSetup() {
        if (recoveryConfirmedCheck != null && !recoveryConfirmedCheck.isSelected()) {
            showError("Please confirm that you have safely recorded your offline recovery code.");
            return;
        }

        if (chkLoadSampleData != null && chkLoadSampleData.isSelected()) {
            try {
                if (com.smartkhata.service.api.SessionContext.getCurrentBusiness() != null &&
                        com.smartkhata.service.api.SessionContext.getCurrentUser() != null) {
                    com.smartkhata.service.sample.SampleDataSeeder.seedDemoDataForBusiness(
                            com.smartkhata.service.api.SessionContext.getCurrentBusiness().id(),
                            com.smartkhata.service.api.SessionContext.getCurrentUser().id()
                    );
                }
            } catch (Exception e) {
                log.error("Failed to seed initial demo data: {}", e.getMessage(), e);
            }
        }

        log.info("Onboarding wizard finished. Navigating to main dashboard...");
        SmartKhataApp.showMainApplication();
    }

    private void showError(String message) {
        if (errorLabel != null) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
    }
}


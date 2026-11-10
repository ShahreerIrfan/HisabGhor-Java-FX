package com.smartkhata.controller;

import com.smartkhata.app.SmartKhataApp;
import com.smartkhata.dao.SqliteBusinessDao;
import com.smartkhata.dao.SqliteUserDao;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.LoginResult;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;
import com.smartkhata.service.api.AuthService;
import com.smartkhata.service.impl.LocalAuthService;
import com.smartkhata.ui.AlertHelper;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Optional;

public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final AuthService authService = new LocalAuthService();

    @FXML private Label storeNameLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button signInButton;

    @FXML
    public void initialize() {
        log.info("Initializing LoginController...");

        if (errorLabel != null) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        }

        // Pre-populate store name and primary owner username if present
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<User> ownerOpt = new SqliteUserDao().findFirstByRole(conn, UserRole.OWNER);
            if (ownerOpt.isPresent()) {
                User owner = ownerOpt.get();
                if (usernameField != null) usernameField.setText(owner.username());

                Optional<Business> bizOpt = new SqliteBusinessDao().findById(conn, owner.businessId());
                if (bizOpt.isPresent() && storeNameLabel != null) {
                    storeNameLabel.setText(bizOpt.get().name());
                }
            }
        } catch (Exception e) {
            log.warn("Could not pre-populate login fields: {}", e.getMessage());
        }

        if (passwordField != null) {
            Platform.runLater(() -> passwordField.requestFocus());
        }
    }

    @FXML
    private void onSignIn() {
        String username = usernameField != null ? usernameField.getText() : "";
        char[] password = passwordField != null ? passwordField.getText().toCharArray() : new char[0];

        if (username.isBlank() || password.length == 0) {
            showError("Please enter your username and password or PIN.");
            return;
        }

        signInButton.setDisable(true);
        LoginResult result = authService.login(username, password);
        signInButton.setDisable(false);

        if (result.isSuccess()) {
            log.info("Sign in successful for user [{}]", username);
            SmartKhataApp.showMainApplication();
        } else {
            showError(result.message());
        }
    }

    @FXML
    private void onForgotPassword() {
        Window owner = usernameField != null && usernameField.getScene() != null
                ? usernameField.getScene().getWindow() : null;

        TextField tokenField = new TextField();
        tokenField.setPromptText("SK-XXXX-XXXX-XXXX-XXXX");
        HBox tokenWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_LOCK, tokenField);

        PasswordField newPassField = new PasswordField();
        newPassField.setPromptText("New PIN or Password (min 4 characters)");
        HBox newPassWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_LOCK, newPassField);

        PasswordField confirmPassField = new PasswordField();
        confirmPassField.setPromptText("Re-enter new PIN or password");
        HBox confirmPassWrap = DialogComponents.textFieldWrap(DialogComponents.SVG_LOCK, confirmPassField);

        VBox card = DialogComponents.sectionCard(
                DialogComponents.sectionHeader(
                        DialogComponents.SVG_LOCK,
                        "Recovery Token Verification",
                        "Enter the 16-character recovery token generated during initial store setup."
                ),
                DialogComponents.formField("Recovery Token", true, "Format: SK-XXXX-XXXX-XXXX-XXXX", tokenWrap),
                DialogComponents.formField("New PIN / Password", true, "Minimum 4 characters", newPassWrap),
                DialogComponents.formField("Confirm New PIN / Password", true, null, confirmPassWrap)
        );

        Optional<Boolean> res = SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title("Offline Account Recovery")
                .subtitle("Reset security credential using your offline recovery code")
                .icon(DialogComponents.SVG_LOCK, DialogComponents.Tone.SOFT_GREEN)
                .content(card)
                .initialFocus(tokenField)
                .cancelLabel("Cancel")
                .primaryAction("Reset Password", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String token = DialogComponents.trimToNull(tokenField.getText());
                    if (token == null) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Please enter your 16-character recovery token.", tokenField);
                    }
                    String newPass = newPassField.getText() != null ? newPassField.getText() : "";
                    if (newPass.length() < 4) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "New PIN or password must be at least 4 characters.", newPassField);
                    }
                    String confirmPass = confirmPassField.getText() != null ? confirmPassField.getText() : "";
                    if (!newPass.equals(confirmPass)) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Confirmation password does not match. Please re-enter.", confirmPassField);
                    }
                    long bizId = 1L; // Primary store id
                    boolean recovered = authService.recoverAccount(bizId, token, newPass.toCharArray());
                    if (!recovered) {
                        throw new SmartKhataDialog.ValidationFocusException(
                                "Invalid recovery code. Security credential was not modified.", tokenField);
                    }
                    return Boolean.TRUE;
                })
                .showAndWait();

        if (res.orElse(false)) {
            AlertHelper.showSuccess(owner, "Recovery Success",
                    "Your master password has been reset. Please sign in with your new credential.");
            if (passwordField != null) passwordField.clear();
        }
    }

    private void showError(String message) {
        if (errorLabel != null) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
    }
}


package com.smartkhata.ui;

import com.smartkhata.app.AppBootstrap;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Standardized SmartKhata dialog helper powering all alerts, confirmations,
 * error details, prompt inputs, and selection popups using the Global Dialog Design System.
 */
public final class AlertHelper {

    private static final Logger log = LoggerFactory.getLogger(AlertHelper.class);

    private AlertHelper() {}

    public static void showStartupError(Stage parent, String title, String userMessage, Throwable throwable) {
        showStartupError((Window) parent, title, userMessage, throwable);
    }

    public static void showStartupError(Window parent, String title, String userMessage, Throwable throwable) {
        log.error("Displaying error dialog [{}]: {}", title, userMessage, throwable);

        Runnable showTask = () -> {
            VBox body = new VBox(12.0);

            Label msgLbl = new Label(userMessage != null && !userMessage.isBlank()
                    ? userMessage
                    : "An unexpected error occurred. Please review logs or retry.");
            msgLbl.setWrapText(true);
            msgLbl.setStyle("-fx-font-size: 13.5px; -fx-text-fill: #18283B;");
            body.getChildren().add(msgLbl);

            if (throwable != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = AppBootstrap.sanitizeMessage(sw.toString());

                TextArea textArea = new TextArea(stackTrace);
                textArea.setEditable(false);
                textArea.setWrapText(true);
                textArea.setPrefRowCount(7);
                textArea.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 11.5px; -fx-text-fill: #18283B;");

                TitledPane tp = new TitledPane("Technical Details (Logs)", textArea);
                tp.setExpanded(false);
                tp.setStyle("-fx-font-size: 12px; -fx-font-weight: 600;");
                body.getChildren().add(tp);
            }

            SmartKhataDialog.builder()
                    .owner(parent)
                    .size(throwable != null ? SmartKhataDialog.Size.MEDIUM : SmartKhataDialog.Size.SMALL)
                    .title(title != null && !title.isBlank() ? title : "Error")
                    .subtitle("Action could not be completed")
                    .icon(DialogComponents.SVG_WARNING, DialogComponents.Tone.DANGER)
                    .greenHeaderAccent(false)
                    .content(body)
                    .infoOnly(true)
                    .primaryAction("Close", DialogComponents.SVG_CLOSE, SmartKhataDialog.ActionTone.DANGER)
                    .showAndWait();
        };

        dispatchFx(showTask, userMessage, throwable);
    }

    public static void showInfo(Stage parent, String title, String message) {
        showInfo((Window) parent, title, message);
    }

    public static void showInfo(Window parent, String title, String message) {
        String resolvedTitle = title != null && !title.isBlank() ? title : "Notice";
        String lower = resolvedTitle.toLowerCase();
        boolean isSuccess = lower.contains("created") || lower.contains("updated") || lower.contains("saved")
                || lower.contains("success") || lower.contains("complete") || lower.contains("posted")
                || lower.contains("exported") || lower.contains("confirmed") || lower.contains("restored")
                || lower.contains("sent") || lower.contains("recorded") || lower.contains("adjusted")
                || lower.contains("processed");
        boolean isWarn = lower.contains("validation") || lower.contains("invalid") || lower.contains("empty")
                || lower.contains("cannot") || lower.contains("failed") || lower.contains("permission")
                || lower.contains("warning") || lower.contains("insufficient") || lower.contains("overpayment")
                || lower.contains("limit") || lower.contains("out of stock");

        if (isSuccess) {
            showSuccess(parent, resolvedTitle, message);
        } else if (isWarn) {
            showWarning(parent, resolvedTitle, message);
        } else {
            showMessageModal(parent, SmartKhataDialog.ActionTone.PRIMARY, DialogComponents.SVG_INFO,
                    DialogComponents.Tone.SOFT_GREEN, resolvedTitle, "Information", message);
        }
    }

    public static void showSuccess(Window owner, String title, String message) {
        showMessageModal(owner, SmartKhataDialog.ActionTone.PRIMARY, DialogComponents.SVG_CHECK,
                DialogComponents.Tone.SUCCESS, title, "Completed successfully", message);
    }

    public static void showWarning(Window owner, String title, String message) {
        showMessageModal(owner, SmartKhataDialog.ActionTone.WARNING, DialogComponents.SVG_WARNING,
                DialogComponents.Tone.WARNING, title, "Attention required", message);
    }

    public static void showError(Window owner, String title, String message) {
        showStartupError(owner, title, message, null);
    }

    /**
     * Shows a unified semantic confirmation modal (Delete = Red, Warning = Amber, Normal = Green)
     * with optional consequence explanation block. Returns true only when the user confirms.
     */
    public static boolean confirm(
            Window owner,
            SmartKhataDialog.ActionTone tone,
            String title,
            String message,
            String consequenceNote,
            String confirmLabel
    ) {
        DialogComponents.Tone badgeTone = switch (tone) {
            case DANGER -> DialogComponents.Tone.DANGER;
            case WARNING -> DialogComponents.Tone.WARNING;
            case PRIMARY -> DialogComponents.Tone.SOFT_GREEN;
        };
        String iconSvg = switch (tone) {
            case DANGER -> DialogComponents.SVG_TRASH;
            case WARNING -> DialogComponents.SVG_WARNING;
            case PRIMARY -> DialogComponents.SVG_CHECK;
        };

        VBox body = new VBox(12.0);
        Label msgLbl = new Label(message != null ? message : "Please confirm this action.");
        msgLbl.setWrapText(true);
        msgLbl.setStyle("-fx-font-size: 13.5px; -fx-text-fill: #18283B;");
        body.getChildren().add(msgLbl);

        if (consequenceNote != null && !consequenceNote.isBlank()) {
            String noteColor = tone == SmartKhataDialog.ActionTone.DANGER ? "#B91C1C" : "#92400E";
            String iconColor = tone == SmartKhataDialog.ActionTone.DANGER ? "#DC3545" : "#D97706";
            Label noteLbl = new Label(consequenceNote);
            noteLbl.setWrapText(true);
            noteLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: " + noteColor + ";");
            HBox noteBox = new HBox(8.0, DialogComponents.iconBox(DialogComponents.SVG_INFO, iconColor, 15.0), noteLbl);
            noteBox.getStyleClass().add(tone == SmartKhataDialog.ActionTone.DANGER
                    ? "sk-dlg-consequence-danger" : "sk-dlg-consequence-box");
            noteBox.setAlignment(Pos.CENTER_LEFT);
            body.getChildren().add(noteBox);
        }

        Optional<Boolean> result = SmartKhataDialog.<Boolean>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title(title)
                .subtitle(tone == SmartKhataDialog.ActionTone.DANGER ? "Destructive action confirmation" : "Confirm action")
                .icon(iconSvg, badgeTone)
                .greenHeaderAccent(tone == SmartKhataDialog.ActionTone.PRIMARY)
                .content(body)
                .cancelLabel("Cancel")
                .primaryAction(confirmLabel != null ? confirmLabel : "Confirm", iconSvg, tone)
                .onSubmit(() -> Boolean.TRUE)
                .showAndWait();

        return result.orElse(false);
    }

    /**
     * Unified single-input text prompt modal replacing raw JavaFX TextInputDialog.
     */
    public static Optional<String> promptInput(
            Window owner,
            String title,
            String subtitle,
            String fieldLabel,
            String initialValue,
            String promptText,
            boolean required,
            String confirmLabel
    ) {
        TextField txt = new TextField(initialValue != null ? initialValue : "");
        txt.setPromptText(promptText != null ? promptText : "");
        HBox wrap = DialogComponents.textFieldWrap(DialogComponents.SVG_NOTE, txt);

        VBox content = DialogComponents.sectionCard(
                DialogComponents.formField(fieldLabel, required, null, wrap)
        );

        return SmartKhataDialog.<String>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title(title)
                .subtitle(subtitle)
                .icon(DialogComponents.SVG_EDIT, DialogComponents.Tone.SOFT_GREEN)
                .content(content)
                .initialFocus(txt)
                .primaryAction(confirmLabel != null ? confirmLabel : "Confirm", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    String val = txt.getText() != null ? txt.getText().trim() : "";
                    if (required && val.isBlank()) {
                        throw new SmartKhataDialog.ValidationFocusException(fieldLabel + " is required.", txt);
                    }
                    return val;
                })
                .showAndWait();
    }

    /**
     * Unified option selection modal replacing raw JavaFX ChoiceDialog.
     */
    public static <T> Optional<T> chooseOption(
            Window owner,
            String title,
            String subtitle,
            String fieldLabel,
            List<T> options,
            T defaultOption,
            Function<T, String> labelExtractor,
            String confirmLabel
    ) {
        ComboBox<T> combo = DialogComponents.styleCombo(new ComboBox<>(FXCollections.observableArrayList(options)));
        Function<T, String> extractor = labelExtractor != null ? labelExtractor : Object::toString;
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : extractor.apply(item));
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select an option..." : extractor.apply(item));
            }
        });
        if (defaultOption != null) {
            combo.getSelectionModel().select(defaultOption);
        } else if (options != null && !options.isEmpty()) {
            combo.getSelectionModel().selectFirst();
        }

        VBox content = DialogComponents.sectionCard(
                DialogComponents.formField(fieldLabel, true, null, combo)
        );

        return SmartKhataDialog.<T>builder()
                .owner(owner)
                .size(SmartKhataDialog.Size.SMALL)
                .title(title)
                .subtitle(subtitle)
                .icon(DialogComponents.SVG_CLIPBOARD, DialogComponents.Tone.SOFT_GREEN)
                .content(content)
                .initialFocus(combo)
                .primaryAction(confirmLabel != null ? confirmLabel : "Continue", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.PRIMARY)
                .onSubmit(() -> {
                    T picked = combo.getValue();
                    if (picked == null) {
                        throw new SmartKhataDialog.ValidationFocusException("Please select an option to continue.", combo);
                    }
                    return picked;
                })
                .showAndWait();
    }

    private static void showMessageModal(
            Window parent,
            SmartKhataDialog.ActionTone buttonTone,
            String svgPath,
            DialogComponents.Tone badgeTone,
            String title,
            String subtitle,
            String message
    ) {
        Runnable showTask = () -> {
            Label msgLbl = new Label(message != null ? message : "");
            msgLbl.setWrapText(true);
            msgLbl.setStyle("-fx-font-size: 13.5px; -fx-text-fill: #18283B;");

            VBox body = DialogComponents.sectionCard(msgLbl);
            SmartKhataDialog.builder()
                    .owner(parent)
                    .size(SmartKhataDialog.Size.SMALL)
                    .title(title)
                    .subtitle(subtitle)
                    .icon(svgPath, badgeTone)
                    .greenHeaderAccent(buttonTone == SmartKhataDialog.ActionTone.PRIMARY)
                    .content(body)
                    .infoOnly(true)
                    .primaryAction("OK", DialogComponents.SVG_CHECK, buttonTone)
                    .showAndWait();
        };

        dispatchFx(showTask, message, null);
    }

    private static void dispatchFx(Runnable showTask, String fallbackLog, Throwable throwable) {
        if (Platform.isFxApplicationThread()) {
            showTask.run();
        } else {
            try {
                Platform.runLater(showTask);
            } catch (IllegalStateException e) {
                log.debug("FX toolkit unavailable for dialog [{}]", fallbackLog);
                if (throwable != null) {
                    log.error("Startup error details: ", throwable);
                }
            }
        }
    }
}

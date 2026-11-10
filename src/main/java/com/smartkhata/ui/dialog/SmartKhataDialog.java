package com.smartkhata.ui.dialog;

import com.smartkhata.ui.icon.AppIconManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Unified floating grocery-theme modal dialog shell enforcing consistent sizing,
 * typography, header/footer layout, inline error handling, keyboard usability,
 * and responsive desktop containment across the entire SmartKhata application.
 */
public final class SmartKhataDialog {

    public enum Size {
        SMALL(440.0, 480.0, 380.0, 560.0),
        MEDIUM(660.0, 760.0, 520.0, 720.0),
        LARGE(980.0, 1080.0, 700.0, 790.0);

        final double prefWidth;
        final double maxWidth;
        final double minWidth;
        final double maxHeight;

        Size(double prefWidth, double maxWidth, double minWidth, double maxHeight) {
            this.prefWidth = prefWidth;
            this.maxWidth = maxWidth;
            this.minWidth = minWidth;
            this.maxHeight = maxHeight;
        }
    }

    public enum ActionTone {
        PRIMARY,
        WARNING,
        DANGER
    }

    /**
     * Live handle exposed to dialog content builders for showing inline validation errors,
     * closing the modal safely, or toggling loading state.
     */
    public static final class DialogHandle {
        private final HBox errorBanner;
        private final VBox errorHolder;
        private final Label lblError;
        private final Button btnPrimary;
        private final Button btnCancel;
        private final Runnable closeAction;
        private final AtomicBoolean busy = new AtomicBoolean(false);
        private final String defaultPrimaryLabel;
        private Node lastErrorNode;

        private DialogHandle(
                HBox errorBanner,
                VBox errorHolder,
                Label lblError,
                Button btnPrimary,
                Button btnCancel,
                String defaultPrimaryLabel,
                Runnable closeAction
        ) {
            this.errorBanner = errorBanner;
            this.errorHolder = errorHolder;
            this.lblError = lblError;
            this.btnPrimary = btnPrimary;
            this.btnCancel = btnCancel;
            this.defaultPrimaryLabel = defaultPrimaryLabel;
            this.closeAction = closeAction;
        }

        public void showError(String message) {
            showError(message, null);
        }

        public void showError(String message, Node focusTarget) {
            clearFieldError();
            lblError.setText(message != null && !message.isBlank() ? message : "Please check your inputs.");
            errorBanner.setVisible(true);
            errorBanner.setManaged(true);
            errorHolder.setVisible(true);
            errorHolder.setManaged(true);
            if (focusTarget != null) {
                DialogComponents.markFieldError(focusTarget, true);
                lastErrorNode = focusTarget;
                focusTarget.requestFocus();
            }
        }

        public void clearError() {
            clearFieldError();
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
            errorHolder.setVisible(false);
            errorHolder.setManaged(false);
        }

        private void clearFieldError() {
            if (lastErrorNode != null) {
                DialogComponents.markFieldError(lastErrorNode, false);
                lastErrorNode = null;
            }
        }

        public void setBusy(boolean isBusy, String busyLabel) {
            busy.set(isBusy);
            if (btnPrimary != null) {
                btnPrimary.setDisable(isBusy);
                btnPrimary.setText(isBusy ? (busyLabel != null ? busyLabel : "Saving...") : defaultPrimaryLabel);
            }
            if (btnCancel != null) {
                btnCancel.setDisable(isBusy);
            }
        }

        public boolean isBusy() {
            return busy.get();
        }

        public void close() {
            closeAction.run();
        }
    }

    /**
     * Exposed modal node parts supporting deterministic unit/FX testing without showing native blocking stages.
     */
    public record FrameParts(
            StackPane rootPane,
            StackPane cardShell,
            StackPane headerArea,
            Label lblTitle,
            Label lblSubtitle,
            HBox errorBanner,
            Label lblError,
            ScrollPane bodyScroll,
            Button btnCancel,
            Button btnPrimary,
            DialogHandle handle
    ) {}

    public static final class Builder<T> {
        private Window owner;
        private Size size = Size.MEDIUM;
        private String title = "Dialog";
        private String subtitle;
        private String iconSvg = DialogComponents.SVG_INFO;
        private DialogComponents.Tone iconTone = DialogComponents.Tone.SOFT_GREEN;
        private boolean greenHeaderAccent = true;
        private boolean groceryIllustration = false;
        private boolean infoOnly = false;
        private String cancelLabel = "Cancel";
        private String primaryLabel = "Save";
        private String primarySvg = DialogComponents.SVG_CHECK;
        private ActionTone actionTone = ActionTone.PRIMARY;
        private String shortcutHint = "Ctrl + Enter to submit  •  Esc to close";
        private Node initialFocus;
        private Node content;
        private Double prefHeight;
        private Supplier<T> submitSupplier;
        private boolean asyncSubmit = false;
        private String busyLabel = "Saving...";

        private Builder() {}

        public Builder<T> owner(Window owner) { this.owner = owner; return this; }
        public Builder<T> size(Size size) { this.size = size != null ? size : Size.MEDIUM; return this; }
        public Builder<T> title(String title) { this.title = title; return this; }
        public Builder<T> subtitle(String subtitle) { this.subtitle = subtitle; return this; }
        public Builder<T> icon(String svgPath, DialogComponents.Tone tone) { this.iconSvg = svgPath; this.iconTone = tone; return this; }
        public Builder<T> greenHeaderAccent(boolean val) { this.greenHeaderAccent = val; return this; }
        public Builder<T> groceryIllustration(boolean val) { this.groceryIllustration = val; return this; }
        public Builder<T> infoOnly(boolean val) { this.infoOnly = val; return this; }
        public Builder<T> cancelLabel(String label) { this.cancelLabel = label; return this; }
        public Builder<T> primaryAction(String label, String svgPath, ActionTone tone) {
            this.primaryLabel = label;
            this.primarySvg = svgPath;
            this.actionTone = tone != null ? tone : ActionTone.PRIMARY;
            return this;
        }
        public Builder<T> shortcutHint(String hint) { this.shortcutHint = hint; return this; }
        public Builder<T> prefHeight(double h) { this.prefHeight = h; return this; }
        public Builder<T> content(Node content) { this.content = content; return this; }
        public Builder<T> initialFocus(Node node) { this.initialFocus = node; return this; }
        public Builder<T> onSubmit(Supplier<T> submitSupplier) { this.submitSupplier = submitSupplier; return this; }
        public Builder<T> asyncSubmit(boolean async, String busyText) {
            this.asyncSubmit = async;
            if (busyText != null) this.busyLabel = busyText;
            return this;
        }

        public FrameParts buildFrame(Runnable closeAction, java.util.function.Consumer<T> onSuccess) {
            AtomicReference<DialogHandle> handleRef = new AtomicReference<>();

            Label lblError = new Label();
            lblError.setWrapText(true);
            lblError.setStyle("-fx-text-fill: #DC3545; -fx-font-size: 12.5px; -fx-font-weight: 600;");
            HBox errorBanner = new HBox(8.0, DialogComponents.iconBox(DialogComponents.SVG_INFO, "#DC3545", 15.0), lblError);
            errorBanner.getStyleClass().add("sk-modal-error-banner");
            errorBanner.setAlignment(Pos.CENTER_LEFT);
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
            VBox errorHolder = new VBox(errorBanner);
            errorHolder.setPadding(new Insets(10, 22, 0, 22));
            errorHolder.setVisible(false);
            errorHolder.setManaged(false);

            // Header
            StackPane badge = DialogComponents.headerBadge(iconSvg, iconTone);
            Label lblTitle = new Label(title);
            lblTitle.getStyleClass().add(size == Size.SMALL ? "sk-dlg-title-sm" : "sk-dlg-title");
            lblTitle.setWrapText(true);

            VBox titleStack = new VBox(2.0, lblTitle);
            Label lblSubtitle = new Label(subtitle != null ? subtitle : "");
            lblSubtitle.getStyleClass().add("sk-dlg-subtitle");
            lblSubtitle.setWrapText(true);
            if (subtitle != null && !subtitle.isBlank()) {
                titleStack.getChildren().add(lblSubtitle);
            }
            titleStack.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(titleStack, Priority.ALWAYS);

            Button btnClose = new Button();
            btnClose.setGraphic(DialogComponents.iconBox(DialogComponents.SVG_CLOSE, "#68778A", 16.0));
            btnClose.getStyleClass().add("sk-modal-close-btn");
            btnClose.setTooltip(new Tooltip("Close (Esc)"));
            btnClose.setOnAction(e -> {
                if (handleRef.get() == null || !handleRef.get().isBusy()) {
                    closeAction.run();
                }
            });

            HBox headerRow = new HBox(14.0, badge, titleStack, btnClose);
            headerRow.setAlignment(Pos.CENTER_LEFT);
            headerRow.setPadding(new Insets(16.0, 20.0, 14.0, 22.0));

            StackPane headerArea = new StackPane();
            if (greenHeaderAccent) {
                SVGPath wave = new SVGPath();
                wave.setContent("M 0,0 L 280,0 L 280,72 C 185,66 95,34 0,0 Z");
                wave.setFill(Color.web("#D4F2DB", 0.50));
                wave.setMouseTransparent(true);
                StackPane.setAlignment(wave, Pos.TOP_RIGHT);
                headerArea.getChildren().add(wave);
                headerArea.getStyleClass().add("sk-dlg-header");
            } else {
                headerArea.getStyleClass().add("sk-dlg-header-neutral");
            }

            if (groceryIllustration) {
                ImageView art = loadBasketArt();
                if (art != null) {
                    StackPane.setAlignment(art, Pos.BOTTOM_RIGHT);
                    StackPane.setMargin(art, new Insets(0, 56, 0, 0));
                    headerArea.getChildren().add(art);
                }
            }
            headerArea.getChildren().add(headerRow);

            // Body ScrollPane
            VBox contentHost = new VBox();
            contentHost.setPadding(new Insets(16.0, 22.0, 18.0, 22.0));
            if (content != null) {
                contentHost.getChildren().add(content);
            }
            ScrollPane bodyScroll = new ScrollPane(contentHost);
            bodyScroll.getStyleClass().add("sk-dlg-scroll");
            bodyScroll.setFitToWidth(true);
            bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            bodyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            VBox.setVgrow(bodyScroll, Priority.ALWAYS);

            // Footer
            Button btnCancel = new Button(cancelLabel);
            btnCancel.getStyleClass().add("sk-dlg-btn-secondary");
            btnCancel.setOnAction(e -> {
                if (handleRef.get() == null || !handleRef.get().isBusy()) {
                    closeAction.run();
                }
            });

            Button btnPrimary = new Button(primaryLabel);
            String primaryCls = switch (actionTone) {
                case DANGER -> "sk-dlg-btn-danger";
                case WARNING -> "sk-dlg-btn-warning";
                case PRIMARY -> "sk-dlg-btn-primary";
            };
            btnPrimary.getStyleClass().add(primaryCls);
            if (primarySvg != null) {
                btnPrimary.setGraphic(DialogComponents.iconBox(primarySvg, "#FFFFFF", 15.0));
            }

            DialogHandle handle = new DialogHandle(errorBanner, errorHolder, lblError, btnPrimary, btnCancel, primaryLabel, closeAction);
            handleRef.set(handle);

            if (content != null) {
                content.getProperties().put("skDialogHandle", handle);
            }

            btnPrimary.setOnAction(e -> {
                if (handle.isBusy()) return;
                handle.clearError();
                if (submitSupplier == null) {
                    onSuccess.accept(null);
                    closeAction.run();
                    return;
                }
                if (asyncSubmit) {
                    handle.setBusy(true, busyLabel);
                    CompletableFuture.supplyAsync(submitSupplier)
                            .whenComplete((res, throwable) -> Platform.runLater(() -> {
                                handle.setBusy(false, null);
                                if (throwable != null) {
                                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                                    if (cause instanceof ValidationFocusException vex) {
                                        handle.showError(vex.getMessage(), vex.getFocusTarget());
                                    } else {
                                        handle.showError(cause.getMessage());
                                    }
                                } else {
                                    onSuccess.accept(res);
                                    closeAction.run();
                                }
                            }));
                } else {
                    try {
                        T res = submitSupplier.get();
                        onSuccess.accept(res);
                        closeAction.run();
                    } catch (ValidationFocusException vex) {
                        handle.showError(vex.getMessage(), vex.getFocusTarget());
                    } catch (Exception ex) {
                        handle.showError(ex.getMessage());
                    }
                }
            });

            HBox footer = new HBox(10.0);
            footer.getStyleClass().add("sk-dlg-footer");
            footer.setAlignment(Pos.CENTER_LEFT);
            if (size != Size.SMALL && shortcutHint != null && !shortcutHint.isBlank()) {
                Label hint = new Label(shortcutHint);
                hint.getStyleClass().add("sk-dlg-helper-text");
                footer.getChildren().add(hint);
            }
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            footer.getChildren().add(spacer);
            if (infoOnly) {
                footer.getChildren().add(btnPrimary);
            } else {
                footer.getChildren().addAll(btnCancel, btnPrimary);
            }

            VBox topArea = new VBox(0, headerArea, errorHolder);
            topArea.setStyle("-fx-background-color: #FFFFFF;");

            BorderPane modalFrame = new BorderPane();
            modalFrame.setTop(topArea);
            modalFrame.setCenter(bodyScroll);
            modalFrame.setBottom(footer);

            Rectangle clipRect = new Rectangle();
            clipRect.widthProperty().bind(modalFrame.widthProperty());
            clipRect.heightProperty().bind(modalFrame.heightProperty());
            clipRect.setArcWidth(28.0);
            clipRect.setArcHeight(28.0);
            modalFrame.setClip(clipRect);

            StackPane cardShell = new StackPane(modalFrame);
            cardShell.getStyleClass().add("sk-dlg-card");

            StackPane rootPane = new StackPane(cardShell);
            rootPane.setPadding(new Insets(16.0));
            rootPane.setStyle("-fx-background-color: transparent;");

            return new FrameParts(rootPane, cardShell, headerArea, lblTitle, lblSubtitle,
                    errorBanner, lblError, bodyScroll, btnCancel, btnPrimary, handle);
        }

        public Optional<T> showAndWait() {
            AtomicReference<T> resultRef = new AtomicReference<>();
            Stage stage = buildStage(resultRef);

            Parent ownerRoot = (owner != null && owner.getScene() != null) ? owner.getScene().getRoot() : null;
            Effect priorEffect = ownerRoot != null ? ownerRoot.getEffect() : null;
            try {
                if (ownerRoot != null && priorEffect == null) {
                    ColorAdjust dim = new ColorAdjust(0, -0.04, -0.07, 0);
                    GaussianBlur blur = new GaussianBlur(4.0);
                    blur.setInput(dim);
                    ownerRoot.setEffect(blur);
                }
                stage.showAndWait();
            } finally {
                if (ownerRoot != null && priorEffect == null) {
                    ownerRoot.setEffect(null);
                }
            }

            return Optional.ofNullable(resultRef.get());
        }

        public Stage buildStage(AtomicReference<T> resultRef) {
            Stage stage = new Stage();
            stage.initStyle(StageStyle.TRANSPARENT);
            stage.setTitle(title);
            AppIconManager.applyToStage(stage);
            if (owner != null) {
                stage.initOwner(owner);
                stage.initModality(Modality.WINDOW_MODAL);
            } else {
                stage.initModality(Modality.APPLICATION_MODAL);
            }

            FrameParts parts = buildFrame(stage::close, resultRef != null ? resultRef::set : r -> {});

            Rectangle2D screenBounds = resolveScreenBounds(owner);
            double maxUsableW = Math.max(360.0, screenBounds.getWidth() - 40.0);
            double maxUsableH = Math.max(320.0, screenBounds.getHeight() - 40.0);

            double targetW = Math.min(size.maxWidth, Math.max(Math.min(size.minWidth, maxUsableW),
                    Math.min(size.prefWidth, maxUsableW)));
            parts.cardShell().setPrefWidth(targetW);
            parts.cardShell().setMinWidth(Math.min(size.minWidth, targetW));
            parts.cardShell().setMaxWidth(targetW);

            if (prefHeight != null) {
                double h = Math.min(prefHeight, maxUsableH);
                parts.cardShell().setPrefHeight(h);
                parts.cardShell().setMaxHeight(h);
            } else if (size == Size.SMALL) {
                parts.cardShell().setMaxHeight(Math.min(size.maxHeight, maxUsableH));
            } else {
                double h = Math.min(size.maxHeight, maxUsableH);
                parts.cardShell().setMaxHeight(h);
                if (size == Size.LARGE) {
                    parts.cardShell().setPrefHeight(h);
                }
            }

            // Draggable header bar
            final double[] drag = new double[2];
            parts.headerArea().setOnMousePressed(e -> {
                drag[0] = e.getSceneX();
                drag[1] = e.getSceneY();
            });
            parts.headerArea().setOnMouseDragged(e -> {
                stage.setX(e.getScreenX() - drag[0]);
                stage.setY(e.getScreenY() - drag[1]);
            });

            Scene scene = new Scene(parts.rootPane());
            scene.setFill(Color.TRANSPARENT);
            URL cssUrl = SmartKhataDialog.class.getResource("/com/smartkhata/ui/css/styles.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE && !parts.handle().isBusy()) {
                    stage.close();
                } else if (e.getCode() == KeyCode.ENTER && (e.isControlDown() || infoOnly)) {
                    parts.btnPrimary().fire();
                }
            });
            stage.setScene(scene);

            stage.setOnShown(ev -> {
                double x = screenBounds.getMinX() + (screenBounds.getWidth() - stage.getWidth()) / 2.0;
                double y = screenBounds.getMinY() + (screenBounds.getHeight() - stage.getHeight()) / 2.0;
                if (owner != null && owner.getWidth() > 0 && owner.getHeight() > 0) {
                    double ox = owner.getX() + (owner.getWidth() - stage.getWidth()) / 2.0;
                    double oy = owner.getY() + (owner.getHeight() - stage.getHeight()) / 2.0;
                    x = Math.max(screenBounds.getMinX(), Math.min(ox, screenBounds.getMaxX() - stage.getWidth()));
                    y = Math.max(screenBounds.getMinY(), Math.min(oy, screenBounds.getMaxY() - stage.getHeight()));
                }
                stage.setX(x);
                stage.setY(y);
                if (initialFocus != null) {
                    initialFocus.requestFocus();
                } else if (infoOnly) {
                    parts.btnPrimary().requestFocus();
                }
            });

            return stage;
        }
    }

    /**
     * Exception associating a validation failure with a specific input Node to highlight and focus.
     */
    public static final class ValidationFocusException extends RuntimeException {
        private final Node focusTarget;

        public ValidationFocusException(String message, Node focusTarget) {
            super(message);
            this.focusTarget = focusTarget;
        }

        public Node getFocusTarget() {
            return focusTarget;
        }
    }

    private SmartKhataDialog() {}

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Retrieves the attached DialogHandle from a dialog content root when needed by inner controls.
     */
    public static Optional<DialogHandle> findHandle(Node contentNode) {
        if (contentNode == null) return Optional.empty();
        Object prop = contentNode.getProperties().get("skDialogHandle");
        return prop instanceof DialogHandle h ? Optional.of(h) : Optional.empty();
    }

    private static Rectangle2D resolveScreenBounds(Window owner) {
        if (owner != null && owner.getWidth() > 0 && owner.getHeight() > 0) {
            List<Screen> screens = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
            if (!screens.isEmpty()) {
                return screens.get(0).getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static ImageView loadBasketArt() {
        try (InputStream is = SmartKhataDialog.class.getResourceAsStream("/com/smartkhata/ui/images/grocery/hero_grocery_basket_2x.png")) {
            if (is == null) return null;
            ImageView iv = new ImageView(new Image(is));
            iv.setFitHeight(60.0);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setOpacity(0.85);
            iv.setMouseTransparent(true);
            return iv;
        } catch (Exception e) {
            return null;
        }
    }
}

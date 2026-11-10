package com.smartkhata.ui.dialog;

import com.smartkhata.model.vo.Money;
import javafx.beans.property.SimpleStringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Reusable UI building blocks implementing the SmartKhata Global Dialog Design System.
 * Ensures consistent typography, #008A32/#F7F9FB/#DCE5E9 palette, 8px spacing rhythm,
 * accessible focus states, red invalid borders, and vector SVG iconography across all dialogs.
 */
public final class DialogComponents {

    private static final PseudoClass PSEUDO_FOCUSED_WITHIN = PseudoClass.getPseudoClass("focused-within");
    private static final PseudoClass PSEUDO_ERROR = PseudoClass.getPseudoClass("error");

    // Clean vector SVG paths (Material / Lucide outline geometry, zero Unicode emojis)
    public static final String SVG_CUBE = "M12 2L3 7v10l9 5 9-5V7l-9-5zm0 2.3L18.4 8 12 11.7 5.6 8 12 4.3zM5 9.8l6 3.5v6.4l-6-3.3V9.8zm8 9.9v-6.4l6-3.5v6.6l-6 3.3z";
    public static final String SVG_TAG = "M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z";
    public static final String SVG_SHOP = "M20 4H4v2h16V4zm1 10v-2l-1-5H4l-1 5v2h1v6h10v-6h4v6h2v-6h1zm-9 4H6v-4h6v4z";
    public static final String SVG_USER = "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z";
    public static final String SVG_USERS = "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z";
    public static final String SVG_PHONE = "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z";
    public static final String SVG_PIN = "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z";
    public static final String SVG_COINS = "M12 3C7.58 3 4 4.79 4 7v10c0 2.21 3.58 4 8 4s8-1.79 8-4V7c0-2.21-3.58-4-8-4zm0 2c3.87 0 6 1.5 6 2s-2.13 2-6 2-6-1.5-6-2 2.13-2 6-2zm6 12c0 .5-2.13 2-6 2s-6-1.5-6-2v-2.23c1.61.78 3.72 1.23 6 1.23s4.39-.45 6-1.23V17zm0-5c0 .5-2.13 2-6 2s-6-1.5-6-2V9.77C7.61 10.55 9.72 11 12 11s4.39-.45 6-1.23V12z";
    public static final String SVG_WALLET = "M21 18v1c0 1.1-.9 2-2 2H5c-1.11 0-2-.9-2-2V5c0-1.1.89-2 2-2h14c1.1 0 2 .9 2 2v1h-9c-1.11 0-2 .9-2 2v8c0 1.1.89 2 2 2h9zm-9-2h10V8H12v8zm4-2.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z";
    public static final String SVG_NOTE = "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z";
    public static final String SVG_RECEIPT = "M19.5 3.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2v14H3v3c0 1.66 1.34 3 3 3h12c1.66 0 3-1.34 3-3V2l-1.5 1.5zM15 20H6c-.55 0-1-.45-1-1v-1h10v2zm4-1c0 .55-.45 1-1 1s-1-.45-1-1v-3H8V5h11v14z";
    public static final String SVG_CART = "M7 18c-1.1 0-1.99.9-1.99 2S5.9 22 7 22s2-.9 2-2-.9-2-2-2zM1 2v2h2l3.6 7.59-1.35 2.45c-.16.28-.25.61-.25.96 0 1.1.9 2 2 2h12v-2H7.42c-.14 0-.25-.11-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49c.08-.14.12-.31.12-.48 0-.55-.45-1-1-1H5.21l-.94-2H1zm16 16c-1.1 0-1.99.9-1.99 2s.89 2 1.99 2 2-.9 2-2-.9-2-2-2z";
    public static final String SVG_TRUCK = "M20 8h-3V4H3c-1.1 0-2 .9-2 2v11h2c0 1.66 1.34 3 3 3s3-1.34 3-3h6c0 1.66 1.34 3 3 3s3-1.34 3-3h2v-5l-3-4zM6 18.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm13.5-9l1.96 2.5H17V9.5h2.5zm-1.5 9c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z";
    public static final String SVG_CLIPBOARD = "M19 3h-4.18C14.4 1.84 13.3 1 12 1s-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm2 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z";
    public static final String SVG_CALENDAR = "M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11z";
    public static final String SVG_LOCK = "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z";
    public static final String SVG_SETTINGS = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";
    public static final String SVG_CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    public static final String SVG_CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
    public static final String SVG_INFO = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z";
    public static final String SVG_WARNING = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";
    public static final String SVG_TRASH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    public static final String SVG_ARCHIVE = "M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z";
    public static final String SVG_EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";
    public static final String SVG_PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
    public static final String SVG_RETURN = "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z";
    public static final String SVG_PRINT = "M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z";
    public static final String SVG_SEARCH = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z";

    public enum Tone {
        PRIMARY,
        SOFT_GREEN,
        SUCCESS,
        WARNING,
        DANGER,
        NEUTRAL
    }

    private DialogComponents() {}

    public static StackPane iconBox(String pathData, String colorHex, double sizePx) {
        SVGPath svg = new SVGPath();
        svg.setContent(pathData);
        svg.setFill(Color.web(colorHex));
        double scale = sizePx / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        StackPane box = new StackPane(new Group(svg));
        box.setPrefSize(sizePx, sizePx);
        box.setMinSize(sizePx, sizePx);
        box.setMaxSize(sizePx, sizePx);
        box.setMouseTransparent(true);
        return box;
    }

    public static StackPane headerBadge(String svgPath, Tone tone) {
        String iconColor = switch (tone) {
            case PRIMARY -> "#FFFFFF";
            case DANGER -> "#DC3545";
            case WARNING -> "#D97706";
            case SUCCESS, SOFT_GREEN -> "#008A32";
            case NEUTRAL -> "#68778A";
        };
        StackPane badge = new StackPane(iconBox(svgPath, iconColor, 22.0));
        String styleClass = switch (tone) {
            case PRIMARY -> "sk-dlg-header-badge-primary";
            case DANGER -> "sk-dlg-header-badge-danger";
            case WARNING -> "sk-dlg-header-badge-warning";
            default -> "sk-dlg-header-badge";
        };
        badge.getStyleClass().add(styleClass);
        return badge;
    }

    public static VBox sectionCard(Node... children) {
        VBox box = new VBox(12.0, children);
        box.getStyleClass().add("sk-dlg-section");
        return box;
    }

    public static VBox tintedSectionCard(Node... children) {
        VBox box = new VBox(10.0, children);
        box.getStyleClass().add("sk-dlg-section-tint");
        return box;
    }

    public static HBox sectionHeader(String svgPath, String titleText, String descText) {
        StackPane iconWrap = new StackPane(iconBox(svgPath, "#008A32", 15.0));
        iconWrap.setStyle("-fx-background-color: #E8F8EA; -fx-background-radius: 7;");
        iconWrap.setPrefSize(28, 28);
        iconWrap.setMinSize(28, 28);

        Label title = new Label(titleText);
        title.getStyleClass().add("sk-dlg-section-title");
        VBox text = new VBox(1.0, title);
        if (descText != null && !descText.isBlank()) {
            Label desc = new Label(descText);
            desc.getStyleClass().add("sk-dlg-section-desc");
            desc.setWrapText(true);
            text.getChildren().add(desc);
        }
        HBox row = new HBox(10.0, iconWrap, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public static VBox formField(String labelText, boolean required, String helperText, Node control) {
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("sk-dlg-field-label");
        HBox labelRow = new HBox(4.0, lbl);
        labelRow.setAlignment(Pos.CENTER_LEFT);
        if (required) {
            Label star = new Label("*");
            star.setStyle("-fx-text-fill: #DC3545; -fx-font-weight: 700; -fx-font-size: 13px;");
            labelRow.getChildren().add(star);
        }

        VBox box = new VBox(5.0, labelRow);
        if (helperText != null && !helperText.isBlank()) {
            Label helper = new Label(helperText);
            helper.getStyleClass().add("sk-dlg-helper-text");
            helper.setWrapText(true);
            box.getChildren().add(helper);
        }
        box.getChildren().add(control);
        return box;
    }

    public static HBox textFieldWrap(String svgPath, TextField field) {
        field.getStyleClass().add("sk-dlg-input");
        HBox wrapper = new HBox(8.0);
        if (svgPath != null) {
            wrapper.getChildren().add(iconBox(svgPath, "#68778A", 14.0));
        }
        wrapper.getChildren().add(field);
        return configureInputWrapper(wrapper, field);
    }

    public static HBox currencyFieldWrap(TextField field) {
        field.getStyleClass().add("sk-dlg-input");
        Label bdt = new Label("BDT");
        bdt.setStyle("-fx-background-color: #F7F9FB; -fx-border-color: #DCE5E9; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 2 6; -fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #68778A;");
        HBox wrapper = new HBox(8.0, iconBox(SVG_COINS, "#68778A", 14.0), field, bdt);
        return configureInputWrapper(wrapper, field);
    }

    public static HBox unitFieldWrap(String svgPath, TextField field, String unitTagText) {
        field.getStyleClass().add("sk-dlg-input");
        Label tag = new Label(unitTagText);
        tag.setStyle("-fx-background-color: #F1F5F8; -fx-border-color: #DCE5E9; -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 2 7; -fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #68778A;");
        HBox wrapper = new HBox(8.0);
        if (svgPath != null) {
            wrapper.getChildren().add(iconBox(svgPath, "#68778A", 14.0));
        }
        wrapper.getChildren().addAll(field, tag);
        return configureInputWrapper(wrapper, field);
    }

    public static HBox textAreaWrap(TextArea area, double prefHeight) {
        area.setWrapText(true);
        area.setStyle("-fx-background-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-font-size: 13.5px; -fx-text-fill: #18283B;");
        HBox wrapper = new HBox(8.0, area);
        HBox.setHgrow(area, Priority.ALWAYS);
        wrapper.getStyleClass().add("sk-dlg-textarea-wrap");
        wrapper.setPrefHeight(prefHeight);
        wrapper.setMinHeight(prefHeight);
        area.focusedProperty().addListener((o, a, hasFocus) ->
                wrapper.pseudoClassStateChanged(PSEUDO_FOCUSED_WITHIN, hasFocus));
        return wrapper;
    }

    public static <T> ComboBox<T> styleCombo(ComboBox<T> combo) {
        combo.getStyleClass().add("sk-dlg-combo");
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    public static DatePicker styleDatePicker(DatePicker picker) {
        picker.getStyleClass().add("sk-dlg-date-picker");
        picker.setMaxWidth(Double.MAX_VALUE);
        return picker;
    }

    private static HBox configureInputWrapper(HBox wrapper, TextField field) {
        HBox.setHgrow(field, Priority.ALWAYS);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.getStyleClass().add("sk-dlg-input-wrap");
        if (!field.isEditable() || field.isDisabled()) {
            wrapper.getStyleClass().add("sk-dlg-input-wrap-disabled");
        }
        field.focusedProperty().addListener((o, a, hasFocus) -> {
            wrapper.pseudoClassStateChanged(PSEUDO_FOCUSED_WITHIN, hasFocus);
            if (hasFocus) {
                wrapper.pseudoClassStateChanged(PSEUDO_ERROR, false);
            }
        });
        field.disabledProperty().addListener((o, a, dis) -> {
            if (Boolean.TRUE.equals(dis)) {
                if (!wrapper.getStyleClass().contains("sk-dlg-input-wrap-disabled")) {
                    wrapper.getStyleClass().add("sk-dlg-input-wrap-disabled");
                }
            } else {
                wrapper.getStyleClass().remove("sk-dlg-input-wrap-disabled");
            }
        });
        field.getProperties().put("skWrapper", wrapper);
        return wrapper;
    }

    public static void markFieldError(Node node, boolean hasError) {
        if (node == null) return;
        Object prop = node.getProperties().get("skWrapper");
        Node target = prop instanceof Node w ? w : node;
        target.pseudoClassStateChanged(PSEUDO_ERROR, hasError);
    }

    public static GridPane twoColGrid(double hgap, double vgap) {
        GridPane grid = new GridPane();
        grid.setHgap(hgap);
        grid.setVgap(vgap);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50.0);
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50.0);
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(c1, c2);
        return grid;
    }

    /**
     * Builds a responsive 2-column grid whose nodes collapse cleanly into 1 column when narrower than collapseWidth.
     */
    public static GridPane responsiveTwoColGrid(double collapseWidth, Node... pairedNodes) {
        GridPane grid = twoColGrid(14.0, 12.0);
        ColumnConstraints full = new ColumnConstraints();
        full.setPercentWidth(100.0);
        full.setHgrow(Priority.ALWAYS);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50.0);
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50.0);
        c2.setHgrow(Priority.ALWAYS);

        Runnable layoutTwoCol = () -> {
            grid.getChildren().clear();
            grid.getColumnConstraints().setAll(c1, c2);
            for (int i = 0; i < pairedNodes.length; i++) {
                grid.add(pairedNodes[i], i % 2, i / 2);
            }
        };
        Runnable layoutOneCol = () -> {
            grid.getChildren().clear();
            grid.getColumnConstraints().setAll(full);
            for (int i = 0; i < pairedNodes.length; i++) {
                grid.add(pairedNodes[i], 0, i);
            }
        };
        layoutTwoCol.run();

        AtomicBoolean stacked = new AtomicBoolean(false);
        grid.widthProperty().addListener((o, oldW, newW) -> {
            boolean shouldStack = newW != null && newW.doubleValue() > 80.0 && newW.doubleValue() < collapseWidth;
            if (shouldStack != stacked.getAndSet(shouldStack)) {
                if (shouldStack) layoutOneCol.run();
                else layoutTwoCol.run();
            }
        });
        return grid;
    }

    public static Label statusBadge(String text, Tone tone) {
        Label badge = new Label(text);
        String cls = switch (tone) {
            case SUCCESS, PRIMARY, SOFT_GREEN -> "sk-dlg-badge-success";
            case WARNING -> "sk-dlg-badge-warning";
            case DANGER -> "sk-dlg-badge-danger";
            case NEUTRAL -> "sk-dlg-badge-neutral";
        };
        badge.getStyleClass().add(cls);
        return badge;
    }

    public static VBox emptyState(String svgPath, String title, String description) {
        StackPane circle = new StackPane(iconBox(svgPath, "#008A32", 24.0));
        circle.setStyle("-fx-background-color: #E8F8EA; -fx-border-color: #BCE4C6; -fx-border-radius: 26; -fx-background-radius: 26;");
        circle.setPrefSize(52, 52);
        circle.setMaxSize(52, 52);
        Label t = new Label(title);
        t.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #18283B;");
        Label d = new Label(description);
        d.setStyle("-fx-font-size: 12px; -fx-text-fill: #68778A;");
        d.setWrapText(true);
        VBox box = new VBox(6.0, circle, t, d);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(22));
        return box;
    }

    public static <T> TableView<T> styleTable(TableView<T> table) {
        table.getStyleClass().add("sk-dlg-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    public static <S> TableColumn<S, String> rightAlignedColumn(String headerText, Function<S, String> extractor) {
        TableColumn<S, String> col = new TableColumn<>(headerText);
        col.setCellValueFactory(c -> new SimpleStringProperty(extractor.apply(c.getValue())));
        col.setCellFactory(tc -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER_RIGHT);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        return col;
    }

    public static Money parseNonNegativeMoney(String input, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            return Money.ZERO;
        }
        String normalized = input.trim().replaceAll("[, ]", "");
        BigDecimal bd;
        try {
            bd = new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldLabel + " must be a valid amount in BDT.");
        }
        if (bd.signum() < 0) {
            throw new IllegalArgumentException(fieldLabel + " cannot be negative.");
        }
        return Money.ofBdt(bd);
    }

    public static Money parsePositiveMoney(String input, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            throw new IllegalArgumentException(fieldLabel + " is required.");
        }
        Money m = parseNonNegativeMoney(input, fieldLabel);
        if (!m.isPositive()) {
            throw new IllegalArgumentException(fieldLabel + " must be greater than zero.");
        }
        return m;
    }

    public static int parseIntRange(String input, int defaultVal, int minVal, String fieldLabel) {
        if (input == null || input.trim().isBlank()) {
            if (defaultVal < minVal) {
                throw new IllegalArgumentException(fieldLabel + " must be at least " + minVal + ".");
            }
            return defaultVal;
        }
        try {
            int val = Integer.parseInt(input.trim().replaceAll("[, ]", ""));
            if (val < minVal) {
                throw new IllegalArgumentException(fieldLabel + " must be at least " + minVal + ".");
            }
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldLabel + " must be a valid whole number.");
        }
    }

    public static String trimToNull(String val) {
        if (val == null) return null;
        String t = val.trim();
        return t.isEmpty() ? null : t;
    }

    public static String nonNull(String val) {
        return val != null ? val : "";
    }
}

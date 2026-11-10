package com.smartkhata.ui;

import com.smartkhata.model.dto.CustomerCreateDto;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.vo.Money;
import com.smartkhata.ui.dialog.DialogComponents;
import com.smartkhata.ui.dialog.SmartKhataDialog;
import com.smartkhata.ui.icon.AppIconManager;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalDialogSystemTest {

    private static final PseudoClass PSEUDO_ERROR = PseudoClass.getPseudoClass("error");

    @Test
    void shouldEnforceGlobalDialogTonesSizesAndInlineValidationFocus() throws Exception {
        runOnFxThread(() -> {
            TextField txtRequired = new TextField();
            HBox wrappedField = DialogComponents.textFieldWrap(DialogComponents.SVG_USER, txtRequired);
            VBox body = DialogComponents.sectionCard(
                    DialogComponents.sectionHeader(DialogComponents.SVG_USER, "Test Section", "Unified section styling"),
                    DialogComponents.formField("Required Name", true, null, wrappedField)
            );

            AtomicReference<String> result = new AtomicReference<>();
            AtomicBoolean closed = new AtomicBoolean(false);

            SmartKhataDialog.FrameParts smallDanger = SmartKhataDialog.<String>builder()
                    .size(SmartKhataDialog.Size.SMALL)
                    .title("Delete Confirmation")
                    .subtitle("Verify semantic danger tone")
                    .icon(DialogComponents.SVG_TRASH, DialogComponents.Tone.DANGER)
                    .greenHeaderAccent(false)
                    .content(body)
                    .primaryAction("Delete", DialogComponents.SVG_TRASH, SmartKhataDialog.ActionTone.DANGER)
                    .onSubmit(() -> {
                        String val = DialogComponents.trimToNull(txtRequired.getText());
                        if (val == null) {
                            throw new SmartKhataDialog.ValidationFocusException(
                                    "Required Name cannot be blank.", txtRequired);
                        }
                        return val;
                    })
                    .buildFrame(() -> closed.set(true), result::set);

            // Structural assertions for Size.SMALL + ActionTone.DANGER
            assertThat(smallDanger.lblTitle().getStyleClass()).contains("sk-dlg-title-sm");
            assertThat(smallDanger.headerArea().getStyleClass()).contains("sk-dlg-header-neutral");
            assertThat(smallDanger.btnPrimary().getStyleClass()).contains("sk-dlg-btn-danger");
            assertThat(smallDanger.btnCancel().isVisible()).isTrue();
            assertThat(smallDanger.errorBanner().isVisible()).isFalse();

            // Invalid submit shows inline error banner + marks field wrapper with :error without closing
            smallDanger.btnPrimary().fire();
            assertThat(smallDanger.errorBanner().isVisible()).isTrue();
            assertThat(smallDanger.lblError().getText()).isEqualTo("Required Name cannot be blank.");
            assertThat(wrappedField.getPseudoClassStates()).contains(PSEUDO_ERROR);
            assertThat(closed.get()).isFalse();
            assertThat(result.get()).isNull();

            // Valid correction clears error, returns value, and invokes close
            txtRequired.setText("  Rahim Store  ");
            smallDanger.btnPrimary().fire();
            assertThat(result.get()).isEqualTo("Rahim Store");
            assertThat(closed.get()).isTrue();

            // Verify LARGE + WARNING + infoOnly variations
            SmartKhataDialog.FrameParts largeInfo = SmartKhataDialog.<String>builder()
                    .size(SmartKhataDialog.Size.LARGE)
                    .title("System Warning")
                    .subtitle("Informational modal hides cancel button")
                    .icon(DialogComponents.SVG_WARNING, DialogComponents.Tone.WARNING)
                    .content(new Label("Info body"))
                    .infoOnly(true)
                    .primaryAction("Understood", DialogComponents.SVG_CHECK, SmartKhataDialog.ActionTone.WARNING)
                    .buildFrame(() -> {}, r -> {});

            assertThat(largeInfo.lblTitle().getStyleClass()).contains("sk-dlg-title");
            assertThat(largeInfo.btnPrimary().getStyleClass()).contains("sk-dlg-btn-warning");
            assertThat(largeInfo.btnCancel().getParent()).isNull();
        });
    }

    @Test
    void shouldCollapseResponsiveTwoColumnGridAndStyleCurrencyTablesCorrectly() throws Exception {
        runOnFxThread(() -> {
            Label f1 = new Label("Field 1");
            Label f2 = new Label("Field 2");
            Label f3 = new Label("Field 3");
            Label f4 = new Label("Field 4");

            GridPane grid = DialogComponents.responsiveTwoColGrid(500.0, f1, f2, f3, f4);
            StackPane host = new StackPane(grid);
            Scene scene = new Scene(host, 640, 240);
            host.applyCss();
            host.layout();

            assertThat(grid.getColumnConstraints()).hasSize(2);
            assertThat(GridPane.getColumnIndex(f2)).isEqualTo(1);
            assertThat(GridPane.getRowIndex(f2)).isEqualTo(0);

            // Shrink width below threshold -> single column stack
            host.resize(400, 240);
            host.applyCss();
            host.layout();

            assertThat(grid.getColumnConstraints()).hasSize(1);
            assertThat(GridPane.getColumnIndex(f2)).isEqualTo(0);
            assertThat(GridPane.getRowIndex(f2)).isEqualTo(1);
            assertThat(GridPane.getRowIndex(f4)).isEqualTo(3);

            // Table styling & right-aligned currency column
            TableView<String> table = DialogComponents.styleTable(new TableView<>());
            TableColumn<String, String> moneyCol = DialogComponents.rightAlignedColumn("Amount (BDT)", s -> s);
            table.getColumns().add(moneyCol);
            assertThat(table.getStyleClass()).contains("sk-dlg-table");
            @SuppressWarnings("unchecked")
            TableCell<String, String> cell = (TableCell<String, String>) moneyCol.getCellFactory().call(moneyCol);
            assertThat(cell.getAlignment()).isEqualTo(Pos.CENTER_RIGHT);

            // Strict parsers
            assertThat(DialogComponents.parseNonNegativeMoney("12,500.50", "Amount")).isEqualTo(Money.ofBdt(12500.50));
            assertThat(DialogComponents.parseNonNegativeMoney(" ", "Amount")).isEqualTo(Money.ZERO);
            assertThat(DialogComponents.parsePositiveMoney("100", "Rate")).isEqualTo(Money.ofBdt(100));
            assertThat(DialogComponents.parseIntRange("", 5, 1, "Threshold")).isEqualTo(5);
            assertThat(DialogComponents.parseIntRange("12", 5, 1, "Threshold")).isEqualTo(12);

            assertThatThrownBy(() -> DialogComponents.parseNonNegativeMoney("-5", "Opening"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");
            assertThatThrownBy(() -> DialogComponents.parsePositiveMoney("0", "Price"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("greater than zero");
            assertThatThrownBy(() -> DialogComponents.parseIntRange("0", 1, 1, "Qty"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1");
        });
    }

    @Test
    void shouldSupportCustomerFormModalAddValidationAndLockedOpeningDueOnEdit() throws Exception {
        AtomicReference<CustomerCreateDto> capturedCreate = new AtomicReference<>();
        AtomicReference<SmartKhataDialog.FrameParts> addFrameRef = new AtomicReference<>();
        AtomicBoolean addClosed = new AtomicBoolean(false);
        CountDownLatch errorShownLatch = new CountDownLatch(1);
        CountDownLatch submittedLatch = new CountDownLatch(1);

        // 1. Build Add Customer modal on FX thread
        runOnFxThread(() -> {
            Customer dummySaved = new Customer(
                    10L, 1L, "Karim Enterprise", "01711223344", "Mirpur-10, Dhaka",
                    Money.ofBdt(50000), Money.ofBdt(12000), false, "VIP", null, null
            );

            SmartKhataDialog.Builder<Customer> builder = CustomerFormModal.buildCreateDialog(
                    null,
                    1L,
                    "Add New Customer (নতুন গ্রাহক)",
                    "Register a customer",
                    "Save Customer",
                    true,
                    "",
                    dto -> {
                        capturedCreate.set(dto);
                        return dummySaved;
                    }
            );

            SmartKhataDialog.FrameParts frame = builder.buildFrame(() -> addClosed.set(true), saved -> submittedLatch.countDown());
            frame.errorBanner().visibleProperty().addListener((o, wasVis, isVis) -> {
                if (Boolean.TRUE.equals(isVis)) {
                    errorShownLatch.countDown();
                }
            });
            addFrameRef.set(frame);

            // Blank name submit -> triggers async ValidationFocusException surfaced back onto FX thread
            frame.btnPrimary().fire();
        });

        assertThat(errorShownLatch.await(5, TimeUnit.SECONDS)).isTrue();
        runOnFxThread(() -> {
            SmartKhataDialog.FrameParts frame = addFrameRef.get();
            assertThat(frame.lblError().getText()).contains("Customer Name is required");
            assertThat(addClosed.get()).isFalse();

            // Fill form fields cleanly via lookup through body section text controls
            List<TextField> fields = collectNodes(frame.bodyScroll().getContent(), TextField.class);
            assertThat(fields).hasSize(5); // Name, Phone, Address, Credit Limit, Opening Due
            fields.get(0).setText("Karim Enterprise");
            fields.get(1).setText("01711223344");
            fields.get(2).setText("Mirpur-10, Dhaka");
            fields.get(3).setText("50,000");
            fields.get(4).setText("12000");

            frame.btnPrimary().fire();
        });

        assertThat(submittedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedCreate.get()).isNotNull();
        assertThat(capturedCreate.get().name()).isEqualTo("Karim Enterprise");
        assertThat(capturedCreate.get().creditLimit()).isEqualTo(Money.ofBdt(50000));
        assertThat(capturedCreate.get().openingBalance()).isEqualTo(Money.ofBdt(12000));

        // 2. Edit Customer modal locks Opening Due & pre-populates existing values
        runOnFxThread(() -> {
            Customer existing = new Customer(
                    22L, 1L, "Bismillah Store", "01855667788", "Motijheel, Dhaka",
                    Money.ofBdt(25000), Money.ofBdt(4500), false, "Weekly credit", null, null
            );

            SmartKhataDialog.FrameParts editFrame = CustomerFormModal.buildEditDialog(null, existing, dto -> existing)
                    .buildFrame(() -> {}, c -> {});

            List<TextField> fields = collectNodes(editFrame.bodyScroll().getContent(), TextField.class);
            assertThat(fields.get(0).getText()).isEqualTo("Bismillah Store");
            assertThat(fields.get(1).getText()).isEqualTo("01855667788");
            assertThat(fields.get(2).getText()).isEqualTo("Motijheel, Dhaka");
            assertThat(fields.get(4).isEditable()).isFalse();
            assertThat(editFrame.btnPrimary().getText()).isEqualTo("Update Customer");

            // 3. Dedicated Product & Supplier modal stages carry all 7 official SmartKhata icons
            assertThat(ProductFormModal.createModalStage(null, "Add New Product").getIcons())
                    .hasSize(AppIconManager.ICON_SIZES.length);
            assertThat(SupplierFormModal.createModalStage(null, "Add New Supplier").getIcons())
                    .hasSize(AppIconManager.ICON_SIZES.length);
        });
    }

    private static <N extends Node> List<N> collectNodes(Node root, Class<N> type) {
        java.util.ArrayList<N> found = new java.util.ArrayList<>();
        collectRecursive(root, type, found);
        return found;
    }

    private static <N extends Node> void collectRecursive(Node node, Class<N> type, List<N> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectRecursive(child, type, out);
            }
        }
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


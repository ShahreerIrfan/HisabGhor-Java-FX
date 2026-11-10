package com.smartkhata.ui;

import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.vo.Money;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierFormModalTest {

    @Test
    void shouldRenderAddSupplierModalWithArtworkStylesAndInlineValidation() throws Exception {
        runOnFxThread(() -> {
            AtomicReference<SupplierFormModal.FormValues> submitted = new AtomicReference<>();
            AtomicBoolean closed = new AtomicBoolean(false);

            SupplierFormModal.ModalParts parts = SupplierFormModal.buildModalParts(
                    null,
                    "Add New Supplier (নতুন সরবরাহকারী)",
                    "Register a vendor with contact details and verified opening payable balance.",
                    "Save Supplier",
                    () -> closed.set(true),
                    submitted::set
            );

            Scene scene = SupplierFormModal.buildStyledScene(parts.rootPane(), parts.btnSave(), () -> closed.set(true));

            assertThat(scene.getStylesheets()).isNotEmpty();
            assertThat(parts.bagArtwork()).isNotNull();
            assertThat(parts.bagArtwork().getImage()).isNotNull();
            assertThat(parts.bagArtwork().getImage().isError()).isFalse();
            assertThat(parts.txtOpening().isEditable()).isTrue();
            assertThat(parts.txtName().getPromptText()).contains("Meghna Oil Mills");

            // 1. Blank name triggers inline error banner cleanly without submitting
            parts.btnSave().fire();
            assertThat(parts.errorBanner().isVisible()).isTrue();
            assertThat(parts.lblError().getText()).contains("Supplier Name is required");
            assertThat(submitted.get()).isNull();

            // 2. Negative opening payable triggers inline error
            parts.txtName().setText("Meghna Oil Mills");
            parts.txtOpening().setText("-500");
            parts.btnSave().fire();
            assertThat(parts.errorBanner().isVisible()).isTrue();
            assertThat(parts.lblError().getText()).contains("cannot be negative");
            assertThat(submitted.get()).isNull();

            // 3. Valid inputs invoke submit callback cleanly
            parts.txtContact().setText("Md. Shahidul Islam");
            parts.txtPhone().setText("01811223344");
            parts.txtAddress().setText("Postogola, Dhaka");
            parts.txtOpening().setText("25,000");
            parts.txtNotes().setText("Net 15 days");
            parts.btnSave().fire();

            assertThat(submitted.get()).isNotNull();
            assertThat(submitted.get().name()).isEqualTo("Meghna Oil Mills");
            assertThat(submitted.get().contactPerson()).isEqualTo("Md. Shahidul Islam");
            assertThat(SupplierFormModal.parseNonNegativeMoney(submitted.get().openingPayable()))
                    .isEqualTo(Money.ofBdt(25000));

            parts.btnCancel().fire();
            assertThat(closed.get()).isTrue();
        });
    }

    @Test
    void shouldRenderEditSupplierModalWithLockedOpeningPayable() throws Exception {
        runOnFxThread(() -> {
            Supplier existing = new Supplier(
                    7L, 1L, "Square Traders", "Kabir Hossain", "01799887766",
                    "Mohakhali, Dhaka", Money.ofBdt(12500), false, "Monthly billing", null, null
            );

            SupplierFormModal.ModalParts parts = SupplierFormModal.buildModalParts(
                    existing,
                    "Edit Supplier (সরবরাহকারী সম্পাদনা)",
                    "Update vendor details",
                    "Update Supplier",
                    () -> {},
                    values -> {}
            );

            assertThat(parts.txtName().getText()).isEqualTo("Square Traders");
            assertThat(parts.txtContact().getText()).isEqualTo("Kabir Hossain");
            assertThat(parts.txtPhone().getText()).isEqualTo("01799887766");
            assertThat(parts.txtAddress().getText()).isEqualTo("Mohakhali, Dhaka");
            assertThat(parts.txtNotes().getText()).isEqualTo("Monthly billing");
            assertThat(parts.txtOpening().isEditable()).isFalse();
            assertThat(parts.btnSave().getText()).isEqualTo("Update Supplier");
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


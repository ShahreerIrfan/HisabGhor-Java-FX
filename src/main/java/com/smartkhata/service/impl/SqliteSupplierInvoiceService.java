package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.GrnService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierInvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SqliteSupplierInvoiceService implements SupplierInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(SqliteSupplierInvoiceService.class);
    private static final DateTimeFormatter DATE_PREFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SupplierInvoiceDao invoiceDao;
    private final GrnDao grnDao;
    private final PurchaseOrderDao poDao;
    private final SupplierDao supplierDao;
    private final SupplierLedgerDao supplierLedgerDao;
    private final PaymentDao paymentDao;
    private final ProductDao productDao;
    private final SettingsDao settingsDao;
    private final GrnService grnService;

    public SqliteSupplierInvoiceService() {
        this(new SqliteSupplierInvoiceDao(), new SqliteGrnDao(), new SqlitePurchaseOrderDao(),
                new SqliteSupplierDao(), new SqliteSupplierLedgerDao(), new SqlitePaymentDao(),
                new SqliteProductDao(), new SqliteSettingsDao(), new SqliteGrnService());
    }

    public SqliteSupplierInvoiceService(SupplierInvoiceDao invoiceDao, GrnDao grnDao, PurchaseOrderDao poDao,
                                        SupplierDao supplierDao, SupplierLedgerDao supplierLedgerDao,
                                        PaymentDao paymentDao, ProductDao productDao, SettingsDao settingsDao,
                                        GrnService grnService) {
        this.invoiceDao = invoiceDao;
        this.grnDao = grnDao;
        this.poDao = poDao;
        this.supplierDao = supplierDao;
        this.supplierLedgerDao = supplierLedgerDao;
        this.paymentDao = paymentDao;
        this.productDao = productDao;
        this.settingsDao = settingsDao;
        this.grnService = grnService;
    }

    @Override
    public SupplierInvoice postSupplierInvoice(SupplierInvoiceCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("At least one invoice line item is required.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Idempotency Guard
            if (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank()) {
                Optional<SupplierInvoice> existing = invoiceDao.findByIdempotencyKey(conn, dto.businessId(), dto.idempotencyKey());
                if (existing.isPresent()) return existing.get();
            }

            Supplier supplier = supplierDao.findById(conn, dto.supplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + dto.supplierId()));
            if (supplier.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied.");
            }
            if (dto.supplierBillNo() != null && !dto.supplierBillNo().isBlank()
                    && invoiceDao.hasActiveSupplierBillNo(conn, dto.businessId(), dto.supplierId(), dto.supplierBillNo())) {
                throw new IllegalArgumentException("Duplicate Vendor Bill Number '" + dto.supplierBillNo().trim()
                        + "' already posted for " + supplier.name());
            }

            long tolerancePaisha = Long.parseLong(
                    settingsDao.get(conn, dto.businessId(), "scm.invoice_tolerance_paisha").orElse("0"));

            // 2. Load GRN & PO for 3-Way Match Verification
            GoodsReceiptNote grn = null;
            Map<Long, GoodsReceiptItem> grnItemMap = new HashMap<>();
            if (dto.grnId() != null) {
                grn = grnDao.findById(conn, dto.grnId())
                        .orElseThrow(() -> new IllegalArgumentException("GRN not found: " + dto.grnId()));
                if (grn.businessId() != dto.businessId()) throw new SecurityException("Cross-business access denied.");
                if (grn.supplierId() != dto.supplierId()) throw new IllegalArgumentException("GRN supplier mismatch.");
                if (grn.status() != GrnStatus.POSTED) {
                    throw new IllegalStateException("Only POSTED GRNs can be invoiced.");
                }
                if (invoiceDao.isGrnActivelyInvoiced(conn, grn.id())) {
                    throw new IllegalStateException("GRN [" + grn.grnNumber() + "] is already linked to an active Supplier Invoice.");
                }
                for (GoodsReceiptItem gi : grnDao.findItemsByGrnId(conn, grn.id())) {
                    grnItemMap.put(gi.id(), gi);
                }
            }

            Long poId = dto.purchaseOrderId() != null ? dto.purchaseOrderId()
                    : (grn != null ? grn.purchaseOrderId() : null);

            // 3. Validate lines & 3-way qty/price match
            long subtotalPaisha = 0L;
            long totalVariancePaisha = 0L;
            List<SupplierInvoiceItem> itemsToSave = new ArrayList<>();

            for (SupplierInvoiceItemCreateDto line : dto.items()) {
                Product product = productDao.findById(conn, line.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Product not found: " + line.productId()));
                if (product.businessId() != dto.businessId()) throw new SecurityException("Cross-business access denied.");
                if (line.invoicedBaseQty() <= 0) {
                    throw new IllegalArgumentException("Invoiced qty must be positive for " + product.name());
                }
                Money unitPrice = line.unitPrice() != null ? line.unitPrice() : Money.ZERO;
                if (unitPrice.isNegative()) throw new IllegalArgumentException("Unit price cannot be negative.");
                Money lineTotal = unitPrice.multiply(line.invoicedBaseQty());
                subtotalPaisha = Math.addExact(subtotalPaisha, lineTotal.paisha());

                long lineVariancePaisha = 0L;
                Long poItemId = line.poItemId();
                if (line.grnItemId() != null && grnItemMap.containsKey(line.grnItemId())) {
                    GoodsReceiptItem gi = grnItemMap.get(line.grnItemId());
                    if (gi.productId() != product.id()) {
                        throw new IllegalArgumentException("Invoice line product mismatches GRN item for " + product.name());
                    }
                    if (line.invoicedBaseQty() > gi.acceptedBaseQty()) {
                        throw new IllegalArgumentException("3-Way Match Failed for " + product.name()
                                + ": invoiced qty (" + line.invoicedBaseQty() + ") exceeds GRN accepted base qty ("
                                + gi.acceptedBaseQty() + ").");
                    }
                    poItemId = poItemId != null ? poItemId : gi.poItemId();
                    lineVariancePaisha = lineTotal.paisha() - gi.lineTotal().paisha();
                    totalVariancePaisha += lineVariancePaisha;
                }

                itemsToSave.add(new SupplierInvoiceItem(
                        null, 0L, line.grnItemId(), poItemId, product.id(),
                        line.invoicedBaseQty(), unitPrice, Money.ofPaisha(lineVariancePaisha), lineTotal, line.notes()
                ));
            }

            Money subtotal = Money.ofPaisha(subtotalPaisha);
            Money disc = dto.headerDiscount() != null ? dto.headerDiscount() : Money.ZERO;
            Money tax = dto.tax() != null ? dto.tax() : Money.ZERO;
            Money charges = dto.additionalCharges() != null ? dto.additionalCharges() : Money.ZERO;
            if (disc.isNegative() || tax.isNegative() || charges.isNegative() || disc.compareTo(subtotal) > 0) {
                throw new IllegalArgumentException("Invalid invoice discount, tax, or additional charges.");
            }
            Money netAmount = subtotal.subtract(disc).add(tax).add(charges);

            String matchingStatus;
            if (grn == null) {
                matchingStatus = "DIRECT_BILL";
            } else {
                long netVariance = netAmount.paisha() - grn.totalCost().paisha();
                totalVariancePaisha = netVariance;
                if (Math.abs(netVariance) <= tolerancePaisha) {
                    matchingStatus = "MATCHED";
                } else if (dto.approveVariance()) {
                    SessionContext.requireOwner();
                    matchingStatus = "VARIANCE_APPROVED";
                } else {
                    throw new IllegalArgumentException(String.format(
                            "3-Way Match Variance (%s) between Invoice (%s) and GRN [%s] (%s) exceeds tolerance (%s). Approve variance as Owner to proceed.",
                            Money.ofPaisha(netVariance), netAmount, grn.grnNumber(), grn.totalCost(), Money.ofPaisha(tolerancePaisha)));
                }
            }

            Money paid = dto.initialPaymentAmount() != null ? dto.initialPaymentAmount() : Money.ZERO;
            if (paid.isNegative() || paid.compareTo(netAmount) > 0) {
                throw new IllegalArgumentException("Initial payment must be between 0 and Net Invoice Amount (" + netAmount + ").");
            }
            Money due = netAmount.subtract(paid);
            String paymentStatus = due.isZero() ? "PAID" : (paid.isPositive() ? "PARTIAL" : "DUE");

            LocalDateTime now = LocalDateTime.now();
            String datePrefix = now.format(DATE_PREFIX_FMT);
            int seq = invoiceDao.getNextInvoiceSequence(conn, dto.businessId(), datePrefix);
            String invoiceNumber = String.format("PINV-%s-%04d", datePrefix, seq);
            LocalDate dueDate = dto.dueDate() != null ? dto.dueDate()
                    : now.toLocalDate().plusDays(Math.max(0, supplier.creditPeriodDays()));
            long actingUser = dto.actingUserId() > 0 ? dto.actingUserId()
                    : (SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L);

            SupplierInvoice saved = invoiceDao.saveHeader(conn, new SupplierInvoice(
                    null, dto.businessId(), invoiceNumber, dto.supplierBillNo(), dto.supplierId(),
                    poId, dto.grnId(), now, dueDate, subtotal, disc, tax, charges,
                    netAmount, paid, due, paymentStatus, matchingStatus, Money.ofPaisha(totalVariancePaisha),
                    "POSTED", dto.notes(),
                    dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank() ? dto.idempotencyKey().trim() : null,
                    actingUser, null, null
            ));

            invoiceDao.saveItems(conn, saved.id(), itemsToSave.stream().map(i -> i.withInvoiceId(saved.id())).toList());
            if (grn != null) {
                invoiceDao.linkGrn(conn, grn.id(), saved.id(), netAmount);
            }

            // 4. Single-Source Accounts Payable Entry in supplier_ledger (NEVER duplicates!)
            Money currentPayable = supplierLedgerDao.getCurrentPayableBalance(conn, dto.supplierId());
            Money runningAfterBill = currentPayable.add(netAmount);
            supplierLedgerDao.save(conn, new SupplierLedgerEntry(
                    null, dto.businessId(), dto.supplierId(), now,
                    "PURCHASE_BILL", saved.id(), netAmount, Money.ZERO, runningAfterBill,
                    "Supplier Invoice " + invoiceNumber
                            + (dto.supplierBillNo() != null && !dto.supplierBillNo().isBlank() ? " (Bill " + dto.supplierBillNo() + ")" : "")
                            + (grn != null ? " [" + grn.grnNumber() + "]" : ""),
                    null
            ));

            // 5. Record upfront payment if provided
            if (paid.isPositive()) {
                int paySeq = paymentDao.getNextPaymentSequence(conn, dto.businessId(), datePrefix, "SUPPLIER");
                String payNo = String.format("PAY-S-%s-%04d", datePrefix, paySeq);
                Payment savedPay = paymentDao.save(conn, new Payment(
                        null, dto.businessId(), payNo, "SUPPLIER", dto.supplierId(),
                        paid, now, dto.paymentMethod() != null ? dto.paymentMethod() : "CASH",
                        dto.referenceTrxId(), "Payment for " + invoiceNumber,
                        actingUser, null, false, null, null, null
                ));
                paymentDao.saveAllocations(conn, List.of(
                        new PaymentAllocation(null, savedPay.id(), null, null, paid, now, saved.id())
                ));
                Money runningAfterPay = runningAfterBill.subtract(paid);
                supplierLedgerDao.save(conn, new SupplierLedgerEntry(
                        null, dto.businessId(), dto.supplierId(), now,
                        "PAYMENT_DISBURSEMENT", savedPay.id(), Money.ZERO, paid, runningAfterPay,
                        "Payment " + payNo + " for " + invoiceNumber, null
                ));
            }

            log.info("Supplier Invoice [{}] posted cleanly (Net: {}, Paid: {}, Due: {}, Match: {})",
                    invoiceNumber, netAmount, paid, due, matchingStatus);
            return saved;
        });
    }

    @Override
    public SupplierInvoice postGrnAndInvoice(GrnCreateDto grnDto, String supplierBillNo, LocalDate dueDate,
                                             Money tax, Money initialPayment, String paymentMethod, String refTrxId) {
        SessionContext.requireAuthenticated();
        GoodsReceiptNote grn = grnService.postGoodsReceipt(grnDto);
        GrnDetailDto detail = getGrnDetailSafe(grn.id());
        List<SupplierInvoiceItemCreateDto> invLines = new ArrayList<>();
        for (GrnItemViewDto item : detail.items()) {
            if (item.acceptedBaseQty() > 0) {
                invLines.add(new SupplierInvoiceItemCreateDto(
                        item.id(), item.poItemId(), item.productId(),
                        item.acceptedBaseQty(), item.effectiveBaseUnitCost(), item.batchOrRemark()
                ));
            }
        }
        return postSupplierInvoice(new SupplierInvoiceCreateDto(
                grn.businessId(), grn.supplierId(), grn.purchaseOrderId(), grn.id(),
                supplierBillNo, dueDate, Money.ZERO, tax != null ? tax : Money.ZERO, Money.ZERO,
                initialPayment != null ? initialPayment : Money.ZERO, paymentMethod, refTrxId,
                true, "Combined GRN + Invoice for " + grn.grnNumber(),
                null, grn.postedByUserId(), invLines
        ));
    }

    private GrnDetailDto getGrnDetailSafe(long grnId) {
        return grnService.getGrnDetail(grnId).orElseThrow();
    }

    @Override
    public void voidSupplierInvoice(long invoiceId, String reason, long actingUserId) {
        SessionContext.requireOwner();
        long bizId = SessionContext.getRequiredBusinessId();
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("Reason is required to void a Supplier Invoice.");
        }
        TransactionManager.runInTransaction(conn -> {
            SupplierInvoice inv = invoiceDao.findById(conn, invoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("Supplier Invoice not found: " + invoiceId));
            if (inv.businessId() != bizId) throw new SecurityException("Cross-business access denied.");
            if ("VOIDED".equals(inv.status())) throw new IllegalStateException("Invoice is already voided.");
            if (inv.paidAmount().isPositive()) {
                throw new IllegalStateException("Cannot void invoice with recorded payments. Void linked payments first.");
            }
            invoiceDao.voidInvoice(conn, invoiceId, bizId, reason.trim());
            Money curPayable = supplierLedgerDao.getCurrentPayableBalance(conn, inv.supplierId());
            Money newPayable = curPayable.subtract(inv.netAmount());
            supplierLedgerDao.save(conn, new SupplierLedgerEntry(
                    null, bizId, inv.supplierId(), LocalDateTime.now(),
                    "BILL_REVERSAL", inv.id(), Money.ZERO, inv.netAmount(), newPayable,
                    "Void Invoice " + inv.invoiceNumber() + ": " + reason.trim(), null
            ));
        });
    }

    @Override
    public Optional<SupplierInvoiceDetailDto> getSupplierInvoiceDetail(long invoiceId) {
        SessionContext.requireAuthenticated();
        long bizId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<SupplierInvoice> opt = invoiceDao.findById(conn, invoiceId);
            if (opt.isEmpty()) return Optional.empty();
            SupplierInvoice inv = opt.get();
            if (inv.businessId() != bizId) throw new SecurityException("Cross-business access denied.");
            Supplier sup = supplierDao.findById(conn, inv.supplierId()).orElse(null);
            String poNum = inv.purchaseOrderId() != null ? poDao.findById(conn, inv.purchaseOrderId()).map(PurchaseOrder::poNumber).orElse(null) : null;
            String grnNum = inv.grnId() != null ? grnDao.findById(conn, inv.grnId()).map(GoodsReceiptNote::grnNumber).orElse(null) : null;
            List<SupplierInvoiceItemViewDto> items = invoiceDao.findItemViewsByInvoiceId(conn, invoiceId);
            return Optional.of(new SupplierInvoiceDetailDto(inv, poNum, grnNum, sup, items));
        } catch (SQLException e) {
            throw new DatabaseException("Failed to fetch supplier invoice detail", e);
        }
    }

    @Override
    public List<SupplierInvoiceListItemDto> searchSupplierInvoices(long businessId, String query, Long supplierId,
                                                                   String paymentStatus, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) throw new SecurityException("Cross-business access denied.");
        try (Connection conn = DatabaseManager.getConnection()) {
            return invoiceDao.searchInvoices(conn, businessId, query, supplierId, paymentStatus, startDate, endDate);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to search supplier invoices", e);
        }
    }

    @Override
    public List<GrnListItemDto> getUninvoicedGrns(long businessId, Long supplierId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) throw new SecurityException("Cross-business access denied.");
        try (Connection conn = DatabaseManager.getConnection()) {
            return invoiceDao.findUninvoicedGrns(conn, businessId, supplierId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load uninvoiced GRNs", e);
        }
    }

    @Override
    public Money getTotalUninvoicedGrnLiability(long businessId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) throw new SecurityException("Cross-business access denied.");
        try (Connection conn = DatabaseManager.getConnection()) {
            return invoiceDao.getTotalUninvoicedGrnLiability(conn, businessId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to calculate received-not-invoiced GRN liability", e);
        }
    }
}


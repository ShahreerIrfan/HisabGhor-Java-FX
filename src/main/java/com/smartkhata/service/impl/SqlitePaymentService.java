package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.PaymentService;
import com.smartkhata.service.api.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqlitePaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(SqlitePaymentService.class);
    private static final DateTimeFormatter DATE_PREFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PaymentDao paymentDao;
    private final CustomerDao customerDao;
    private final CustomerLedgerDao customerLedgerDao;
    private final SupplierDao supplierDao;
    private final SupplierLedgerDao supplierLedgerDao;
    private final SaleDao saleDao;
    private final PurchaseDao purchaseDao;
    private final UserDao userDao;
    private final SupplierInvoiceDao supplierInvoiceDao;

    public SqlitePaymentService() {
        this(new SqlitePaymentDao(), new SqliteCustomerDao(), new SqliteCustomerLedgerDao(),
                new SqliteSupplierDao(), new SqliteSupplierLedgerDao(), new SqliteSaleDao(),
                new SqlitePurchaseDao(), new SqliteUserDao(), new SqliteSupplierInvoiceDao());
    }

    public SqlitePaymentService(PaymentDao paymentDao, CustomerDao customerDao, CustomerLedgerDao customerLedgerDao,
                                SupplierDao supplierDao, SupplierLedgerDao supplierLedgerDao, SaleDao saleDao,
                                PurchaseDao purchaseDao, UserDao userDao) {
        this(paymentDao, customerDao, customerLedgerDao, supplierDao, supplierLedgerDao, saleDao,
                purchaseDao, userDao, new SqliteSupplierInvoiceDao());
    }

    public SqlitePaymentService(PaymentDao paymentDao, CustomerDao customerDao, CustomerLedgerDao customerLedgerDao,
                                SupplierDao supplierDao, SupplierLedgerDao supplierLedgerDao, SaleDao saleDao,
                                PurchaseDao purchaseDao, UserDao userDao, SupplierInvoiceDao supplierInvoiceDao) {
        this.paymentDao = paymentDao;
        this.customerDao = customerDao;
        this.customerLedgerDao = customerLedgerDao;
        this.supplierDao = supplierDao;
        this.supplierLedgerDao = supplierLedgerDao;
        this.saleDao = saleDao;
        this.purchaseDao = purchaseDao;
        this.userDao = userDao;
        this.supplierInvoiceDao = supplierInvoiceDao;
    }

    @Override
    public Payment receiveCustomerPayment(PaymentCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.amount() == null || !dto.amount().isPositive()) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Idempotency Guard: prevent duplicate collection
            if (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank()) {
                Optional<Payment> existing = paymentDao.findByIdempotencyKey(conn, dto.businessId(), dto.idempotencyKey().trim());
                if (existing.isPresent()) {
                    log.info("Idempotent payment collection matched existing receipt [{}]", existing.get().paymentNumber());
                    return existing.get();
                }
            }

            // 2. Validate Customer and Store Scope
            Optional<Customer> custOpt = customerDao.findById(conn, dto.partyId());
            if (custOpt.isEmpty()) {
                throw new IllegalArgumentException("Customer not found with id: " + dto.partyId());
            }
            Customer customer = custOpt.get();
            if (customer.businessId() != dto.businessId()) {
                throw new SecurityException("Customer does not belong to active store context.");
            }

            // 3. Outstanding Due Check: Advance beyond due balance is rejected explicitly
            Money currentDue = customerLedgerDao.getCurrentBalance(conn, dto.partyId());
            if (dto.amount().compareTo(currentDue) > 0) {
                throw new IllegalArgumentException("Collection amount (" + dto.amount() +
                        ") exceeds total outstanding customer due (" + currentDue +
                        "). Advance collections beyond due balance are not permitted in current policy.");
            }

            // 4. Generate Sequential Payment Reference
            String datePrefix = LocalDate.now().format(DATE_PREFIX_FMT);
            int seq = paymentDao.getNextPaymentSequence(conn, dto.businessId(), datePrefix, "CUSTOMER");
            String paymentNumber = String.format("PAY-C-%s-%04d", datePrefix, seq);

            // 5. Persist Payment Header
            Payment payment = new Payment(
                    null,
                    dto.businessId(),
                    paymentNumber,
                    "CUSTOMER",
                    dto.partyId(),
                    dto.amount(),
                    LocalDateTime.now(),
                    dto.paymentMethod() != null ? dto.paymentMethod() : "CASH",
                    dto.referenceTrxId(),
                    dto.notes(),
                    dto.actingUserId(),
                    dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank() ? dto.idempotencyKey().trim() : null,
                    false, null, null, null
            );
            Payment savedPayment = paymentDao.save(conn, payment);

            // 6. Deterministic FIFO Invoice Allocation across unpaid sales
            List<Sale> unpaidSales = saleDao.findUnpaidSalesByCustomerId(conn, dto.partyId());
            Money remainingToAllocate = dto.amount();
            List<PaymentAllocation> allocations = new ArrayList<>();

            for (Sale sale : unpaidSales) {
                if (remainingToAllocate.isZero()) break;

                Money saleDue = sale.dueAmount();
                Money alloc = remainingToAllocate.compareTo(saleDue) >= 0 ? saleDue : remainingToAllocate;

                Money newPaid = sale.paidAmount().add(alloc);
                Money newDue = sale.dueAmount().subtract(alloc);
                String newStatus = newDue.isZero() ? "PAID" : "PARTIAL";

                saleDao.updatePaymentStatus(conn, sale.id(), newPaid, newDue, newStatus);
                allocations.add(new PaymentAllocation(null, savedPayment.id(), sale.id(), null, alloc, LocalDateTime.now()));

                remainingToAllocate = remainingToAllocate.subtract(alloc);
            }
            paymentDao.saveAllocations(conn, allocations);

            // 7. Post to Customer Ledger (Credit decreases customer due)
            Money runningBalance = currentDue.subtract(dto.amount());
            CustomerLedgerEntry ledgerEntry = new CustomerLedgerEntry(
                    null,
                    dto.businessId(),
                    dto.partyId(),
                    LocalDateTime.now(),
                    "PAYMENT_COLLECTION",
                    savedPayment.id(),
                    Money.ZERO,
                    dto.amount(), // Credit reduces debt
                    runningBalance,
                    "Due Collection " + paymentNumber + (dto.notes() != null ? " - " + dto.notes() : ""),
                    null
            );
            customerLedgerDao.save(conn, ledgerEntry);

            log.info("Customer payment [{}] recorded successfully (Amount: {}, Customer: {}, Running Due: {})",
                    paymentNumber, dto.amount(), customer.name(), runningBalance);
            return savedPayment;
        });
    }

    @Override
    public Payment makeSupplierPayment(PaymentCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.amount() == null || !dto.amount().isPositive()) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Idempotency Guard: prevent duplicate disbursement
            if (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank()) {
                Optional<Payment> existing = paymentDao.findByIdempotencyKey(conn, dto.businessId(), dto.idempotencyKey().trim());
                if (existing.isPresent()) {
                    log.info("Idempotent supplier payment matched existing voucher [{}]", existing.get().paymentNumber());
                    return existing.get();
                }
            }

            // 2. Validate Supplier and Store Scope
            Optional<Supplier> suppOpt = supplierDao.findById(conn, dto.partyId());
            if (suppOpt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + dto.partyId());
            }
            Supplier supplier = suppOpt.get();
            if (supplier.businessId() != dto.businessId()) {
                throw new SecurityException("Supplier does not belong to active store context.");
            }

            // 3. Outstanding Payable Check: Advance beyond payable balance is rejected explicitly
            Money currentPayable = supplierLedgerDao.getCurrentPayableBalance(conn, dto.partyId());
            if (dto.amount().compareTo(currentPayable) > 0) {
                throw new IllegalArgumentException("Payment amount (" + dto.amount() +
                        ") exceeds total outstanding supplier payable (" + currentPayable +
                        "). Advance payments beyond payable balance are not permitted in current policy.");
            }

            // 4. Generate Sequential Payment Reference
            String datePrefix = LocalDate.now().format(DATE_PREFIX_FMT);
            int seq = paymentDao.getNextPaymentSequence(conn, dto.businessId(), datePrefix, "SUPPLIER");
            String paymentNumber = String.format("PAY-S-%s-%04d", datePrefix, seq);

            // 5. Persist Payment Header
            Payment payment = new Payment(
                    null,
                    dto.businessId(),
                    paymentNumber,
                    "SUPPLIER",
                    dto.partyId(),
                    dto.amount(),
                    LocalDateTime.now(),
                    dto.paymentMethod() != null ? dto.paymentMethod() : "CASH",
                    dto.referenceTrxId(),
                    dto.notes(),
                    dto.actingUserId(),
                    dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank() ? dto.idempotencyKey().trim() : null,
                    false, null, null, null
            );
            Payment savedPayment = paymentDao.save(conn, payment);

            // 6. Deterministic FIFO Bill Allocation across unpaid purchases and supplier invoices
            List<Purchase> unpaidPurchases = purchaseDao.findUnpaidPurchasesBySupplierId(conn, dto.partyId());
            List<SupplierInvoice> unpaidInvoices = supplierInvoiceDao.findUnpaidBySupplierId(conn, dto.partyId());
            Money remainingToAllocate = dto.amount();
            List<PaymentAllocation> allocations = new ArrayList<>();

            for (SupplierInvoice inv : unpaidInvoices) {
                if (remainingToAllocate.isZero()) break;
                Money invDue = inv.dueAmount();
                Money alloc = remainingToAllocate.compareTo(invDue) >= 0 ? invDue : remainingToAllocate;
                Money newPaid = inv.paidAmount().add(alloc);
                Money newDue = inv.dueAmount().subtract(alloc);
                String newStatus = newDue.isZero() ? "PAID" : "PARTIAL";

                supplierInvoiceDao.updatePaymentStatus(conn, inv.id(), newPaid, newDue, newStatus);
                allocations.add(new PaymentAllocation(null, savedPayment.id(), null, null, alloc, LocalDateTime.now(), inv.id()));
                remainingToAllocate = remainingToAllocate.subtract(alloc);
            }

            for (Purchase purchase : unpaidPurchases) {
                if (remainingToAllocate.isZero()) break;

                Money purDue = purchase.dueAmount();
                Money alloc = remainingToAllocate.compareTo(purDue) >= 0 ? purDue : remainingToAllocate;

                Money newPaid = purchase.paidAmount().add(alloc);
                Money newDue = purchase.dueAmount().subtract(alloc);
                String newStatus = newDue.isZero() ? "PAID" : "PARTIAL";

                purchaseDao.updatePaymentStatus(conn, purchase.id(), newPaid, newDue, newStatus);
                allocations.add(new PaymentAllocation(null, savedPayment.id(), null, purchase.id(), alloc, LocalDateTime.now()));

                remainingToAllocate = remainingToAllocate.subtract(alloc);
            }
            paymentDao.saveAllocations(conn, allocations);

            // 7. Post to Supplier Ledger (Debit decreases supplier liability)
            Money runningBalance = currentPayable.subtract(dto.amount());
            SupplierLedgerEntry ledgerEntry = new SupplierLedgerEntry(
                    null,
                    dto.businessId(),
                    dto.partyId(),
                    LocalDateTime.now(),
                    "PAYMENT_DISBURSEMENT",
                    savedPayment.id(),
                    Money.ZERO,
                    dto.amount(), // Debit reduces payable
                    runningBalance,
                    "Disbursement " + paymentNumber + (dto.notes() != null ? " - " + dto.notes() : ""),
                    null
            );
            supplierLedgerDao.save(conn, ledgerEntry);

            log.info("Supplier disbursement [{}] recorded successfully (Amount: {}, Supplier: {}, Running Payable: {})",
                    paymentNumber, dto.amount(), supplier.name(), runningBalance);
            return savedPayment;
        });
    }

    @Override
    public void voidPayment(long paymentId, String reason, long actingUserId) {
        SessionContext.requireAuthenticated();
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("A specific reason is required to void/reverse a payment.");
        }

        TransactionManager.runInTransaction(conn -> {
            Optional<Payment> opt = paymentDao.findById(conn, paymentId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Payment not found with id: " + paymentId);
            }
            Payment payment = opt.get();
            if (payment.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: payment belongs to another business.");
            }
            if (payment.isReversed()) {
                throw new IllegalStateException("Payment [" + payment.paymentNumber() + "] is already voided/reversed.");
            }

            LocalDateTime now = LocalDateTime.now();

            // 1. Mark payment as reversed
            paymentDao.voidPayment(conn, paymentId, payment.businessId(), reason.trim(), now);

            // 2. Unwind invoice / bill allocations
            List<PaymentAllocationDto> allocs = paymentDao.findAllocationsByPaymentId(conn, paymentId);
            for (PaymentAllocationDto alloc : allocs) {
                if (alloc.saleId() != null) {
                    Optional<Sale> sOpt = saleDao.findById(conn, alloc.saleId());
                    if (sOpt.isPresent()) {
                        Sale s = sOpt.get();
                        Money restoredPaid = s.paidAmount().subtract(alloc.allocatedAmount());
                        Money restoredDue = s.dueAmount().add(alloc.allocatedAmount());
                        String restoredStatus = restoredPaid.isZero() ? "BAKI" : "PARTIAL";
                        saleDao.updatePaymentStatus(conn, s.id(), restoredPaid, restoredDue, restoredStatus);
                    }
                }
                if (alloc.purchaseId() != null) {
                    Optional<Purchase> pOpt = purchaseDao.findById(conn, alloc.purchaseId());
                    if (pOpt.isPresent()) {
                        Purchase p = pOpt.get();
                        Money restoredPaid = p.paidAmount().subtract(alloc.allocatedAmount());
                        Money restoredDue = p.dueAmount().add(alloc.allocatedAmount());
                        String restoredStatus = restoredPaid.isZero() ? "DUE" : "PARTIAL";
                        purchaseDao.updatePaymentStatus(conn, p.id(), restoredPaid, restoredDue, restoredStatus);
                    }
                }
                if (alloc.supplierInvoiceId() != null) {
                    Optional<SupplierInvoice> siOpt = supplierInvoiceDao.findById(conn, alloc.supplierInvoiceId());
                    if (siOpt.isPresent()) {
                        SupplierInvoice si = siOpt.get();
                        Money restoredPaid = si.paidAmount().subtract(alloc.allocatedAmount());
                        Money restoredDue = si.dueAmount().add(alloc.allocatedAmount());
                        String restoredStatus = restoredPaid.isZero() ? "DUE" : "PARTIAL";
                        supplierInvoiceDao.updatePaymentStatus(conn, si.id(), restoredPaid, restoredDue, restoredStatus);
                    }
                }
            }

            // 3. Post Ledger Offsetting Entry
            if ("CUSTOMER".equalsIgnoreCase(payment.partyType())) {
                Money currentDue = customerLedgerDao.getCurrentBalance(conn, payment.partyId());
                Money newDue = currentDue.add(payment.amount()); // Restoring due increases customer debt back
                CustomerLedgerEntry offset = new CustomerLedgerEntry(
                        null,
                        payment.businessId(),
                        payment.partyId(),
                        now,
                        "ADJUSTMENT",
                        payment.id(),
                        payment.amount(), // Debit increases debt
                        Money.ZERO,
                        newDue,
                        "Reversal of " + payment.paymentNumber() + ": " + reason.trim(),
                        null
                );
                customerLedgerDao.save(conn, offset);
            } else if ("SUPPLIER".equalsIgnoreCase(payment.partyType())) {
                Money currentPay = supplierLedgerDao.getCurrentPayableBalance(conn, payment.partyId());
                Money newPay = currentPay.add(payment.amount()); // Restoring liability increases payable back
                SupplierLedgerEntry offset = new SupplierLedgerEntry(
                        null,
                        payment.businessId(),
                        payment.partyId(),
                        now,
                        "ADJUSTMENT",
                        payment.id(),
                        payment.amount(), // Credit increases payable
                        Money.ZERO,
                        newPay,
                        "Reversal of " + payment.paymentNumber() + ": " + reason.trim(),
                        null
                );
                supplierLedgerDao.save(conn, offset);
            }

            log.info("Payment [{}] (ID: {}) voided successfully. Allocations reversed and ledger restored.",
                    payment.paymentNumber(), paymentId);
        });
    }

    @Override
    public void updatePaymentDetails(long paymentId, String paymentMethod, String referenceTrxId, String notes) {
        SessionContext.requireAuthenticated();
        String method = paymentMethod != null ? paymentMethod.trim().toUpperCase(java.util.Locale.ROOT) : "CASH";
        if (!java.util.Set.of("CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER").contains(method)) {
            throw new IllegalArgumentException("Unsupported payment method: " + paymentMethod);
        }

        TransactionManager.runInTransaction(conn -> {
            Payment payment = paymentDao.findById(conn, paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment not found with id: " + paymentId));
            if (payment.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: payment belongs to another business.");
            }
            if (payment.isReversed()) {
                throw new IllegalStateException("Cannot edit a voided/reversed payment voucher.");
            }
            paymentDao.updateMetadata(conn, paymentId, payment.businessId(), method, referenceTrxId, notes);
            log.info("Updated metadata for payment [{}] (ID: {})", payment.paymentNumber(), paymentId);
        });
    }

    @Override
    public void deleteVoidedPayment(long paymentId) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Payment payment = paymentDao.findById(conn, paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment not found with id: " + paymentId));
            if (payment.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: payment belongs to another business.");
            }
            if (!payment.isReversed()) {
                throw new IllegalStateException("Only voided/reversed payments can be deleted. Void this voucher first.");
            }
            boolean deleted = paymentDao.deleteVoided(conn, paymentId, payment.businessId());
            if (!deleted) {
                throw new IllegalStateException("Unable to delete voided payment [" + payment.paymentNumber() + "].");
            }
            log.info("Voided payment [{}] (ID: {}) deleted permanently by owner", payment.paymentNumber(), paymentId);
        });
    }

    @Override
    public Optional<PaymentDetailDto> getPaymentDetail(long paymentId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Payment> opt = paymentDao.findById(conn, paymentId);
            if (opt.isEmpty()) return Optional.empty();
            Payment payment = opt.get();
            if (payment.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: payment belongs to another business.");
            }

            String partyName = "General";
            String partyPhone = "—";
            if ("CUSTOMER".equalsIgnoreCase(payment.partyType())) {
                Optional<Customer> cOpt = customerDao.findById(conn, payment.partyId());
                if (cOpt.isPresent()) {
                    Customer c = cOpt.get();
                    partyName = c.name();
                    if (c.phone() != null) partyPhone = c.phone();
                }
            } else if ("SUPPLIER".equalsIgnoreCase(payment.partyType())) {
                Optional<Supplier> sOpt = supplierDao.findById(conn, payment.partyId());
                if (sOpt.isPresent()) {
                    Supplier s = sOpt.get();
                    partyName = s.name();
                    if (s.phone() != null) partyPhone = s.phone();
                }
            }

            String creatorName = "System";
            Optional<User> uOpt = userDao.findById(conn, payment.createdByUserId());
            if (uOpt.isPresent()) {
                creatorName = uOpt.get().fullName();
            }

            List<PaymentAllocationDto> allocations = paymentDao.findAllocationsByPaymentId(conn, paymentId);

            return Optional.of(new PaymentDetailDto(
                    payment, partyName, partyPhone, allocations, creatorName
            ));
        } catch (SQLException e) {
            log.error("Failed to load payment detail: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to load payment details", e);
        }
    }

    @Override
    public List<PaymentListItemDto> searchPayments(long businessId, String query, String partyType,
                                                  Long partyId, String paymentMethod, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return paymentDao.searchPayments(conn, businessId, query, partyType, partyId, paymentMethod, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to search payments: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve payment records", e);
        }
    }
}


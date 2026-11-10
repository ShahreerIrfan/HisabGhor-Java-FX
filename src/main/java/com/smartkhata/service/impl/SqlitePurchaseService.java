package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.PurchaseService;
import com.smartkhata.service.api.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqlitePurchaseService implements PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(SqlitePurchaseService.class);
    private static final DateTimeFormatter DATE_PREFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseDao purchaseDao;
    private final ProductDao productDao;
    private final StockMovementDao stockMovementDao;
    private final SupplierDao supplierDao;
    private final SupplierLedgerDao supplierLedgerDao;
    private final UserDao userDao;

    public SqlitePurchaseService() {
        this(new SqlitePurchaseDao(), new SqliteProductDao(), new SqliteStockMovementDao(),
                new SqliteSupplierDao(), new SqliteSupplierLedgerDao(), new SqliteUserDao());
    }

    public SqlitePurchaseService(PurchaseDao purchaseDao, ProductDao productDao, StockMovementDao stockMovementDao,
                                 SupplierDao supplierDao, SupplierLedgerDao supplierLedgerDao, UserDao userDao) {
        this.purchaseDao = purchaseDao;
        this.productDao = productDao;
        this.stockMovementDao = stockMovementDao;
        this.supplierDao = supplierDao;
        this.supplierLedgerDao = supplierLedgerDao;
        this.userDao = userDao;
    }

    @Override
    public Purchase createPurchase(PurchaseCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("A purchase invoice must contain at least one line item.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Idempotency Guard: prevent duplicate bill creation from retries
            if (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank()) {
                Optional<Purchase> existing = purchaseDao.findByIdempotencyKey(conn, dto.businessId(), dto.idempotencyKey().trim());
                if (existing.isPresent()) {
                    log.info("Idempotent purchase request matched existing bill [{}]", existing.get().purchaseNumber());
                    return existing.get();
                }
            }

            // 2. Validate Supplier existence and business scope
            Optional<Supplier> supplierOpt = supplierDao.findById(conn, dto.supplierId());
            if (supplierOpt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + dto.supplierId());
            }
            Supplier supplier = supplierOpt.get();
            if (supplier.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another store context.");
            }
            if (supplier.isArchived()) {
                throw new IllegalStateException("Cannot create purchase bill for an archived supplier.");
            }

            // 3. Validate product items
            for (PurchaseItemDto item : dto.items()) {
                if (item.quantity() <= 0) {
                    throw new IllegalArgumentException("Item quantity must be greater than zero.");
                }
                if (item.unitCost() == null || item.unitCost().isNegative()) {
                    throw new IllegalArgumentException("Item unit cost cannot be negative.");
                }
                Optional<Product> pOpt = productDao.findById(conn, item.productId());
                if (pOpt.isEmpty()) {
                    throw new IllegalArgumentException("Product not found with id: " + item.productId());
                }
                Product p = pOpt.get();
                if (p.businessId() != dto.businessId()) {
                    throw new SecurityException("Product does not belong to active store context.");
                }
            }

            // 4. Compute Totals
            Money subtotal = Money.ZERO;
            List<PurchaseItem> itemsToPersist = new ArrayList<>(dto.items().size());

            for (PurchaseItemDto item : dto.items()) {
                Money lineTotal = item.unitCost().multiply(item.quantity());
                subtotal = subtotal.add(lineTotal);

                itemsToPersist.add(new PurchaseItem(
                        null, 0L, item.productId(), item.quantity(), item.unitCost(), lineTotal
                ));
            }

            Money discount = dto.discount() != null ? dto.discount() : Money.ZERO;
            Money netAmount = subtotal.subtract(discount);
            if (netAmount.isNegative()) {
                throw new IllegalArgumentException("Net purchase amount cannot be negative.");
            }

            Money paidInput = dto.paidAmount() != null ? dto.paidAmount() : Money.ZERO;
            Money paidAmount = paidInput.compareTo(netAmount) > 0 ? netAmount : paidInput;
            Money dueAmount = netAmount.subtract(paidAmount);

            String paymentStatus;
            if (dueAmount.isZero()) {
                paymentStatus = "PAID";
            } else if (paidAmount.isZero()) {
                paymentStatus = "DUE";
            } else {
                paymentStatus = "PARTIAL";
            }

            // 5. Generate Sequential Purchase Number
            String datePrefix = LocalDate.now().format(DATE_PREFIX_FMT);
            int seq = purchaseDao.getNextPurchaseSequence(conn, dto.businessId(), datePrefix);
            String purchaseNumber = String.format("PUR-%s-%04d", datePrefix, seq);

            // 6. Persist Purchase Header
            Purchase purchase = new Purchase(
                    null,
                    dto.businessId(),
                    purchaseNumber,
                    dto.supplierId(),
                    subtotal,
                    discount,
                    netAmount,
                    paidAmount,
                    dueAmount,
                    paymentStatus,
                    dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank() ? dto.idempotencyKey().trim() : null,
                    LocalDateTime.now(),
                    dto.notes(),
                    dto.actingUserId(),
                    false, null, null, null, null
            );
            Purchase savedPurchase = purchaseDao.save(conn, purchase);

            // 7. Persist Purchase Line Items, update recorded product cost price, and post positive stock movements
            List<PurchaseItem> boundItems = new ArrayList<>(itemsToPersist.size());
            for (PurchaseItem item : itemsToPersist) {
                boundItems.add(item.withPurchaseId(savedPurchase.id()));

                // Reflect latest purchase cost in product catalog for accurate inventory valuation
                productDao.updateCostPrice(conn, item.productId(), item.unitCost());

                // Post atomic positive inward inventory movement
                StockMovement stockIn = new StockMovement(
                        null,
                        savedPurchase.businessId(),
                        item.productId(),
                        "PURCHASE_IN",
                        item.quantity(),
                        item.unitCost(),
                        "PURCHASE",
                        savedPurchase.id(),
                        "Purchase " + purchaseNumber,
                        dto.actingUserId(),
                        LocalDateTime.now()
                );
                stockMovementDao.save(conn, stockIn);
            }
            purchaseDao.saveItems(conn, boundItems);

            // 8. Persist Initial Supplier Payment if paid upfront
            Long paymentId = null;
            if (paidAmount.isPositive()) {
                String payNumber = String.format("PAY-S-%s-%04d", datePrefix, seq);
                String payMethod = dto.paymentMethod() != null && !dto.paymentMethod().isBlank() ? dto.paymentMethod() : "CASH";

                String sqlPay = "INSERT INTO payments (business_id, payment_number, party_type, party_id, amount_paisha, " +
                        "payment_date, payment_method, notes, created_by_user_id) VALUES (?, ?, 'SUPPLIER', ?, ?, datetime('now', 'localtime'), ?, ?, ?)";
                try (PreparedStatement psPay = conn.prepareStatement(sqlPay, Statement.RETURN_GENERATED_KEYS)) {
                    psPay.setLong(1, savedPurchase.businessId());
                    psPay.setString(2, payNumber);
                    psPay.setLong(3, savedPurchase.supplierId());
                    psPay.setLong(4, paidAmount.paisha());
                    psPay.setString(5, payMethod);
                    psPay.setString(6, "Initial payment for " + purchaseNumber);
                    psPay.setLong(7, dto.actingUserId());
                    psPay.executeUpdate();

                    try (ResultSet rs = psPay.getGeneratedKeys()) {
                        if (rs.next()) {
                            paymentId = rs.getLong(1);
                        }
                    }
                }

                if (paymentId != null) {
                    String sqlAlloc = "INSERT INTO payment_allocations (payment_id, purchase_id, allocated_amount_paisha) VALUES (?, ?, ?)";
                    try (PreparedStatement psAlloc = conn.prepareStatement(sqlAlloc)) {
                        psAlloc.setLong(1, paymentId);
                        psAlloc.setLong(2, savedPurchase.id());
                        psAlloc.setLong(3, paidAmount.paisha());
                        psAlloc.executeUpdate();
                    }
                }
            }

            // 9. Post Traceable Supplier Ledger Entries (Double-Entry Log)
            Money currentPayable = supplierLedgerDao.getCurrentPayableBalance(conn, savedPurchase.supplierId());

            // Credit: Purchase bill increases business liability (payable to supplier)
            Money runningAfterBill = currentPayable.add(netAmount);
            SupplierLedgerEntry billEntry = new SupplierLedgerEntry(
                    null,
                    savedPurchase.businessId(),
                    savedPurchase.supplierId(),
                    LocalDateTime.now(),
                    "PURCHASE_BILL",
                    savedPurchase.id(),
                    netAmount,   // credit increases payable
                    Money.ZERO,  // debit
                    runningAfterBill,
                    "Purchase Bill " + purchaseNumber,
                    null
            );
            supplierLedgerDao.save(conn, billEntry);

            // Debit: Upfront payment disbursement decreases liability to supplier
            if (paidAmount.isPositive()) {
                Money runningAfterPayment = runningAfterBill.subtract(paidAmount);
                SupplierLedgerEntry payEntry = new SupplierLedgerEntry(
                        null,
                        savedPurchase.businessId(),
                        savedPurchase.supplierId(),
                        LocalDateTime.now(),
                        "PAYMENT_DISBURSEMENT",
                        paymentId,
                        Money.ZERO,  // credit
                        paidAmount,  // debit decreases payable
                        runningAfterPayment,
                        "Payment for " + purchaseNumber,
                        null
                );
                supplierLedgerDao.save(conn, payEntry);
            }

            log.info("Purchase [{}] recorded successfully (Net: {}, Paid: {}, Due: {}, Status: {})",
                    savedPurchase.purchaseNumber(), netAmount, paidAmount, dueAmount, paymentStatus);
            return savedPurchase;
        });
    }

    @Override
    public void voidPurchase(long purchaseId, String reason, long actingUserId) {
        SessionContext.requireAuthenticated();
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("A specific reason is required to void/reverse a purchase.");
        }

        TransactionManager.runInTransaction(conn -> {
            Optional<Purchase> opt = purchaseDao.findById(conn, purchaseId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Purchase not found with id: " + purchaseId);
            }
            Purchase purchase = opt.get();
            if (purchase.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: purchase belongs to another business.");
            }
            if (purchase.isReversed()) {
                throw new IllegalStateException("Purchase [" + purchase.purchaseNumber() + "] is already voided/reversed.");
            }

            List<PurchaseItem> items = purchaseDao.findItemsByPurchaseId(conn, purchaseId);

            // 1. Verify that reversal won't cause negative stock (e.g., if goods were already sold)
            for (PurchaseItem item : items) {
                int currentStock = productDao.getCurrentStock(conn, item.productId());
                if (currentStock < item.quantity()) {
                    throw new InsufficientStockException(item.productId(), currentStock, item.quantity());
                }
            }

            // 2. Mark purchase as reversed in purchases table
            LocalDateTime now = LocalDateTime.now();
            purchaseDao.voidPurchase(conn, purchaseId, purchase.businessId(), reason.trim(), now);

            // 3. Remove received stock via auditable RETURN_OUT movement (negative quantity)
            for (PurchaseItem item : items) {
                StockMovement returnOut = new StockMovement(
                        null,
                        purchase.businessId(),
                        item.productId(),
                        "RETURN_OUT",
                        -item.quantity(),
                        item.unitCost(),
                        "PURCHASE",
                        purchase.id(),
                        "Reversal of " + purchase.purchaseNumber() + ": " + reason.trim(),
                        actingUserId,
                        now
                );
                stockMovementDao.save(conn, returnOut);
            }

            // 4. Reverse outstanding supplier dues in supplier_ledger
            if (purchase.dueAmount().isPositive()) {
                Money currentBal = supplierLedgerDao.getCurrentPayableBalance(conn, purchase.supplierId());
                Money newBal = currentBal.subtract(purchase.dueAmount());

                SupplierLedgerEntry reversalEntry = new SupplierLedgerEntry(
                        null,
                        purchase.businessId(),
                        purchase.supplierId(),
                        now,
                        "BILL_REVERSAL",
                        purchase.id(),
                        Money.ZERO,
                        purchase.dueAmount(), // Debit decreases supplier payable
                        newBal,
                        "Reversal of " + purchase.purchaseNumber() + ": " + reason.trim(),
                        null
                );
                supplierLedgerDao.save(conn, reversalEntry);
            }

            log.info("Purchase [{}] (ID: {}) voided successfully. Stock corrected and supplier payables reversed.",
                    purchase.purchaseNumber(), purchaseId);
        });
    }

    @Override
    public void updatePurchaseNotes(long purchaseId, String notes) {
        SessionContext.requireAuthenticated();
        TransactionManager.runInTransaction(conn -> {
            Purchase purchase = purchaseDao.findById(conn, purchaseId)
                    .orElseThrow(() -> new IllegalArgumentException("Purchase not found with id: " + purchaseId));
            if (purchase.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: purchase belongs to another business.");
            }
            purchaseDao.updateNotes(conn, purchaseId, purchase.businessId(), notes);
            log.info("Updated notes for purchase [{}] (ID: {})", purchase.purchaseNumber(), purchaseId);
        });
    }

    @Override
    public void deleteVoidedPurchase(long purchaseId) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Purchase purchase = purchaseDao.findById(conn, purchaseId)
                    .orElseThrow(() -> new IllegalArgumentException("Purchase not found with id: " + purchaseId));
            if (purchase.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: purchase belongs to another business.");
            }
            if (!purchase.isReversed()) {
                throw new IllegalStateException("Only voided/reversed purchase bills can be deleted. Void this bill first.");
            }
            if (purchaseDao.hasActiveLinkedPayments(conn, purchaseId)) {
                throw new IllegalStateException("Cannot delete bill while an active linked supplier payment exists. Void linked payment(s) first.");
            }
            boolean deleted = purchaseDao.deleteVoided(conn, purchaseId, purchase.businessId());
            if (!deleted) {
                throw new IllegalStateException("Unable to delete voided purchase [" + purchase.purchaseNumber() + "].");
            }
            log.info("Voided purchase [{}] (ID: {}) deleted permanently by owner", purchase.purchaseNumber(), purchaseId);
        });
    }

    @Override
    public Optional<PurchaseDetailDto> getPurchaseDetail(long purchaseId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Purchase> purchaseOpt = purchaseDao.findById(conn, purchaseId);
            if (purchaseOpt.isEmpty()) return Optional.empty();
            Purchase purchase = purchaseOpt.get();
            if (purchase.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: purchase belongs to another business.");
            }

            String suppName = "Unknown Supplier";
            String suppPhone = "—";
            String suppAddr = "—";
            Optional<Supplier> sOpt = supplierDao.findById(conn, purchase.supplierId());
            if (sOpt.isPresent()) {
                Supplier s = sOpt.get();
                suppName = s.name();
                if (s.phone() != null) suppPhone = s.phone();
                if (s.address() != null) suppAddr = s.address();
            }

            String creatorName = "System";
            Optional<User> uOpt = userDao.findById(conn, purchase.createdByUserId());
            if (uOpt.isPresent()) {
                creatorName = uOpt.get().fullName();
            }

            List<PurchaseItem> rawItems = purchaseDao.findItemsByPurchaseId(conn, purchaseId);
            List<PurchaseItemDto> dtos = new ArrayList<>(rawItems.size());
            for (PurchaseItem item : rawItems) {
                String pName = "Product #" + item.productId();
                Optional<Product> pOpt = productDao.findById(conn, item.productId());
                if (pOpt.isPresent()) {
                    pName = pOpt.get().name();
                }
                dtos.add(new PurchaseItemDto(
                        item.productId(),
                        pName,
                        item.quantity(),
                        item.unitCost(),
                        item.lineTotal()
                ));
            }

            return Optional.of(new PurchaseDetailDto(
                    purchase, suppName, suppPhone, suppAddr, dtos, creatorName
            ));
        } catch (SQLException e) {
            log.error("Failed to load purchase detail: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to load purchase details", e);
        }
    }

    @Override
    public List<PurchaseListItemDto> searchPurchases(long businessId, String query, Long supplierId,
                                                    String paymentStatus, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return purchaseDao.searchPurchases(conn, businessId, query, supplierId, paymentStatus, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to search purchases: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve purchases records", e);
        }
    }
}


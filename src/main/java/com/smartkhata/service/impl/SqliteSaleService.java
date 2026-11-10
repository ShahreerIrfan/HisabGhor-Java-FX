package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.SaleService;
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

public class SqliteSaleService implements SaleService {

    private static final Logger log = LoggerFactory.getLogger(SqliteSaleService.class);
    private static final DateTimeFormatter DATE_PREFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SaleDao saleDao;
    private final ProductDao productDao;
    private final StockMovementDao stockMovementDao;
    private final CustomerDao customerDao;
    private final CustomerLedgerDao customerLedgerDao;
    private final UserDao userDao;
    private final SettingsDao settingsDao;

    public SqliteSaleService() {
        this(new SqliteSaleDao(), new SqliteProductDao(), new SqliteStockMovementDao(),
                new SqliteCustomerDao(), new SqliteCustomerLedgerDao(), new SqliteUserDao(),
                new SqliteSettingsDao());
    }

    public SqliteSaleService(SaleDao saleDao, ProductDao productDao, StockMovementDao stockMovementDao,
                             CustomerDao customerDao, CustomerLedgerDao customerLedgerDao, UserDao userDao) {
        this(saleDao, productDao, stockMovementDao, customerDao, customerLedgerDao, userDao, new SqliteSettingsDao());
    }

    public SqliteSaleService(SaleDao saleDao, ProductDao productDao, StockMovementDao stockMovementDao,
                             CustomerDao customerDao, CustomerLedgerDao customerLedgerDao, UserDao userDao,
                             SettingsDao settingsDao) {
        this.saleDao = saleDao;
        this.productDao = productDao;
        this.stockMovementDao = stockMovementDao;
        this.customerDao = customerDao;
        this.customerLedgerDao = customerLedgerDao;
        this.userDao = userDao;
        this.settingsDao = settingsDao;
    }

    @Override
    public Sale createSale(SaleCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("A sale must contain at least one line item.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Idempotency Guard: prevent double-posting on rapid retries
            if (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank()) {
                Optional<Sale> existing = saleDao.findByIdempotencyKey(conn, dto.businessId(), dto.idempotencyKey().trim());
                if (existing.isPresent()) {
                    log.info("Idempotent sale request matched existing invoice [{}]", existing.get().invoiceNumber());
                    return existing.get();
                }
            }

            // 2. Validate customer existence and status if specified
            if (dto.customerId() != null) {
                Optional<Customer> cOpt = customerDao.findById(conn, dto.customerId());
                if (cOpt.isEmpty()) {
                    throw new IllegalArgumentException("Customer not found with id: " + dto.customerId());
                }
                Customer customer = cOpt.get();
                if (customer.businessId() != dto.businessId()) {
                    throw new SecurityException("Customer does not belong to active store context.");
                }
                if (customer.isArchived()) {
                    throw new IllegalStateException("Cannot create sale invoice for an archived customer.");
                }
            }

            String mrpPolicy = settingsDao.get(conn, dto.businessId(), "scm.mrp_policy").orElse("WARN");
            boolean allowNegativeStock = Boolean.parseBoolean(
                    settingsDao.get(conn, dto.businessId(), "scm.allow_negative_stock").orElse("false"));

            // 3. Validate product existence, MRP policy, and stock availability
            for (SaleItemDto item : dto.items()) {
                if (item.quantity() <= 0) {
                    throw new IllegalArgumentException("Item quantity must be greater than zero.");
                }
                Optional<Product> pOpt = productDao.findById(conn, item.productId());
                if (pOpt.isEmpty()) {
                    throw new IllegalArgumentException("Product not found with id: " + item.productId());
                }
                Product p = pOpt.get();
                if (p.businessId() != dto.businessId()) {
                    throw new SecurityException("Product does not belong to active store context.");
                }

                if (p.mrp() != null && p.mrp().isPositive() && item.unitPrice().compareTo(p.mrp()) > 0) {
                    if ("BLOCK".equalsIgnoreCase(mrpPolicy)) {
                        throw new IllegalArgumentException("Selling price (" + item.unitPrice()
                                + ") exceeds configured MRP (" + p.mrp() + ") for " + p.name() + ".");
                    } else if ("WARN".equalsIgnoreCase(mrpPolicy)) {
                        log.warn("MRP Warning: selling {} above MRP ({}) for {}", item.unitPrice(), p.mrp(), p.name());
                    }
                }

                if (!allowNegativeStock) {
                    int availableStock = productDao.getCurrentStock(conn, item.productId());
                    if (availableStock < item.quantity()) {
                        throw new InsufficientStockException(item.productId(), availableStock, item.quantity());
                    }
                }
            }

            // 3. Compute Line Items, Snapshot COGS (Weighted Avg Cost) & Totals
            Money subtotal = Money.ZERO;
            long totalCogsPaisha = 0L;
            List<SaleItem> itemsToPersist = new ArrayList<>(dto.items().size());

            for (SaleItemDto item : dto.items()) {
                Product p = productDao.findById(conn, item.productId()).orElseThrow();
                Money itemDiscount = item.discount() != null ? item.discount() : Money.ZERO;
                Money lineTotal = item.unitPrice().multiply(item.quantity()).subtract(itemDiscount);
                if (lineTotal.isNegative()) {
                    throw new IllegalArgumentException("Line item total cannot be negative.");
                }
                Money unitCost = p.costPrice() != null ? p.costPrice() : Money.ZERO;
                long lineCogsPaisha = Math.multiplyExact(unitCost.paisha(), item.quantity());
                Money lineProfit = lineTotal.subtract(Money.ofPaisha(lineCogsPaisha));

                subtotal = subtotal.add(lineTotal);
                totalCogsPaisha = Math.addExact(totalCogsPaisha, lineCogsPaisha);

                itemsToPersist.add(new SaleItem(
                        null, 0L, item.productId(), item.quantity(),
                        item.unitPrice(), unitCost, itemDiscount, lineTotal, lineProfit, 0
                ));
            }

            Money invoiceDiscount = dto.discount() != null ? dto.discount() : Money.ZERO;
            Money netAmount = subtotal.subtract(invoiceDiscount);
            Money totalCogs = Money.ofPaisha(totalCogsPaisha);
            Money grossProfit = netAmount.subtract(totalCogs);
            if (netAmount.isNegative()) {
                throw new IllegalArgumentException("Invoice net amount cannot be negative.");
            }

            Money paidInput = dto.paidAmount() != null ? dto.paidAmount() : Money.ZERO;
            // Cap paid amount at net invoice amount
            Money paidAmount = paidInput.compareTo(netAmount) > 0 ? netAmount : paidInput;
            Money dueAmount = netAmount.subtract(paidAmount);

            // Walk-in Customer Check: Unregistered customers cannot take dues
            if (dto.customerId() == null && dueAmount.isPositive()) {
                throw new IllegalArgumentException("Credit sale (Baki) requires selecting a registered customer account.");
            }

            String paymentStatus;
            if (dueAmount.isZero()) {
                paymentStatus = "PAID";
            } else if (paidAmount.isZero()) {
                paymentStatus = "BAKI";
            } else {
                paymentStatus = "PARTIAL";
            }

            // 4. Generate Unique Sequential Invoice Number
            String datePrefix = LocalDate.now().format(DATE_PREFIX_FMT);
            int seq = saleDao.getNextInvoiceSequence(conn, dto.businessId(), datePrefix);
            String invoiceNumber = String.format("INV-%s-%04d", datePrefix, seq);

            // 5. Persist Sale Header (with COGS & Gross Profit snapshot)
            Sale sale = new Sale(
                    null,
                    dto.businessId(),
                    invoiceNumber,
                    dto.customerId(),
                    subtotal,
                    invoiceDiscount,
                    netAmount,
                    paidAmount,
                    dueAmount,
                    paymentStatus,
                    dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank() ? dto.idempotencyKey().trim() : null,
                    LocalDateTime.now(),
                    dto.notes(),
                    dto.actingUserId(),
                    false, null, null, null, null,
                    totalCogs,
                    grossProfit
            );

            Sale savedSale = saleDao.save(conn, sale);

            // 6. Persist Sale Line Items & Post Atomic Stock Reductions
            List<SaleItem> boundItems = new ArrayList<>(itemsToPersist.size());
            for (SaleItem item : itemsToPersist) {
                boundItems.add(item.withSaleId(savedSale.id()));

                // Atomic negative stock movement
                StockMovement stockOut = new StockMovement(
                        null,
                        savedSale.businessId(),
                        item.productId(),
                        "SALE_OUT",
                        -item.quantity(),
                        item.unitCost(),
                        "SALE",
                        savedSale.id(),
                        "Sale " + invoiceNumber,
                        dto.actingUserId(),
                        LocalDateTime.now()
                );
                stockMovementDao.save(conn, stockOut);
            }
            saleDao.saveItems(conn, boundItems);

            // 7. Persist Initial Payment if paid upfront
            Long paymentId = null;
            if (paidAmount.isPositive()) {
                String payNumber = String.format("PAY-%s-%04d", datePrefix, seq);
                String payMethod = dto.paymentMethod() != null && !dto.paymentMethod().isBlank() ? dto.paymentMethod() : "CASH";

                String sqlPay = "INSERT INTO payments (business_id, payment_number, party_type, party_id, amount_paisha, " +
                        "payment_date, payment_method, notes, created_by_user_id) VALUES (?, ?, 'CUSTOMER', ?, ?, datetime('now', 'localtime'), ?, ?, ?)";
                try (PreparedStatement psPay = conn.prepareStatement(sqlPay, Statement.RETURN_GENERATED_KEYS)) {
                    psPay.setLong(1, savedSale.businessId());
                    psPay.setString(2, payNumber);
                    psPay.setLong(3, savedSale.customerId() != null ? savedSale.customerId() : 0L);
                    psPay.setLong(4, paidAmount.paisha());
                    psPay.setString(5, payMethod);
                    psPay.setString(6, "Initial payment for " + invoiceNumber);
                    psPay.setLong(7, dto.actingUserId());
                    psPay.executeUpdate();

                    try (ResultSet rs = psPay.getGeneratedKeys()) {
                        if (rs.next()) {
                            paymentId = rs.getLong(1);
                        }
                    }
                }

                if (paymentId != null) {
                    String sqlAlloc = "INSERT INTO payment_allocations (payment_id, sale_id, allocated_amount_paisha) VALUES (?, ?, ?)";
                    try (PreparedStatement psAlloc = conn.prepareStatement(sqlAlloc)) {
                        psAlloc.setLong(1, paymentId);
                        psAlloc.setLong(2, savedSale.id());
                        psAlloc.setLong(3, paidAmount.paisha());
                        psAlloc.executeUpdate();
                    }
                }
            }

            // 8. Post Traceable Customer Ledger Entries (Double-Entry Log)
            if (savedSale.customerId() != null) {
                Money currentDue = customerLedgerDao.getCurrentBalance(conn, savedSale.customerId());

                // Debit: Invoicing increases customer debt
                Money runningAfterSale = currentDue.add(netAmount);
                CustomerLedgerEntry saleEntry = new CustomerLedgerEntry(
                        null,
                        savedSale.businessId(),
                        savedSale.customerId(),
                        LocalDateTime.now(),
                        "SALE_INVOICE",
                        savedSale.id(),
                        netAmount,
                        Money.ZERO,
                        runningAfterSale,
                        "Sale Invoice " + invoiceNumber,
                        null
                );
                customerLedgerDao.save(conn, saleEntry);

                // Credit: Payment collection decreases customer debt
                if (paidAmount.isPositive()) {
                    Money runningAfterPayment = runningAfterSale.subtract(paidAmount);
                    CustomerLedgerEntry payEntry = new CustomerLedgerEntry(
                            null,
                            savedSale.businessId(),
                            savedSale.customerId(),
                            LocalDateTime.now(),
                            "PAYMENT_COLLECTION",
                            paymentId,
                            Money.ZERO,
                            paidAmount,
                            runningAfterPayment,
                            "Payment for " + invoiceNumber,
                            null
                    );
                    customerLedgerDao.save(conn, payEntry);
                }
            }

            log.info("Sale [{}] completed successfully (Net: {}, Paid: {}, Due: {}, Status: {})",
                    savedSale.invoiceNumber(), netAmount, paidAmount, dueAmount, paymentStatus);
            return savedSale;
        });
    }

    @Override
    public void voidSale(long saleId, String reason, long actingUserId) {
        SessionContext.requireAuthenticated();
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("A specific reason is required to void/reverse a sale.");
        }

        TransactionManager.runInTransaction(conn -> {
            Optional<Sale> opt = saleDao.findById(conn, saleId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Sale not found with id: " + saleId);
            }
            Sale sale = opt.get();
            if (sale.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: sale belongs to another business.");
            }
            if (sale.isReversed()) {
                throw new IllegalStateException("Sale [" + sale.invoiceNumber() + "] is already voided/reversed.");
            }

            // 1. Mark sale as reversed in sales table
            LocalDateTime now = LocalDateTime.now();
            saleDao.voidSale(conn, saleId, sale.businessId(), reason.trim(), now);

            // 2. Restore stock items via auditable RETURN_IN movement
            List<SaleItem> items = saleDao.findItemsBySaleId(conn, saleId);
            for (SaleItem item : items) {
                StockMovement restoreMovement = new StockMovement(
                        null,
                        sale.businessId(),
                        item.productId(),
                        "RETURN_IN",
                        item.quantity(), // Positive restores physical stock
                        item.unitCost(),
                        "SALE",
                        sale.id(),
                        "Reversal of " + sale.invoiceNumber() + ": " + reason.trim(),
                        actingUserId,
                        now
                );
                stockMovementDao.save(conn, restoreMovement);
            }

            // 3. Reverse customer dues in customer_ledger
            if (sale.customerId() != null && sale.dueAmount().isPositive()) {
                Money currentBal = customerLedgerDao.getCurrentBalance(conn, sale.customerId());
                Money newBal = currentBal.subtract(sale.dueAmount());

                CustomerLedgerEntry reversalEntry = new CustomerLedgerEntry(
                        null,
                        sale.businessId(),
                        sale.customerId(),
                        now,
                        "INVOICE_REVERSAL",
                        sale.id(),
                        Money.ZERO,
                        sale.dueAmount(), // Credit removes the outstanding due
                        newBal,
                        "Reversal of " + sale.invoiceNumber() + ": " + reason.trim(),
                        null
                );
                customerLedgerDao.save(conn, reversalEntry);
            }

            log.info("Sale [{}] (ID: {}) voided successfully. Stock restored and customer dues reversed.",
                    sale.invoiceNumber(), saleId);
        });
    }

    @Override
    public void updateSaleNotes(long saleId, String notes) {
        SessionContext.requireAuthenticated();
        TransactionManager.runInTransaction(conn -> {
            Sale sale = saleDao.findById(conn, saleId)
                    .orElseThrow(() -> new IllegalArgumentException("Sale not found with id: " + saleId));
            if (sale.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: sale belongs to another business.");
            }
            saleDao.updateNotes(conn, saleId, sale.businessId(), notes);
            log.info("Updated notes for sale [{}] (ID: {})", sale.invoiceNumber(), saleId);
        });
    }

    @Override
    public void deleteVoidedSale(long saleId) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Sale sale = saleDao.findById(conn, saleId)
                    .orElseThrow(() -> new IllegalArgumentException("Sale not found with id: " + saleId));
            if (sale.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: sale belongs to another business.");
            }
            if (!sale.isReversed()) {
                throw new IllegalStateException("Only voided/reversed sales can be deleted. Void this invoice first.");
            }
            if (saleDao.hasActiveLinkedPayments(conn, saleId)) {
                throw new IllegalStateException("Cannot delete invoice while an active linked payment receipt exists. Void linked payment receipt(s) first.");
            }
            boolean deleted = saleDao.deleteVoided(conn, saleId, sale.businessId());
            if (!deleted) {
                throw new IllegalStateException("Unable to delete voided sale [" + sale.invoiceNumber() + "].");
            }
            log.info("Voided sale [{}] (ID: {}) deleted permanently by owner", sale.invoiceNumber(), saleId);
        });
    }

    @Override
    public Sale updateSaleOrder(SaleUpdateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto == null) {
            throw new IllegalArgumentException("Sale update payload is required.");
        }
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Sale existingSale = saleDao.findById(conn, dto.saleId())
                    .orElseThrow(() -> new IllegalArgumentException("Sale not found with id: " + dto.saleId()));
            if (existingSale.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied: sale belongs to another business.");
            }
            if (existingSale.isReversed()) {
                throw new IllegalStateException("Voided invoice [" + existingSale.invoiceNumber() + "] cannot be edited.");
            }
            if (hasProcessedSaleReturns(conn, existingSale.id())) {
                throw new IllegalStateException("Invoice [" + existingSale.invoiceNumber() + "] has processed sales returns and cannot be edited directly.");
            }

            List<Long> singleSalePaymentIds = new ArrayList<>();
            List<Long> multiSalePaymentIds = new ArrayList<>();
            classifyLinkedActivePayments(conn, existingSale.id(), singleSalePaymentIds, multiSalePaymentIds);
            if (!multiSalePaymentIds.isEmpty()) {
                throw new IllegalStateException("Cannot edit [" + existingSale.invoiceNumber()
                        + "] because it is covered by a consolidated multi-invoice payment receipt. Void that receipt in Payments first.");
            }

            // Validate customer
            if (dto.customerId() != null) {
                Customer customer = customerDao.findById(conn, dto.customerId())
                        .orElseThrow(() -> new IllegalArgumentException("Customer not found with id: " + dto.customerId()));
                if (customer.businessId() != dto.businessId()) {
                    throw new SecurityException("Customer does not belong to active store context.");
                }
                if (customer.isArchived()) {
                    throw new IllegalStateException("Cannot assign sale invoice to an archived customer.");
                }
            }

            List<SaleItem> oldItems = saleDao.findItemsBySaleId(conn, existingSale.id());
            java.util.Map<Long, Integer> oldQtyByProduct = new java.util.HashMap<>();
            java.util.Map<Long, Money> oldCostByProduct = new java.util.HashMap<>();
            for (SaleItem oi : oldItems) {
                oldQtyByProduct.merge(oi.productId(), oi.quantity(), Integer::sum);
                oldCostByProduct.putIfAbsent(oi.productId(), oi.unitCost());
            }

            String mrpPolicy = settingsDao.get(conn, dto.businessId(), "scm.mrp_policy").orElse("WARN");
            boolean allowNegativeStock = Boolean.parseBoolean(
                    settingsDao.get(conn, dto.businessId(), "scm.allow_negative_stock").orElse("false"));

            java.util.Map<Long, Integer> requestedQtyByProduct = new java.util.HashMap<>();
            for (SaleItemDto item : dto.items()) {
                if (item.quantity() <= 0) {
                    throw new IllegalArgumentException("Item quantity must be greater than zero.");
                }
                if (item.unitPrice() == null || item.unitPrice().isNegative()) {
                    throw new IllegalArgumentException("Item unit price cannot be negative.");
                }
                Product p = productDao.findById(conn, item.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Product not found with id: " + item.productId()));
                if (p.businessId() != dto.businessId()) {
                    throw new SecurityException("Product does not belong to active store context.");
                }
                if (p.mrp() != null && p.mrp().isPositive() && item.unitPrice().compareTo(p.mrp()) > 0
                        && "BLOCK".equalsIgnoreCase(mrpPolicy)) {
                    throw new IllegalArgumentException("Selling price (" + item.unitPrice()
                            + ") exceeds configured MRP (" + p.mrp() + ") for " + p.name() + ".");
                }
                requestedQtyByProduct.merge(item.productId(), item.quantity(), Integer::sum);
            }

            if (!allowNegativeStock) {
                for (java.util.Map.Entry<Long, Integer> entry : requestedQtyByProduct.entrySet()) {
                    long pid = entry.getKey();
                    int totalRequested = entry.getValue();
                    int currentOnHand = productDao.getCurrentStock(conn, pid);
                    int effectiveAvailable = currentOnHand + oldQtyByProduct.getOrDefault(pid, 0);
                    if (effectiveAvailable < totalRequested) {
                        throw new InsufficientStockException(pid, effectiveAvailable, totalRequested);
                    }
                }
            }

            // Compute edited totals
            Money subtotal = Money.ZERO;
            long totalCogsPaisha = 0L;
            List<SaleItem> editedItems = new ArrayList<>(dto.items().size());
            for (SaleItemDto item : dto.items()) {
                Product p = productDao.findById(conn, item.productId()).orElseThrow();
                Money itemDiscount = item.discount() != null ? item.discount() : Money.ZERO;
                Money lineTotal = item.unitPrice().multiply(item.quantity()).subtract(itemDiscount);
                if (lineTotal.isNegative()) {
                    throw new IllegalArgumentException("Line item total cannot be negative.");
                }
                Money unitCost = oldCostByProduct.getOrDefault(item.productId(),
                        p.costPrice() != null ? p.costPrice() : Money.ZERO);
                long lineCogsPaisha = Math.multiplyExact(unitCost.paisha(), item.quantity());
                Money lineProfit = lineTotal.subtract(Money.ofPaisha(lineCogsPaisha));

                subtotal = subtotal.add(lineTotal);
                totalCogsPaisha = Math.addExact(totalCogsPaisha, lineCogsPaisha);
                editedItems.add(new SaleItem(
                        null, existingSale.id(), item.productId(), item.quantity(),
                        item.unitPrice(), unitCost, itemDiscount, lineTotal, lineProfit, 0
                ));
            }

            Money invoiceDiscount = dto.discount() != null ? dto.discount() : Money.ZERO;
            if (invoiceDiscount.isNegative()) {
                throw new IllegalArgumentException("Invoice discount cannot be negative.");
            }
            Money netAmount = subtotal.subtract(invoiceDiscount);
            if (netAmount.isNegative()) {
                throw new IllegalArgumentException("Discount cannot exceed invoice subtotal (" + subtotal + ").");
            }

            Money paidInput = dto.paidAmount() != null ? dto.paidAmount() : Money.ZERO;
            if (paidInput.isNegative()) {
                throw new IllegalArgumentException("Paid amount cannot be negative.");
            }
            Money paidAmount = paidInput.compareTo(netAmount) > 0 ? netAmount : paidInput;
            Money dueAmount = netAmount.subtract(paidAmount);

            if (dto.customerId() == null && dueAmount.isPositive()) {
                throw new IllegalArgumentException("Credit sale (Baki) requires selecting a registered customer account.");
            }

            String paymentStatus = dueAmount.isZero() ? "PAID" : (paidAmount.isZero() ? "BAKI" : "PARTIAL");
            Money totalCogs = Money.ofPaisha(totalCogsPaisha);
            Money grossProfit = netAmount.subtract(totalCogs);

            // Replace stock movements for this sale atomically
            try (PreparedStatement psDelSm = conn.prepareStatement(
                    "DELETE FROM stock_movements WHERE business_id = ? AND reference_type = 'SALE' AND reference_id = ?")) {
                psDelSm.setLong(1, dto.businessId());
                psDelSm.setLong(2, existingSale.id());
                psDelSm.executeUpdate();
            }

            long actingUser = dto.actingUserId() > 0 ? dto.actingUserId() : existingSale.createdByUserId();
            LocalDateTime now = LocalDateTime.now();
            for (SaleItem item : editedItems) {
                stockMovementDao.save(conn, new StockMovement(
                        null, dto.businessId(), item.productId(), "SALE_OUT",
                        -item.quantity(), item.unitCost(), "SALE", existingSale.id(),
                        "Sale " + existingSale.invoiceNumber() + " (Edited)", actingUser, now
                ));
            }

            Sale updatedSale = new Sale(
                    existingSale.id(), existingSale.businessId(), existingSale.invoiceNumber(),
                    dto.customerId(), subtotal, invoiceDiscount, netAmount, paidAmount, dueAmount,
                    paymentStatus, existingSale.idempotencyKey(), existingSale.invoiceDate(),
                    dto.notes(), existingSale.createdByUserId(),
                    false, null, null, existingSale.createdAt(), now,
                    totalCogs, grossProfit
            );
            saleDao.updateOrderHeader(conn, updatedSale);
            saleDao.replaceItems(conn, existingSale.id(), editedItems);

            // Remove old customer ledger rows tied to this sale & any old single-sale payments
            deleteSingleSaleLedgerRows(conn, dto.businessId(), existingSale.id(), singleSalePaymentIds);

            Long activePaymentId = null;
            String payMethod = dto.paymentMethod() != null && !dto.paymentMethod().isBlank()
                    ? dto.paymentMethod().trim().toUpperCase(java.util.Locale.ROOT) : "CASH";
            if (paidAmount.isPositive()) {
                if (!singleSalePaymentIds.isEmpty()) {
                    activePaymentId = singleSalePaymentIds.get(0);
                    try (PreparedStatement psUpdPay = conn.prepareStatement(
                            "UPDATE payments SET party_id = ?, amount_paisha = ?, payment_method = ?, notes = ? WHERE id = ? AND business_id = ?")) {
                        psUpdPay.setLong(1, dto.customerId() != null ? dto.customerId() : 0L);
                        psUpdPay.setLong(2, paidAmount.paisha());
                        psUpdPay.setString(3, payMethod);
                        psUpdPay.setString(4, "Payment for " + existingSale.invoiceNumber() + " (Edited)");
                        psUpdPay.setLong(5, activePaymentId);
                        psUpdPay.setLong(6, dto.businessId());
                        psUpdPay.executeUpdate();
                    }
                    try (PreparedStatement psUpdAlloc = conn.prepareStatement(
                            "UPDATE payment_allocations SET allocated_amount_paisha = ? WHERE payment_id = ? AND sale_id = ?")) {
                        psUpdAlloc.setLong(1, paidAmount.paisha());
                        psUpdAlloc.setLong(2, activePaymentId);
                        psUpdAlloc.setLong(3, existingSale.id());
                        psUpdAlloc.executeUpdate();
                    }
                    // Clean any redundant extra single-sale allocations if multiple existed
                    for (int idx = 1; idx < singleSalePaymentIds.size(); idx++) {
                        deletePaymentCompletely(conn, dto.businessId(), singleSalePaymentIds.get(idx));
                    }
                } else {
                    String datePrefix = existingSale.invoiceDate().format(DATE_PREFIX_FMT);
                    String payNumber = generateNextPaymentNumber(conn, dto.businessId(), datePrefix);
                    try (PreparedStatement psInsPay = conn.prepareStatement(
                            "INSERT INTO payments (business_id, payment_number, party_type, party_id, amount_paisha, " +
                                    "payment_date, payment_method, notes, created_by_user_id) VALUES (?, ?, 'CUSTOMER', ?, ?, ?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        psInsPay.setLong(1, dto.businessId());
                        psInsPay.setString(2, payNumber);
                        psInsPay.setLong(3, dto.customerId() != null ? dto.customerId() : 0L);
                        psInsPay.setLong(4, paidAmount.paisha());
                        psInsPay.setString(5, existingSale.invoiceDate().toString().replace("T", " "));
                        psInsPay.setString(6, payMethod);
                        psInsPay.setString(7, "Payment for " + existingSale.invoiceNumber() + " (Edited)");
                        psInsPay.setLong(8, actingUser);
                        psInsPay.executeUpdate();
                        try (ResultSet rs = psInsPay.getGeneratedKeys()) {
                            if (rs.next()) activePaymentId = rs.getLong(1);
                        }
                    }
                    if (activePaymentId != null) {
                        try (PreparedStatement psInsAlloc = conn.prepareStatement(
                                "INSERT INTO payment_allocations (payment_id, sale_id, allocated_amount_paisha) VALUES (?, ?, ?)")) {
                            psInsAlloc.setLong(1, activePaymentId);
                            psInsAlloc.setLong(2, existingSale.id());
                            psInsAlloc.setLong(3, paidAmount.paisha());
                            psInsAlloc.executeUpdate();
                        }
                    }
                }
            } else {
                for (Long payId : singleSalePaymentIds) {
                    deletePaymentCompletely(conn, dto.businessId(), payId);
                }
            }

            if (dto.customerId() != null) {
                customerLedgerDao.save(conn, new CustomerLedgerEntry(
                        null, dto.businessId(), dto.customerId(), existingSale.invoiceDate(),
                        "SALE_INVOICE", existingSale.id(), netAmount, Money.ZERO, Money.ZERO,
                        "Sale Invoice " + existingSale.invoiceNumber(), null
                ));
                if (paidAmount.isPositive() && activePaymentId != null) {
                    customerLedgerDao.save(conn, new CustomerLedgerEntry(
                            null, dto.businessId(), dto.customerId(), existingSale.invoiceDate(),
                            "PAYMENT_COLLECTION", activePaymentId, Money.ZERO, paidAmount, Money.ZERO,
                            "Payment for " + existingSale.invoiceNumber(), null
                    ));
                }
            }

            if (existingSale.customerId() != null) {
                recalculateCustomerRunningBalances(conn, existingSale.customerId());
            }
            if (dto.customerId() != null && !dto.customerId().equals(existingSale.customerId())) {
                recalculateCustomerRunningBalances(conn, dto.customerId());
            }

            insertSaleAuditLog(conn, dto.businessId(), actingUser, "UPDATE", existingSale.id(),
                    "Edited POS order " + existingSale.invoiceNumber() + " (Net: " + netAmount + ", Paid: " + paidAmount + ")");

            log.info("Sale [{}] (ID: {}) updated successfully (Net: {}, Paid: {}, Due: {}, Status: {})",
                    existingSale.invoiceNumber(), existingSale.id(), netAmount, paidAmount, dueAmount, paymentStatus);
            return saleDao.findById(conn, existingSale.id()).orElse(updatedSale);
        });
    }

    @Override
    public void deleteSaleOrder(long saleId) {
        SessionContext.requireAuthenticated();
        SessionContext.requireOwner();

        TransactionManager.runInTransaction(conn -> {
            Sale sale = saleDao.findById(conn, saleId)
                    .orElseThrow(() -> new IllegalArgumentException("Sale not found with id: " + saleId));
            long businessId = SessionContext.getRequiredBusinessId();
            if (sale.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied: sale belongs to another business.");
            }
            if (hasProcessedSaleReturns(conn, saleId)) {
                throw new IllegalStateException("Cannot delete invoice [" + sale.invoiceNumber()
                        + "] while processed sales returns exist for it.");
            }

            List<Long> singleSalePaymentIds = new ArrayList<>();
            List<Long> multiSalePaymentIds = new ArrayList<>();
            classifyLinkedActivePayments(conn, saleId, singleSalePaymentIds, multiSalePaymentIds);
            if (!multiSalePaymentIds.isEmpty()) {
                throw new IllegalStateException("Cannot delete [" + sale.invoiceNumber()
                        + "] while an active multi-invoice payment receipt allocates to it. Void that payment receipt first.");
            }

            deleteSingleSaleLedgerRows(conn, businessId, saleId, singleSalePaymentIds);
            for (Long payId : singleSalePaymentIds) {
                deletePaymentCompletely(conn, businessId, payId);
            }

            try (PreparedStatement psDelAlloc = conn.prepareStatement("DELETE FROM payment_allocations WHERE sale_id = ?")) {
                psDelAlloc.setLong(1, saleId);
                psDelAlloc.executeUpdate();
            }
            try (PreparedStatement psDelSm = conn.prepareStatement(
                    "DELETE FROM stock_movements WHERE business_id = ? AND reference_type = 'SALE' AND reference_id = ?")) {
                psDelSm.setLong(1, businessId);
                psDelSm.setLong(2, saleId);
                psDelSm.executeUpdate();
            }

            boolean deleted = saleDao.deleteSaleRecord(conn, saleId, businessId);
            if (!deleted) {
                throw new IllegalStateException("Unable to delete sale order [" + sale.invoiceNumber() + "].");
            }

            if (sale.customerId() != null) {
                recalculateCustomerRunningBalances(conn, sale.customerId());
            }
            long actingUser = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : sale.createdByUserId();
            insertSaleAuditLog(conn, businessId, actingUser, "DELETE", saleId,
                    "Deleted POS order " + sale.invoiceNumber() + " permanently (stock and balances restored)");

            log.info("POS sale order [{}] (ID: {}) deleted permanently by owner", sale.invoiceNumber(), saleId);
        });
    }

    @Override
    public Optional<String> findPrimaryPaymentMethod(long saleId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            return saleDao.findPrimaryPaymentMethodBySaleId(conn, saleId);
        } catch (SQLException e) {
            log.warn("Could not read payment method for sale {}: {}", saleId, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean hasProcessedSaleReturns(Connection conn, long saleId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sale_returns WHERE sale_id = ?")) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private void classifyLinkedActivePayments(Connection conn, long saleId,
                                              List<Long> singleSalePaymentIds,
                                              List<Long> multiSalePaymentIds) throws SQLException {
        String sql = "SELECT pa.payment_id, " +
                "(SELECT COUNT(DISTINCT pa2.sale_id) FROM payment_allocations pa2 WHERE pa2.payment_id = pa.payment_id) AS sale_cnt " +
                "FROM payment_allocations pa JOIN payments py ON pa.payment_id = py.id " +
                "WHERE pa.sale_id = ? AND py.is_reversed = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long payId = rs.getLong("payment_id");
                    int saleCnt = rs.getInt("sale_cnt");
                    if (saleCnt > 1) {
                        if (!multiSalePaymentIds.contains(payId)) multiSalePaymentIds.add(payId);
                    } else {
                        if (!singleSalePaymentIds.contains(payId)) singleSalePaymentIds.add(payId);
                    }
                }
            }
        }
    }

    private void deleteSingleSaleLedgerRows(Connection conn, long businessId, long saleId,
                                            List<Long> singleSalePaymentIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM customer_ledger WHERE business_id = ? AND reference_id = ? AND entry_type IN ('SALE_INVOICE', 'INVOICE_REVERSAL')")) {
            ps.setLong(1, businessId);
            ps.setLong(2, saleId);
            ps.executeUpdate();
        }
        for (Long payId : singleSalePaymentIds) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM customer_ledger WHERE business_id = ? AND reference_id = ? AND entry_type = 'PAYMENT_COLLECTION'")) {
                ps.setLong(1, businessId);
                ps.setLong(2, payId);
                ps.executeUpdate();
            }
        }
    }

    private void deletePaymentCompletely(Connection conn, long businessId, long paymentId) throws SQLException {
        try (PreparedStatement psAlloc = conn.prepareStatement("DELETE FROM payment_allocations WHERE payment_id = ?")) {
            psAlloc.setLong(1, paymentId);
            psAlloc.executeUpdate();
        }
        try (PreparedStatement psPay = conn.prepareStatement("DELETE FROM payments WHERE id = ? AND business_id = ?")) {
            psPay.setLong(1, paymentId);
            psPay.setLong(2, businessId);
            psPay.executeUpdate();
        }
    }

    private String generateNextPaymentNumber(Connection conn, long businessId, String datePrefix) throws SQLException {
        String pattern = "PAY-" + datePrefix + "-%";
        int maxSeq = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT payment_number FROM payments WHERE business_id = ? AND payment_number LIKE ?")) {
            ps.setLong(1, businessId);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String num = rs.getString(1);
                    if (num != null) {
                        int idx = num.lastIndexOf('-');
                        if (idx >= 0 && idx + 1 < num.length()) {
                            try {
                                maxSeq = Math.max(maxSeq, Integer.parseInt(num.substring(idx + 1)));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        return String.format("PAY-%s-%04d", datePrefix, maxSeq + 1);
    }

    private void recalculateCustomerRunningBalances(Connection conn, long customerId) throws SQLException {
        List<CustomerLedgerEntry> entries = customerLedgerDao.findByCustomerId(conn, customerId);
        long runningPaisha = 0L;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE customer_ledger SET running_balance_paisha = ? WHERE id = ?")) {
            for (CustomerLedgerEntry e : entries) {
                long debit = e.debit() != null ? e.debit().paisha() : 0L;
                long credit = e.credit() != null ? e.credit().paisha() : 0L;
                runningPaisha = runningPaisha + debit - credit;
                ps.setLong(1, runningPaisha);
                ps.setLong(2, e.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertSaleAuditLog(Connection conn, long businessId, long userId,
                                    String actionType, long saleId, String summary) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_logs (business_id, user_id, action_type, entity_name, entity_id, change_summary) VALUES (?, ?, ?, 'SALE', ?, ?)")) {
            ps.setLong(1, businessId);
            ps.setLong(2, userId);
            ps.setString(3, actionType);
            ps.setLong(4, saleId);
            ps.setString(5, summary);
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<SaleDetailDto> getSaleDetail(long saleId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Sale> saleOpt = saleDao.findById(conn, saleId);
            if (saleOpt.isEmpty()) return Optional.empty();
            Sale sale = saleOpt.get();
            if (sale.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: sale belongs to another business.");
            }

            String custName = "Walk-in Cash Customer";
            String custPhone = "—";
            String custAddr = "—";
            if (sale.customerId() != null) {
                Optional<Customer> cOpt = customerDao.findById(conn, sale.customerId());
                if (cOpt.isPresent()) {
                    Customer c = cOpt.get();
                    custName = c.name();
                    if (c.phone() != null) custPhone = c.phone();
                    if (c.address() != null) custAddr = c.address();
                }
            }

            String cashierName = "System";
            Optional<User> uOpt = userDao.findById(conn, sale.createdByUserId());
            if (uOpt.isPresent()) {
                cashierName = uOpt.get().fullName();
            }

            List<SaleItem> rawItems = saleDao.findItemsBySaleId(conn, saleId);
            List<SaleItemDto> dtos = new ArrayList<>(rawItems.size());
            for (SaleItem item : rawItems) {
                String pName = "Product #" + item.productId();
                Optional<Product> pOpt = productDao.findById(conn, item.productId());
                if (pOpt.isPresent()) {
                    pName = pOpt.get().name();
                }
                dtos.add(new SaleItemDto(
                        item.productId(),
                        pName,
                        item.quantity(),
                        item.unitPrice(),
                        item.unitCost(),
                        item.discount(),
                        item.lineTotal()
                ));
            }

            return Optional.of(new SaleDetailDto(
                    sale, custName, custPhone, custAddr, dtos, cashierName
            ));
        } catch (SQLException e) {
            log.error("Failed to load sale detail: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to load sale details", e);
        }
    }

    @Override
    public List<SaleListItemDto> searchSales(long businessId, String query, Long customerId,
                                            String paymentStatus, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return saleDao.searchSales(conn, businessId, query, customerId, paymentStatus, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to search sales: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve sales records", e);
        }
    }

    @Override
    public SaleReturn createSaleReturn(SaleReturnCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        if (dto.reason() == null || dto.reason().trim().isBlank()) {
            throw new IllegalArgumentException("Return reason is required.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("Select at least one line item to return.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Sale sale = saleDao.findById(conn, dto.saleId())
                    .orElseThrow(() -> new IllegalArgumentException("Sale not found: " + dto.saleId()));
            if (sale.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied.");
            }
            if (sale.isReversed()) {
                throw new IllegalStateException("Cannot process partial return on a voided invoice.");
            }

            List<SaleItem> existingItems = saleDao.findItemsBySaleId(conn, sale.id());
            java.util.Map<Long, SaleItem> itemMap = new java.util.HashMap<>();
            for (SaleItem si : existingItems) {
                itemMap.put(si.id(), si);
            }

            long totalRefundPaisha = 0L;
            long totalCogsRestoredPaisha = 0L;
            LocalDateTime now = LocalDateTime.now();

            record PreparedReturn(SaleItem item, int qty, Money refund, Money cogsRestored) {}
            List<PreparedReturn> prepared = new ArrayList<>();

            for (SaleReturnItemCreateDto req : dto.items()) {
                SaleItem si = itemMap.get(req.saleItemId());
                if (si == null) {
                    throw new IllegalArgumentException("Invalid sale item ID: " + req.saleItemId());
                }
                int returnable = si.quantity() - si.returnedQty();
                if (req.returnedQty() <= 0 || req.returnedQty() > returnable) {
                    throw new IllegalArgumentException("Return qty (" + req.returnedQty()
                            + ") exceeds remaining returnable qty (" + returnable + ").");
                }
                // Proportional refund preserving historical sale price & line discount
                long refundPaisha = (req.returnedQty() == si.quantity())
                        ? si.lineTotal().paisha()
                        : java.math.BigDecimal.valueOf(si.lineTotal().paisha())
                            .multiply(java.math.BigDecimal.valueOf(req.returnedQty()))
                            .divide(java.math.BigDecimal.valueOf(si.quantity()), 0, java.math.RoundingMode.HALF_UP)
                            .longValueExact();
                long cogsPaisha = Math.multiplyExact(si.unitCost().paisha(), req.returnedQty());

                totalRefundPaisha = Math.addExact(totalRefundPaisha, refundPaisha);
                totalCogsRestoredPaisha = Math.addExact(totalCogsRestoredPaisha, cogsPaisha);
                prepared.add(new PreparedReturn(si, req.returnedQty(), Money.ofPaisha(refundPaisha), Money.ofPaisha(cogsPaisha)));
            }

            String datePrefix = now.format(DATE_PREFIX_FMT);
            int seq = 1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) + 1 FROM sale_returns WHERE business_id = ? AND return_number LIKE ?")) {
                ps.setLong(1, dto.businessId());
                ps.setString(2, "SRN-" + datePrefix + "-%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) seq = rs.getInt(1);
                }
            }
            String returnNo = String.format("SRN-%s-%04d", datePrefix, seq);
            long actingUser = dto.actingUserId() > 0 ? dto.actingUserId()
                    : (SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : sale.createdByUserId());

            long returnId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sale_returns (business_id, return_number, sale_id, customer_id, return_date, " +
                            "total_refund_paisha, total_cogs_restored_paisha, reason, created_by_user_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, dto.businessId());
                ps.setString(2, returnNo);
                ps.setLong(3, sale.id());
                if (sale.customerId() != null) ps.setLong(4, sale.customerId()); else ps.setNull(4, Types.INTEGER);
                ps.setString(5, now.toString().replace("T", " "));
                ps.setLong(6, totalRefundPaisha);
                ps.setLong(7, totalCogsRestoredPaisha);
                ps.setString(8, dto.reason().trim());
                ps.setLong(9, actingUser);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    returnId = rs.getLong(1);
                }
            }

            try (PreparedStatement psItem = conn.prepareStatement(
                    "INSERT INTO sale_return_items (sale_return_id, sale_item_id, product_id, returned_qty, " +
                            "unit_price_paisha, unit_cost_paisha, line_refund_paisha, line_cogs_restored_paisha) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement psUpd = conn.prepareStatement(
                         "UPDATE sale_items SET returned_qty = returned_qty + ? WHERE id = ?")) {
                for (PreparedReturn pr : prepared) {
                    psItem.setLong(1, returnId);
                    psItem.setLong(2, pr.item().id());
                    psItem.setLong(3, pr.item().productId());
                    psItem.setInt(4, pr.qty());
                    psItem.setLong(5, pr.item().unitPrice().paisha());
                    psItem.setLong(6, pr.item().unitCost().paisha());
                    psItem.setLong(7, pr.refund().paisha());
                    psItem.setLong(8, pr.cogsRestored().paisha());
                    psItem.addBatch();

                    psUpd.setInt(1, pr.qty());
                    psUpd.setLong(2, pr.item().id());
                    psUpd.addBatch();

                    stockMovementDao.save(conn, new StockMovement(
                            null, dto.businessId(), pr.item().productId(), "RETURN_IN",
                            pr.qty(), pr.item().unitCost(), "RETURN", returnId,
                            "Sale Return " + returnNo + " (" + sale.invoiceNumber() + "): " + dto.reason().trim(),
                            actingUser, now, "SALE_RETURN", returnNo, null
                    ));
                }
                psItem.executeBatch();
                psUpd.executeBatch();
            }

            if (sale.customerId() != null && sale.dueAmount().isPositive() && totalRefundPaisha > 0L) {
                Money creditAdjust = Money.ofPaisha(Math.min(sale.dueAmount().paisha(), totalRefundPaisha));
                Money newSaleDue = sale.dueAmount().subtract(creditAdjust);
                String newPayStatus = newSaleDue.isZero() ? "PAID" : (sale.paidAmount().isPositive() ? "PARTIAL" : "BAKI");
                saleDao.updatePaymentStatus(conn, sale.id(), sale.paidAmount(), newSaleDue, newPayStatus);

                Money curBal = customerLedgerDao.getCurrentBalance(conn, sale.customerId());
                Money nextBal = curBal.subtract(creditAdjust);
                customerLedgerDao.save(conn, new CustomerLedgerEntry(
                        null, dto.businessId(), sale.customerId(), now, "INVOICE_REVERSAL", returnId,
                        Money.ZERO, creditAdjust, nextBal,
                        "Credit for Sale Return " + returnNo + " (" + sale.invoiceNumber() + ")", null
                ));
            }

            return new SaleReturn(returnId, dto.businessId(), returnNo, sale.id(), sale.customerId(), now,
                    Money.ofPaisha(totalRefundPaisha), Money.ofPaisha(totalCogsRestoredPaisha),
                    dto.reason().trim(), actingUser, now);
        });
    }
}


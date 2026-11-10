package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.GrnStatus;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.GrnService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.util.PricingCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SqliteGrnService implements GrnService {

    private static final Logger log = LoggerFactory.getLogger(SqliteGrnService.class);
    private static final DateTimeFormatter DATE_PREFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GrnDao grnDao;
    private final PurchaseOrderDao poDao;
    private final SupplierDao supplierDao;
    private final SupplierLedgerDao supplierLedgerDao;
    private final ProductDao productDao;
    private final ProductSupplierDao productSupplierDao;
    private final StockMovementDao stockMovementDao;
    private final PurchaseReturnDao returnDao;
    private final SettingsDao settingsDao;

    public SqliteGrnService() {
        this(new SqliteGrnDao(), new SqlitePurchaseOrderDao(), new SqliteSupplierDao(),
                new SqliteSupplierLedgerDao(), new SqliteProductDao(), new SqliteProductSupplierDao(),
                new SqliteStockMovementDao(), new SqlitePurchaseReturnDao(), new SqliteSettingsDao());
    }

    public SqliteGrnService(GrnDao grnDao, PurchaseOrderDao poDao, SupplierDao supplierDao,
                            SupplierLedgerDao supplierLedgerDao, ProductDao productDao,
                            ProductSupplierDao productSupplierDao, StockMovementDao stockMovementDao,
                            PurchaseReturnDao returnDao, SettingsDao settingsDao) {
        this.grnDao = grnDao;
        this.poDao = poDao;
        this.supplierDao = supplierDao;
        this.supplierLedgerDao = supplierLedgerDao;
        this.productDao = productDao;
        this.productSupplierDao = productSupplierDao;
        this.stockMovementDao = stockMovementDao;
        this.returnDao = returnDao;
        this.settingsDao = settingsDao;
    }

    @Override
    public GoodsReceiptNote postGoodsReceipt(GrnCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("At least one item line is required to post a Goods Receipt Note.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // 1. Idempotency guard against duplicate GRN posting
            if (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank()) {
                Optional<GoodsReceiptNote> existing = grnDao.findByIdempotencyKey(conn, dto.businessId(), dto.idempotencyKey());
                if (existing.isPresent()) {
                    log.info("Idempotent GRN matched existing [{}]", existing.get().grnNumber());
                    return existing.get();
                }
            }

            // 2. Settings check for Direct GRN vs Over-Receipt
            boolean allowDirectGrn = Boolean.parseBoolean(
                    settingsDao.get(conn, dto.businessId(), "scm.allow_direct_grn").orElse("true"));
            boolean allowOverReceipt = Boolean.parseBoolean(
                    settingsDao.get(conn, dto.businessId(), "scm.allow_over_receipt").orElse("false"));

            Supplier supplier = supplierDao.findById(conn, dto.supplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + dto.supplierId()));
            if (supplier.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another store.");
            }

            PurchaseOrder linkedPo = null;
            Map<Long, PurchaseOrderItem> poItemMap = new HashMap<>();
            if (dto.purchaseOrderId() != null) {
                linkedPo = poDao.findById(conn, dto.purchaseOrderId())
                        .orElseThrow(() -> new IllegalArgumentException("Purchase Order not found: " + dto.purchaseOrderId()));
                if (linkedPo.businessId() != dto.businessId()) {
                    throw new SecurityException("Cross-business access denied: PO belongs to another store.");
                }
                if (linkedPo.supplierId() != dto.supplierId()) {
                    throw new IllegalArgumentException("GRN supplier does not match the linked Purchase Order supplier.");
                }
                if (!linkedPo.status().canReceiveGoods()) {
                    throw new IllegalStateException("Cannot receive goods against PO [" + linkedPo.poNumber()
                            + "] in status " + linkedPo.status());
                }
                for (PurchaseOrderItem poi : poDao.findItemsByOrderId(conn, linkedPo.id())) {
                    poItemMap.put(poi.id(), poi);
                }
            } else if (!allowDirectGrn) {
                throw new IllegalStateException("Direct GRN without a Purchase Order is disabled in Store Settings.");
            }

            // 3. Validate lines & calculate accepted subtotals
            List<PreparedGrnLine> prepared = new ArrayList<>();
            long acceptedSubtotalPaisha = 0L;

            for (GrnItemCreateDto line : dto.items()) {
                Product product = productDao.findById(conn, line.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Product not found: " + line.productId()));
                if (product.businessId() != dto.businessId()) {
                    throw new SecurityException("Cross-business access denied: product belongs to another store.");
                }
                if (line.receivedQty() < 0 || line.acceptedQty() < 0 || line.rejectedQty() < 0 || line.damagedQty() < 0) {
                    throw new IllegalArgumentException("Quantities cannot be negative for " + product.name());
                }
                if (line.acceptedQty() + line.rejectedQty() + line.damagedQty() != line.receivedQty()) {
                    throw new IllegalArgumentException("Received qty (" + line.receivedQty()
                            + ") must equal Accepted (" + line.acceptedQty() + ") + Rejected (" + line.rejectedQty()
                            + ") + Damaged (" + line.damagedQty() + ") for " + product.name());
                }

                PurchaseOrderItem poItem = null;
                int orderedQty = 0;
                if (line.poItemId() != null) {
                    if (linkedPo == null) {
                        throw new IllegalArgumentException("poItemId provided without purchaseOrderId.");
                    }
                    poItem = poItemMap.get(line.poItemId());
                    if (poItem == null || poItem.productId() != product.id()) {
                        throw new IllegalArgumentException("Invalid PO line item mapping for " + product.name());
                    }
                    orderedQty = poItem.orderedQty();
                    if (!allowOverReceipt && poItem.receivedQty() + line.acceptedQty() > poItem.orderedQty()) {
                        throw new IllegalArgumentException("Over-receipt prevented for " + product.name()
                                + ": ordered " + poItem.orderedQty() + ", already received " + poItem.receivedQty()
                                + ", attempting to accept " + line.acceptedQty());
                    }
                }

                int packSize = line.packSize() >= 1 ? line.packSize()
                        : (poItem != null ? poItem.packSize() : Math.max(1, product.packSize()));
                String purUnit = line.purchaseUnit() != null && !line.purchaseUnit().isBlank()
                        ? line.purchaseUnit().trim()
                        : (poItem != null ? poItem.purchaseUnit() : product.purchaseUnit());
                Money tp = line.unitTradePrice() != null ? line.unitTradePrice()
                        : (poItem != null ? poItem.tradePrice() : product.tradePrice());
                if (tp.isNegative()) {
                    throw new IllegalArgumentException("Trade price cannot be negative for " + product.name());
                }
                Money disc = line.discount() != null ? line.discount() : Money.ZERO;
                Money grossAccepted = tp.multiply(line.acceptedQty());
                if (disc.isNegative() || disc.compareTo(grossAccepted) > 0) {
                    throw new IllegalArgumentException("Invalid line discount for " + product.name());
                }
                long netLinePaisha = grossAccepted.subtract(disc).paisha();
                acceptedSubtotalPaisha = Math.addExact(acceptedSubtotalPaisha, netLinePaisha);
                prepared.add(new PreparedGrnLine(line, product, poItem, orderedQty, purUnit, packSize, tp, disc, netLinePaisha));
            }

            Money subtotal = Money.ofPaisha(acceptedSubtotalPaisha);
            Money headerDisc = dto.headerDiscount() != null ? dto.headerDiscount() : Money.ZERO;
            Money freight = dto.freightAndOtherCosts() != null ? dto.freightAndOtherCosts() : Money.ZERO;
            if (headerDisc.isNegative() || freight.isNegative()) {
                throw new IllegalArgumentException("Header discount and freight costs cannot be negative.");
            }
            if (headerDisc.compareTo(subtotal) > 0) {
                throw new IllegalArgumentException("Header discount cannot exceed accepted goods subtotal.");
            }
            Money totalCost = subtotal.subtract(headerDisc).add(freight);

            // Proportional allocation of (freight - headerDisc) across accepted lines so SUM(line_total) == totalCost
            long netAdjustmentPaisha = freight.paisha() - headerDisc.paisha();
            long[] allocatedExtra = allocateProportionalAdjustment(prepared, acceptedSubtotalPaisha, netAdjustmentPaisha);

            // 4. Generate GRN Number & Save Header
            LocalDateTime now = LocalDateTime.now();
            String datePrefix = now.format(DATE_PREFIX_FMT);
            int seq = grnDao.getNextGrnSequence(conn, dto.businessId(), datePrefix);
            String grnNumber = String.format("GRN-%s-%04d", datePrefix, seq);
            long actingUser = dto.actingUserId() > 0 ? dto.actingUserId()
                    : (SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L);

            GoodsReceiptNote header = new GoodsReceiptNote(
                    null, dto.businessId(), grnNumber, dto.purchaseOrderId(), dto.supplierId(),
                    now, GrnStatus.POSTED, subtotal, headerDisc, freight, totalCost,
                    dto.challanReference(), dto.notes(),
                    dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank() ? dto.idempotencyKey().trim() : null,
                    actingUser, now, null, null, null, null, null
            );
            GoodsReceiptNote savedGrn = grnDao.saveHeader(conn, header);

            // 5. Build GRN Items and persist
            List<GoodsReceiptItem> grnItemsToSave = new ArrayList<>(prepared.size());
            for (int i = 0; i < prepared.size(); i++) {
                PreparedGrnLine p = prepared.get(i);
                int acceptedBaseQty = Math.multiplyExact(p.line.acceptedQty(), p.packSize);
                long extra = allocatedExtra[i];
                long finalLineTotal = Math.max(0L, p.netLinePaisha + extra);
                Money effectiveBaseCost = PricingCalculator.calculateEffectiveBaseUnitCost(
                        p.line.acceptedQty(), p.packSize, Money.ofPaisha(finalLineTotal));

                grnItemsToSave.add(new GoodsReceiptItem(
                        null, savedGrn.id(), p.line.poItemId(), p.product.id(),
                        p.orderedQty, p.line.receivedQty(), p.line.acceptedQty(),
                        p.line.rejectedQty(), p.line.damagedQty(),
                        p.purchaseUnit, p.packSize, acceptedBaseQty,
                        p.tradePrice, p.lineDiscount, Money.ofPaisha(Math.max(0L, extra)),
                        effectiveBaseCost, Money.ofPaisha(finalLineTotal), p.line.batchOrRemark()
                ));
            }
            List<GoodsReceiptItem> savedItems = grnDao.saveItems(conn, savedGrn.id(), grnItemsToSave);

            // 6. Atomic Stock Posting + Moving Weighted Average Cost + Cost History + Supplier Cost Telemetry + PO Progress
            for (int i = 0; i < savedItems.size(); i++) {
                GoodsReceiptItem gi = savedItems.get(i);
                PreparedGrnLine prep = prepared.get(i);

                if (gi.acceptedBaseQty() > 0) {
                    int existingQty = productDao.getCurrentStock(conn, gi.productId());
                    Product latestProduct = productDao.findById(conn, gi.productId()).orElse(prep.product);
                    Money prevAvgCost = latestProduct.costPrice();

                    long safeExistingQty = Math.max(0L, existingQty);
                    long existingValuePaisha = safeExistingQty * prevAvgCost.paisha();
                    long totalCombinedQty = safeExistingQty + gi.acceptedBaseQty();
                    long newAvgPaisha = BigDecimal.valueOf(existingValuePaisha + gi.lineTotal().paisha())
                            .divide(BigDecimal.valueOf(totalCombinedQty), 0, RoundingMode.HALF_UP)
                            .longValueExact();
                    Money newAvgCost = Money.ofPaisha(newAvgPaisha);

                    productDao.updateCostAndLastPurchase(conn, gi.productId(), newAvgCost, gi.effectiveBaseUnitCost());

                    grnDao.saveCostHistory(conn, new InventoryCostHistory(
                            null, dto.businessId(), gi.productId(), savedGrn.id(), gi.id(),
                            existingQty, prevAvgCost, gi.acceptedBaseQty(), gi.effectiveBaseUnitCost(),
                            existingQty + gi.acceptedBaseQty(), newAvgCost, now, actingUser,
                            "GRN " + savedGrn.grnNumber() + " weighted average update"
                    ));

                    // Update product_suppliers telemetry cleanly (only for THIS supplier!)
                    productSupplierDao.updateLastPurchase(conn, dto.businessId(), gi.productId(),
                            dto.supplierId(), gi.unitTradePrice(), now);

                    // Immutable stock ledger inward entry
                    StockMovement stockIn = new StockMovement(
                            null, dto.businessId(), gi.productId(), "PURCHASE_IN",
                            gi.acceptedBaseQty(), gi.effectiveBaseUnitCost(),
                            "PURCHASE", savedGrn.id(),
                            "GRN Receipt " + savedGrn.grnNumber()
                                    + (gi.packSize() > 1 ? " (" + gi.acceptedQty() + " " + gi.purchaseUnit() + " x" + gi.packSize() + ")" : ""),
                            actingUser, now, "GRN", savedGrn.grnNumber(), savedGrn.id()
                    );
                    stockMovementDao.save(conn, stockIn);
                }

                if (gi.poItemId() != null && gi.acceptedQty() > 0) {
                    poDao.addItemReceivedQty(conn, gi.poItemId(), gi.acceptedQty());
                }
            }

            // 7. Recompute Linked PO Status
            if (linkedPo != null) {
                recomputePoStatus(conn, linkedPo.id(), dto.businessId());
            }

            log.info("GRN [{}] posted atomically (Total Cost: {}, Supplier: {}, PO: {})",
                    savedGrn.grnNumber(), totalCost, supplier.name(), linkedPo != null ? linkedPo.poNumber() : "DIRECT");
            return savedGrn;
        });
    }

    @Override
    public void reverseGrn(long grnId, String reason, long actingUserId) {
        SessionContext.requireOwner();
        long businessId = SessionContext.getRequiredBusinessId();
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("A clear reason is required to reverse a posted GRN.");
        }

        TransactionManager.runInTransaction(conn -> {
            GoodsReceiptNote grn = grnDao.findById(conn, grnId)
                    .orElseThrow(() -> new IllegalArgumentException("GRN not found: " + grnId));
            if (grn.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            if (grn.status() != GrnStatus.POSTED) {
                throw new IllegalStateException("Only POSTED GRNs can be reversed. Current status: " + grn.status());
            }

            if (hasActiveSupplierInvoiceLink(conn, grnId)) {
                throw new IllegalStateException("Cannot reverse GRN [" + grn.grnNumber()
                        + "] because an active Supplier Invoice is linked. Void the supplier invoice first.");
            }

            LocalDateTime now = LocalDateTime.now();
            List<GoodsReceiptItem> items = grnDao.findItemsByGrnId(conn, grnId);
            for (GoodsReceiptItem item : items) {
                if (item.acceptedBaseQty() > 0) {
                    int currentStock = productDao.getCurrentStock(conn, item.productId());
                    if (currentStock < item.acceptedBaseQty()) {
                        Product p = productDao.findById(conn, item.productId()).orElseThrow();
                        throw new IllegalStateException("Cannot reverse GRN [" + grn.grnNumber() + "] because '"
                                + p.name() + "' only has " + currentStock + " units left (need " + item.acceptedBaseQty() + ").");
                    }
                    stockMovementDao.save(conn, new StockMovement(
                            null, businessId, item.productId(), "RETURN_OUT",
                            -item.acceptedBaseQty(), item.effectiveBaseUnitCost(),
                            "RETURN", grn.id(),
                            "Reversal of GRN " + grn.grnNumber() + ": " + reason.trim(),
                            actingUserId, now, "GRN_REVERSAL", grn.grnNumber(), grn.id()
                    ));
                }
                if (item.poItemId() != null && item.acceptedQty() > 0) {
                    poDao.addItemReceivedQty(conn, item.poItemId(), -item.acceptedQty());
                }
            }

            grnDao.markReversed(conn, grnId, businessId, actingUserId, reason.trim(), now);
            if (grn.purchaseOrderId() != null) {
                recomputePoStatus(conn, grn.purchaseOrderId(), businessId);
            }
        });
    }

    @Override
    public Optional<GrnDetailDto> getGrnDetail(long grnId) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<GoodsReceiptNote> opt = grnDao.findById(conn, grnId);
            if (opt.isEmpty()) return Optional.empty();
            GoodsReceiptNote grn = opt.get();
            if (grn.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            Supplier sup = supplierDao.findById(conn, grn.supplierId()).orElse(null);
            String poNumber = null;
            if (grn.purchaseOrderId() != null) {
                poNumber = poDao.findById(conn, grn.purchaseOrderId()).map(PurchaseOrder::poNumber).orElse(null);
            }
            List<GrnItemViewDto> items = grnDao.findItemViewsByGrnId(conn, grnId);
            return Optional.of(new GrnDetailDto(grn, poNumber, sup, items));
        } catch (SQLException e) {
            throw new DatabaseException("Failed to fetch GRN details", e);
        }
    }

    @Override
    public List<GrnListItemDto> searchGrns(long businessId, String query, Long supplierId, Long poId,
                                           GrnStatus status, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return grnDao.searchGrns(conn, businessId, query, supplierId, poId, status, startDate, endDate);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to search GRNs", e);
        }
    }

    @Override
    public List<InventoryCostHistory> getProductCostHistory(long productId) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            return grnDao.findCostHistoryByProduct(conn, businessId, productId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load product cost history", e);
        }
    }

    @Override
    public PurchaseReturn createPurchaseReturn(PurchaseReturnCreateDto dto) {
        SessionContext.requireOwner();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        if (dto.reason() == null || dto.reason().trim().isBlank()) {
            throw new IllegalArgumentException("Return reason is required.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("At least one product is required for a Supplier Return.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Supplier sup = supplierDao.findById(conn, dto.supplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + dto.supplierId()));
            if (sup.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied.");
            }

            long totalReturnPaisha = 0L;
            List<PurchaseReturnItem> returnItems = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();

            for (PurchaseReturnItemCreateDto line : dto.items()) {
                Product p = productDao.findById(conn, line.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Product not found: " + line.productId()));
                if (p.businessId() != dto.businessId()) {
                    throw new SecurityException("Cross-business access denied.");
                }
                if (line.returnBaseQty() <= 0) {
                    throw new IllegalArgumentException("Return quantity must be positive for " + p.name());
                }
                int available = productDao.getCurrentStock(conn, p.id());
                if (available < line.returnBaseQty()) {
                    throw new IllegalStateException("Insufficient stock to return '" + p.name() + "': available "
                            + available + ", return requested " + line.returnBaseQty());
                }
                Money unitCost = line.unitCost() != null ? line.unitCost() : p.costPrice();
                Money lineTotal = unitCost.multiply(line.returnBaseQty());
                totalReturnPaisha = Math.addExact(totalReturnPaisha, lineTotal.paisha());

                returnItems.add(new PurchaseReturnItem(
                        null, 0L, line.grnItemId(), p.id(), line.returnBaseQty(),
                        unitCost, lineTotal, line.notes()
                ));
            }

            String datePrefix = now.format(DATE_PREFIX_FMT);
            int seq = returnDao.getNextSequence(conn, dto.businessId(), datePrefix);
            String returnNumber = String.format("PRN-%s-%04d", datePrefix, seq);
            long actingUser = dto.actingUserId() > 0 ? dto.actingUserId() : SessionContext.getCurrentUser().id();

            PurchaseReturn saved = returnDao.saveHeader(conn, new PurchaseReturn(
                    null, dto.businessId(), returnNumber, dto.supplierId(), dto.grnId(),
                    now, Money.ofPaisha(totalReturnPaisha), dto.reason().trim(), "POSTED", actingUser, null
            ));
            List<PurchaseReturnItem> toSave = returnItems.stream().map(i -> i.withReturnId(saved.id())).toList();
            returnDao.saveItems(conn, saved.id(), toSave);

            for (PurchaseReturnItem item : toSave) {
                stockMovementDao.save(conn, new StockMovement(
                        null, dto.businessId(), item.productId(), "RETURN_OUT",
                        -item.returnBaseQty(), item.unitCost(),
                        "RETURN", saved.id(),
                        "Supplier Return " + returnNumber + ": " + dto.reason().trim(),
                        actingUser, now, "PURCHASE_RETURN", returnNumber, dto.grnId()
                ));
            }

            if (dto.creditSupplierLedger() && totalReturnPaisha > 0L) {
                Money currentPayable = supplierLedgerDao.getCurrentPayableBalance(conn, dto.supplierId());
                Money returnCredit = Money.ofPaisha(totalReturnPaisha);
                Money newPayable = currentPayable.subtract(returnCredit);
                supplierLedgerDao.save(conn, new SupplierLedgerEntry(
                        null, dto.businessId(), dto.supplierId(), now,
                        "BILL_REVERSAL", saved.id(), Money.ZERO, returnCredit, newPayable,
                        "Purchase Return " + returnNumber + ": " + dto.reason().trim(), null
                ));
            }

            return saved;
        });
    }

    @Override
    public List<PurchaseReturnListItemDto> getPurchaseReturns(long businessId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return returnDao.findAll(conn, businessId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to fetch supplier returns", e);
        }
    }

    private void recomputePoStatus(Connection conn, long poId, long businessId) throws SQLException {
        List<PurchaseOrderItem> items = poDao.findItemsByOrderId(conn, poId);
        boolean anyReceived = false;
        boolean allFullyReceived = !items.isEmpty();
        for (PurchaseOrderItem item : items) {
            if (item.receivedQty() > 0) anyReceived = true;
            if (item.receivedQty() < item.orderedQty()) allFullyReceived = false;
        }
        PurchaseOrderStatus next = allFullyReceived ? PurchaseOrderStatus.FULLY_RECEIVED
                : (anyReceived ? PurchaseOrderStatus.PARTIALLY_RECEIVED : PurchaseOrderStatus.CONFIRMED);
        poDao.updateStatus(conn, poId, businessId, next);
    }

    private boolean hasActiveSupplierInvoiceLink(Connection conn, long grnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='grn_invoice_links'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
            }
        }
        String sql = "SELECT 1 FROM grn_invoice_links gil JOIN supplier_invoices si ON si.id = gil.supplier_invoice_id " +
                "WHERE gil.grn_id = ? AND si.status = 'POSTED' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, grnId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long[] allocateProportionalAdjustment(List<PreparedGrnLine> lines, long subtotalPaisha, long netAdjPaisha) {
        long[] out = new long[lines.size()];
        if (netAdjPaisha == 0L || subtotalPaisha <= 0L) return out;
        long remaining = netAdjPaisha;
        int largestIdx = -1;
        long largestVal = -1L;
        for (int i = 0; i < lines.size(); i++) {
            long lineVal = lines.get(i).netLinePaisha;
            if (lineVal > 0L) {
                long share = BigDecimal.valueOf(netAdjPaisha)
                        .multiply(BigDecimal.valueOf(lineVal))
                        .divide(BigDecimal.valueOf(subtotalPaisha), 0, RoundingMode.DOWN)
                        .longValue();
                out[i] = share;
                remaining -= share;
                if (lineVal > largestVal) {
                    largestVal = lineVal;
                    largestIdx = i;
                }
            }
        }
        if (remaining != 0L && largestIdx >= 0) {
            out[largestIdx] += remaining;
        }
        return out;
    }

    private record PreparedGrnLine(
            GrnItemCreateDto line,
            Product product,
            PurchaseOrderItem poItem,
            int orderedQty,
            String purchaseUnit,
            int packSize,
            Money tradePrice,
            Money lineDiscount,
            long netLinePaisha
    ) {}
}


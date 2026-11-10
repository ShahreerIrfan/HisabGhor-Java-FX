package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.*;
import com.smartkhata.model.enums.PurchaseOrderStatus;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.PurchaseOrderService;
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

public class SqlitePurchaseOrderService implements PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(SqlitePurchaseOrderService.class);
    private static final DateTimeFormatter DATE_PREFIX_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseOrderDao poDao;
    private final SupplierDao supplierDao;
    private final ProductDao productDao;
    private final ProductSupplierDao productSupplierDao;

    public SqlitePurchaseOrderService() {
        this(new SqlitePurchaseOrderDao(), new SqliteSupplierDao(), new SqliteProductDao(), new SqliteProductSupplierDao());
    }

    public SqlitePurchaseOrderService(PurchaseOrderDao poDao, SupplierDao supplierDao,
                                      ProductDao productDao, ProductSupplierDao productSupplierDao) {
        this.poDao = poDao;
        this.supplierDao = supplierDao;
        this.productDao = productDao;
        this.productSupplierDao = productSupplierDao;
    }

    @Override
    public PurchaseOrder savePurchaseOrder(PurchaseOrderCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        if (dto.items() == null || dto.items().isEmpty()) {
            throw new IllegalArgumentException("At least one product line item is required for a Purchase Order.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Supplier supplier = supplierDao.findById(conn, dto.supplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + dto.supplierId()));
            if (supplier.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            if (supplier.isArchived() || !supplier.isActive()) {
                throw new IllegalArgumentException("Cannot create Purchase Order for archived/inactive supplier: " + supplier.name());
            }

            PurchaseOrder existingOrder = null;
            if (dto.id() != null) {
                existingOrder = poDao.findById(conn, dto.id())
                        .orElseThrow(() -> new IllegalArgumentException("Purchase Order not found: " + dto.id()));
                if (existingOrder.businessId() != dto.businessId()) {
                    throw new SecurityException("Cross-business access denied.");
                }
                if (!existingOrder.status().canEdit()) {
                    throw new IllegalStateException("Only DRAFT Purchase Orders can be modified. Current status: " + existingOrder.status());
                }
            }

            long subtotalPaisha = 0L;
            List<PurchaseOrderItem> items = new ArrayList<>();
            for (PurchaseOrderItemCreateDto line : dto.items()) {
                Product product = productDao.findById(conn, line.productId())
                        .orElseThrow(() -> new IllegalArgumentException("Product not found: " + line.productId()));
                if (product.businessId() != dto.businessId()) {
                    throw new SecurityException("Cross-business access denied: product belongs to another business.");
                }
                if (line.orderedQty() <= 0) {
                    throw new IllegalArgumentException("Ordered quantity must be positive for " + product.name());
                }
                Money tp = line.tradePrice() != null ? line.tradePrice() : product.tradePrice();
                if (tp.isNegative()) {
                    throw new IllegalArgumentException("Trade price cannot be negative for " + product.name());
                }
                Money disc = line.discount() != null ? line.discount() : Money.ZERO;
                Money tax = line.tax() != null ? line.tax() : Money.ZERO;
                if (disc.isNegative() || tax.isNegative()) {
                    throw new IllegalArgumentException("Discount and tax cannot be negative for " + product.name());
                }
                Money gross = tp.multiply(line.orderedQty());
                if (disc.compareTo(gross) > 0) {
                    throw new IllegalArgumentException("Line discount exceeds gross amount for " + product.name());
                }
                Money lineTotal = gross.subtract(disc).add(tax);
                subtotalPaisha = Math.addExact(subtotalPaisha, lineTotal.paisha());

                String purUnit = line.purchaseUnit() != null && !line.purchaseUnit().isBlank()
                        ? line.purchaseUnit().trim()
                        : (product.purchaseUnit() != null ? product.purchaseUnit() : product.unit());
                int packSize = line.packSize() >= 1 ? line.packSize() : Math.max(1, product.packSize());

                items.add(new PurchaseOrderItem(
                        null,
                        existingOrder != null ? existingOrder.id() : 0L,
                        product.id(),
                        line.orderedQty(),
                        0,
                        purUnit,
                        packSize,
                        tp,
                        disc,
                        tax,
                        lineTotal,
                        line.notes()
                ));
            }

            Money subtotal = Money.ofPaisha(subtotalPaisha);
            Money headerDiscount = dto.headerDiscount() != null ? dto.headerDiscount() : Money.ZERO;
            Money headerTax = dto.headerTax() != null ? dto.headerTax() : Money.ZERO;
            Money additionalCharges = dto.additionalCharges() != null ? dto.additionalCharges() : Money.ZERO;
            if (headerDiscount.isNegative() || headerTax.isNegative() || additionalCharges.isNegative()) {
                throw new IllegalArgumentException("Order discount, tax, and additional charges cannot be negative.");
            }
            if (headerDiscount.compareTo(subtotal) > 0) {
                throw new IllegalArgumentException("Order discount cannot exceed subtotal.");
            }
            Money grandTotal = subtotal.subtract(headerDiscount).add(headerTax).add(additionalCharges);

            String poNumber;
            if (existingOrder != null) {
                poNumber = existingOrder.poNumber();
            } else {
                String datePrefix = LocalDate.now().format(DATE_PREFIX_FMT);
                int seq = poDao.getNextPoSequence(conn, dto.businessId(), datePrefix);
                poNumber = String.format("PO-%s-%04d", datePrefix, seq);
            }

            PurchaseOrderStatus targetStatus = dto.confirmImmediately()
                    ? PurchaseOrderStatus.CONFIRMED : PurchaseOrderStatus.DRAFT;

            PurchaseOrder header = new PurchaseOrder(
                    existingOrder != null ? existingOrder.id() : null,
                    dto.businessId(),
                    poNumber,
                    dto.supplierId(),
                    existingOrder != null ? existingOrder.poDate() : LocalDateTime.now(),
                    dto.expectedDeliveryDate(),
                    targetStatus,
                    subtotal,
                    headerDiscount,
                    headerTax,
                    additionalCharges,
                    grandTotal,
                    dto.supplierReference(),
                    dto.notes(),
                    dto.actingUserId() > 0 ? dto.actingUserId()
                            : (SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : 1L),
                    null, null
            );

            PurchaseOrder saved = poDao.saveHeader(conn, header);
            List<PurchaseOrderItem> persistedItems = new ArrayList<>(items.size());
            for (PurchaseOrderItem item : items) {
                persistedItems.add(item.withPurchaseOrderId(saved.id()));
            }
            poDao.replaceItems(conn, saved.id(), persistedItems);
            log.info("Purchase Order [{}] saved with status [{}] (Grand Total: {}) - Zero stock impact",
                    saved.poNumber(), saved.status(), saved.grandTotal());
            return saved;
        });
    }

    @Override
    public PurchaseOrder confirmPurchaseOrder(long poId) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        return TransactionManager.executeInTransaction(conn -> {
            PurchaseOrder po = poDao.findById(conn, poId)
                    .orElseThrow(() -> new IllegalArgumentException("Purchase Order not found: " + poId));
            if (po.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            if (po.status() != PurchaseOrderStatus.DRAFT) {
                throw new IllegalStateException("Only DRAFT orders can be confirmed.");
            }
            poDao.updateStatus(conn, poId, businessId, PurchaseOrderStatus.CONFIRMED);
            log.info("Purchase Order [{}] confirmed (Zero stock impact until GRN).", po.poNumber());
            return poDao.findById(conn, poId).orElseThrow();
        });
    }

    @Override
    public void cancelPurchaseOrder(long poId, String reason) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        TransactionManager.runInTransaction(conn -> {
            PurchaseOrder po = poDao.findById(conn, poId)
                    .orElseThrow(() -> new IllegalArgumentException("Purchase Order not found: " + poId));
            if (po.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            if (!po.status().canCancel()) {
                throw new IllegalStateException("Cannot cancel PO in status " + po.status() + " after goods have been received.");
            }
            String note = (po.notes() != null && !po.notes().isBlank() ? po.notes() + " | " : "")
                    + "Cancelled: " + (reason != null && !reason.isBlank() ? reason.trim() : "User requested");
            PurchaseOrder updated = new PurchaseOrder(
                    po.id(), po.businessId(), po.poNumber(), po.supplierId(), po.poDate(),
                    po.expectedDeliveryDate(), PurchaseOrderStatus.CANCELLED, po.subtotal(),
                    po.discount(), po.tax(), po.additionalCharges(), po.grandTotal(),
                    po.supplierReference(), note, po.createdByUserId(), po.createdAt(), po.updatedAt()
            );
            poDao.saveHeader(conn, updated);
        });
    }

    @Override
    public void closePurchaseOrder(long poId, String reason) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        TransactionManager.runInTransaction(conn -> {
            PurchaseOrder po = poDao.findById(conn, poId)
                    .orElseThrow(() -> new IllegalArgumentException("Purchase Order not found: " + poId));
            if (po.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            if (!po.status().canClose()) {
                throw new IllegalStateException("Only CONFIRMED or PARTIALLY_RECEIVED orders can be closed.");
            }
            String note = (po.notes() != null && !po.notes().isBlank() ? po.notes() + " | " : "")
                    + "Short-closed: " + (reason != null && !reason.isBlank() ? reason.trim() : "Remainder closed");
            PurchaseOrder updated = new PurchaseOrder(
                    po.id(), po.businessId(), po.poNumber(), po.supplierId(), po.poDate(),
                    po.expectedDeliveryDate(), PurchaseOrderStatus.CLOSED, po.subtotal(),
                    po.discount(), po.tax(), po.additionalCharges(), po.grandTotal(),
                    po.supplierReference(), note, po.createdByUserId(), po.createdAt(), po.updatedAt()
            );
            poDao.saveHeader(conn, updated);
        });
    }

    @Override
    public Optional<PurchaseOrderDetailDto> getPurchaseOrderDetail(long poId) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<PurchaseOrder> poOpt = poDao.findById(conn, poId);
            if (poOpt.isEmpty()) return Optional.empty();
            PurchaseOrder po = poOpt.get();
            if (po.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            Supplier sup = supplierDao.findById(conn, po.supplierId()).orElse(null);
            List<PurchaseOrderItemViewDto> items = poDao.findItemViewsByOrderId(conn, poId);
            return Optional.of(new PurchaseOrderDetailDto(po, sup, items));
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load Purchase Order details", e);
        }
    }

    @Override
    public List<PurchaseOrderListItemDto> searchPurchaseOrders(long businessId, String query, Long supplierId,
                                                             PurchaseOrderStatus status, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return poDao.searchOrders(conn, businessId, query, supplierId, status, startDate, endDate);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to search Purchase Orders", e);
        }
    }

    @Override
    public ProductSupplierViewDto resolveProductPricingForSupplier(long productId, long supplierId) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            Product product = productDao.findById(conn, productId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
            if (product.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            Supplier supplier = supplierDao.findById(conn, supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + supplierId));
            Optional<ProductSupplier> mapped = productSupplierDao.findByProductAndSupplier(conn, businessId, productId, supplierId);
            if (mapped.isPresent()) {
                ProductSupplier ps = mapped.get();
                return new ProductSupplierViewDto(
                        ps.id(), businessId, productId, product.name(), product.sku(), product.unit(),
                        supplierId, supplier.name(), supplier.supplierCode(),
                        ps.tradePrice(), ps.supplierProductCode(), ps.minOrderQty(), ps.purchaseUnit(),
                        ps.packSize(), ps.isPreferred(), ps.leadTimeDays(), ps.lastPurchasePrice(),
                        ps.lastPurchaseDate(), ps.isActive()
                );
            }
            return new ProductSupplierViewDto(
                    0L, businessId, productId, product.name(), product.sku(), product.unit(),
                    supplierId, supplier.name(), supplier.supplierCode(),
                    product.tradePrice() != null && product.tradePrice().isPositive() ? product.tradePrice() : product.costPrice(),
                    product.sku(), 1,
                    product.purchaseUnit() != null ? product.purchaseUnit() : product.unit(),
                    Math.max(1, product.packSize()),
                    false, 0, product.lastPurchasePrice(), null, true
            );
        } catch (SQLException e) {
            throw new DatabaseException("Failed to resolve supplier trade price", e);
        }
    }
}


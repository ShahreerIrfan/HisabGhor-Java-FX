package com.smartkhata.service.impl;

import com.smartkhata.dao.*;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Category;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.ProductSupplier;
import com.smartkhata.model.entity.StockMovement;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.InsufficientStockException;
import com.smartkhata.service.api.InventoryService;
import com.smartkhata.service.api.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

public class SqliteInventoryService implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(SqliteInventoryService.class);

    private final ProductDao productDao;
    private final StockMovementDao stockMovementDao;
    private final CategoryDao categoryDao;
    private final ProductSupplierDao productSupplierDao;
    private final SupplierDao supplierDao;

    public SqliteInventoryService() {
        this(new SqliteProductDao(), new SqliteStockMovementDao(), new SqliteCategoryDao(),
                new SqliteProductSupplierDao(), new SqliteSupplierDao());
    }

    public SqliteInventoryService(ProductDao productDao, StockMovementDao stockMovementDao, CategoryDao categoryDao) {
        this(productDao, stockMovementDao, categoryDao, new SqliteProductSupplierDao(), new SqliteSupplierDao());
    }

    public SqliteInventoryService(ProductDao productDao, StockMovementDao stockMovementDao, CategoryDao categoryDao,
                                  ProductSupplierDao productSupplierDao, SupplierDao supplierDao) {
        this.productDao = productDao;
        this.stockMovementDao = stockMovementDao;
        this.categoryDao = categoryDao;
        this.productSupplierDao = productSupplierDao;
        this.supplierDao = supplierDao;
    }

    @Override
    public Product createProduct(ProductCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.name() == null || dto.name().trim().isBlank()) {
            throw new IllegalArgumentException("Product name is required.");
        }
        if (dto.costPrice() != null && dto.costPrice().isNegative()) {
            throw new IllegalArgumentException("Cost price cannot be negative.");
        }
        if (dto.sellingPrice() != null && dto.sellingPrice().isNegative()) {
            throw new IllegalArgumentException("Selling price cannot be negative.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            // Check for duplicate product name in the same business
            Optional<Product> existingName = productDao.findByNameAndBusiness(conn, dto.businessId(), dto.name().trim());
            if (existingName.isPresent()) {
                throw new IllegalArgumentException("A product with the name '" + dto.name().trim() + "' already exists in this store.");
            }

            // Check for duplicate barcode if barcode is provided
            if (dto.barcode() != null && !dto.barcode().trim().isBlank()) {
                Optional<Product> existingBarcode = productDao.findByBarcodeAndBusiness(conn, dto.businessId(), dto.barcode().trim());
                if (existingBarcode.isPresent()) {
                    throw new IllegalArgumentException("A product with the barcode '" + dto.barcode().trim() + "' already exists.");
                }
            }

            String cleanImagePath = dto.imagePath() != null && !dto.imagePath().trim().isBlank()
                    ? dto.imagePath().trim() : null;

            Money safeCost = dto.costPrice() != null ? dto.costPrice() : (dto.tradePrice() != null ? dto.tradePrice() : Money.ZERO);
            Money safeSell = dto.sellingPrice() != null ? dto.sellingPrice() : Money.ZERO;
            Money safeTp = dto.tradePrice() != null && dto.tradePrice().isPositive() ? dto.tradePrice() : safeCost;
            Money safeMrp = dto.mrp() != null && dto.mrp().isPositive() ? dto.mrp() : safeSell;
            String baseUnit = dto.unit() != null && !dto.unit().trim().isBlank() ? dto.unit().trim() : "pcs";
            String purUnit = dto.purchaseUnit() != null && !dto.purchaseUnit().trim().isBlank() ? dto.purchaseUnit().trim() : baseUnit;
            int packSize = Math.max(1, dto.packSize());

            Product product = new Product(
                    null,
                    dto.businessId(),
                    dto.categoryId(),
                    dto.name().trim(),
                    dto.barcode() != null && !dto.barcode().trim().isBlank() ? dto.barcode().trim() : null,
                    dto.sku() != null && !dto.sku().trim().isBlank() ? dto.sku().trim() : null,
                    baseUnit,
                    safeCost,
                    safeSell,
                    Math.max(0, dto.lowStockThreshold()),
                    false,
                    null, null,
                    cleanImagePath,
                    safeTp,
                    safeMrp,
                    safeCost,
                    purUnit,
                    packSize
            );

            Product saved = productDao.save(conn, product);

            // Record initial opening stock in stock_movements ledger if positive
            if (dto.openingStock() > 0) {
                Long userId = SessionContext.getCurrentUser() != null ? SessionContext.getCurrentUser().id() : null;
                StockMovement openingMovement = new StockMovement(
                        null,
                        saved.businessId(),
                        saved.id(),
                        "OPENING",
                        dto.openingStock(),
                        saved.costPrice(),
                        "MANUAL",
                        null,
                        dto.notes() != null && !dto.notes().isBlank() ? dto.notes().trim() : "Verified Initial Opening Stock",
                        userId,
                        LocalDateTime.now()
                );
                stockMovementDao.save(conn, openingMovement);
                log.info("Recorded initial opening stock of {} {} for product [{}]",
                        dto.openingStock(), saved.unit(), saved.name());
            }

            log.info("Product [{}] (ID: {}) created successfully", saved.name(), saved.id());
            return saved;
        });
    }

    @Override
    public Product updateProduct(ProductUpdateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.name() == null || dto.name().trim().isBlank()) {
            throw new IllegalArgumentException("Product name cannot be blank.");
        }
        if (dto.costPrice() != null && dto.costPrice().isNegative()) {
            throw new IllegalArgumentException("Cost price cannot be negative.");
        }
        if (dto.sellingPrice() != null && dto.sellingPrice().isNegative()) {
            throw new IllegalArgumentException("Selling price cannot be negative.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Product> opt = productDao.findById(conn, dto.productId());
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Product not found with id: " + dto.productId());
            }

            Product existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }

            // Check duplicate name if name changed
            if (!existing.name().equalsIgnoreCase(dto.name().trim())) {
                Optional<Product> dup = productDao.findByNameAndBusiness(conn, existing.businessId(), dto.name().trim());
                if (dup.isPresent() && !dup.get().id().equals(existing.id())) {
                    throw new IllegalArgumentException("A product with the name '" + dto.name().trim() + "' already exists.");
                }
            }

            // Check duplicate barcode if barcode changed
            String newBarcode = dto.barcode() != null && !dto.barcode().trim().isBlank() ? dto.barcode().trim() : null;
            if (newBarcode != null && !newBarcode.equals(existing.barcode())) {
                Optional<Product> dupBarcode = productDao.findByBarcodeAndBusiness(conn, existing.businessId(), newBarcode);
                if (dupBarcode.isPresent() && !dupBarcode.get().id().equals(existing.id())) {
                    throw new IllegalArgumentException("A product with the barcode '" + newBarcode + "' already exists.");
                }
            }

            String cleanImagePath = dto.imagePath() != null && !dto.imagePath().trim().isBlank()
                    ? dto.imagePath().trim() : null;

            String baseUnit = dto.unit() != null && !dto.unit().trim().isBlank() ? dto.unit().trim() : existing.unit();
            Money updatedCost = dto.costPrice() != null ? dto.costPrice() : existing.costPrice();
            Money updatedSell = dto.sellingPrice() != null ? dto.sellingPrice() : existing.sellingPrice();
            Money updatedTp = dto.tradePrice() != null ? dto.tradePrice() : existing.tradePrice();
            Money updatedMrp = dto.mrp() != null ? dto.mrp() : existing.mrp();
            String updatedPurUnit = dto.purchaseUnit() != null && !dto.purchaseUnit().trim().isBlank()
                    ? dto.purchaseUnit().trim() : (existing.purchaseUnit() != null ? existing.purchaseUnit() : baseUnit);
            int updatedPack = dto.packSize() >= 1 ? dto.packSize() : Math.max(1, existing.packSize());

            Product updated = new Product(
                    existing.id(),
                    existing.businessId(),
                    dto.categoryId(),
                    dto.name().trim(),
                    newBarcode,
                    dto.sku() != null && !dto.sku().trim().isBlank() ? dto.sku().trim() : null,
                    baseUnit,
                    updatedCost,
                    updatedSell,
                    Math.max(0, dto.lowStockThreshold()),
                    existing.isArchived(),
                    existing.createdAt(),
                    null,
                    cleanImagePath,
                    updatedTp,
                    updatedMrp,
                    existing.lastPurchasePrice(),
                    updatedPurUnit,
                    updatedPack
            );

            return productDao.save(conn, updated);
        });
    }

    @Override
    public void archiveProduct(long productId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Product> opt = productDao.findById(conn, productId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Product not found with id: " + productId);
            }
            Product product = opt.get();
            if (product.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            productDao.setArchived(conn, productId, product.businessId(), true);
            log.info("Archived product [{}] (ID: {}). Movement history retained.", product.name(), productId);
        });
    }

    @Override
    public void restoreProduct(long productId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Product> opt = productDao.findById(conn, productId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Product not found with id: " + productId);
            }
            Product product = opt.get();
            if (product.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            productDao.setArchived(conn, productId, product.businessId(), false);
            log.info("Restored archived product [{}] (ID: {}).", product.name(), productId);
        });
    }

    @Override
    public void deleteProduct(long productId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Product> opt = productDao.findById(conn, productId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Product not found with id: " + productId);
            }
            Product product = opt.get();
            if (product.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            if (productDao.hasLinkedTransactionRecords(conn, productId)) {
                throw new IllegalStateException("Cannot delete product '" + product.name()
                        + "' because it has sales, purchase, or stock adjustment history. Please Archive it instead.");
            }
            productDao.delete(conn, productId, product.businessId());
            log.info("Deleted unused product [{}] (ID: {})", product.name(), productId);
        });
    }

    @Override
    public void updateStockMovementNotes(long movementId, String notes) {
        SessionContext.requireAuthenticated();
        TransactionManager.runInTransaction(conn -> {
            Optional<StockMovement> opt = stockMovementDao.findById(conn, movementId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Stock movement not found with id: " + movementId);
            }
            StockMovement m = opt.get();
            if (m.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: stock movement belongs to another business.");
            }
            String normalized = notes != null ? notes.trim() : "";
            stockMovementDao.updateNotes(conn, movementId, m.businessId(), normalized);
        });
    }

    @Override
    public Optional<Product> getProductById(long productId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Product> opt = productDao.findById(conn, productId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            return opt;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load product", e);
        }
    }

    @Override
    public List<ProductListItemDto> searchProducts(long businessId, String query, Long categoryId, boolean includeArchived) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            List<Product> products = productDao.search(conn, businessId, query, categoryId, includeArchived);
            List<ProductListItemDto> dtos = new ArrayList<>(products.size());

            // Cache categories for lookup
            Map<Long, String> categoryNames = new HashMap<>();
            for (Category c : categoryDao.findAll(conn, businessId)) {
                categoryNames.put(c.id(), c.name());
            }

            for (Product p : products) {
                int stock = productDao.getCurrentStock(conn, p.id());
                boolean isLow = stock <= p.lowStockThreshold();
                String catName = p.categoryId() != null ? categoryNames.getOrDefault(p.categoryId(), "—") : "—";

                dtos.add(new ProductListItemDto(
                        p.id(),
                        p.name(),
                        p.categoryId(),
                        catName,
                        p.barcode(),
                        p.sku(),
                        p.unit(),
                        p.costPrice(),
                        p.sellingPrice(),
                        stock,
                        p.lowStockThreshold(),
                        isLow,
                        p.isArchived(),
                        p.createdAt(),
                        p.imagePath(),
                        p.tradePrice(),
                        p.mrp(),
                        p.lastPurchasePrice(),
                        p.purchaseUnit(),
                        p.packSize()
                ));
            }
            return dtos;
        } catch (SQLException e) {
            log.error("Failed to search products: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve product catalog", e);
        }
    }

    @Override
    public StockMovement recordStockMovement(StockMovement movement) {
        SessionContext.requireAuthenticated();
        if (movement.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match movement business.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Product> pOpt = productDao.findById(conn, movement.productId());
            if (pOpt.isEmpty()) {
                throw new IllegalArgumentException("Product not found with id: " + movement.productId());
            }

            // Enforce non-negative stock guard for outward movements
            if (movement.quantity() < 0) {
                int available = productDao.getCurrentStock(conn, movement.productId());
                if (available + movement.quantity() < 0) {
                    throw new InsufficientStockException(movement.productId(), available, Math.abs(movement.quantity()));
                }
            }

            return stockMovementDao.save(conn, movement);
        });
    }

    @Override
    public StockMovement adjustStock(StockAdjustmentDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.reason() == null || dto.reason().trim().isBlank()) {
            throw new IllegalArgumentException("A specific reason is strictly required for manual stock adjustments.");
        }
        if (dto.quantityDelta() == 0) {
            throw new IllegalArgumentException("Adjustment quantity delta cannot be zero.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Product> pOpt = productDao.findById(conn, dto.productId());
            if (pOpt.isEmpty()) {
                throw new IllegalArgumentException("Product not found with id: " + dto.productId());
            }
            Product product = pOpt.get();
            if (product.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }

            // Validate negative stock invariant
            int currentStock = productDao.getCurrentStock(conn, product.id());
            if (dto.quantityDelta() < 0 && (currentStock + dto.quantityDelta() < 0)) {
                throw new InsufficientStockException(product.id(), currentStock, Math.abs(dto.quantityDelta()));
            }

            StockMovement movement = new StockMovement(
                    null,
                    product.businessId(),
                    product.id(),
                    "MANUAL_ADJUSTMENT",
                    dto.quantityDelta(),
                    product.costPrice(),
                    "MANUAL",
                    null,
                    dto.reason().trim(),
                    dto.actingUserId(),
                    LocalDateTime.now()
            );

            StockMovement saved = stockMovementDao.save(conn, movement);
            log.info("Manual stock adjustment recorded for product [{}] (Delta: {}, Reason: '{}')",
                    product.name(), dto.quantityDelta(), dto.reason());
            return saved;
        });
    }

    @Override
    public int getCurrentStock(long productId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Product> pOpt = productDao.findById(conn, productId);
            if (pOpt.isPresent() && pOpt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            return productDao.getCurrentStock(conn, productId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get product stock", e);
        }
    }

    @Override
    public boolean isAvailable(long productId, int requestedQuantity) {
        return getCurrentStock(productId) >= requestedQuantity;
    }

    @Override
    public void validateStockAvailability(long productId, int requestedQuantity) {
        int current = getCurrentStock(productId);
        if (current < requestedQuantity) {
            throw new InsufficientStockException(productId, current, requestedQuantity);
        }
    }

    @Override
    public List<StockMovement> getProductMovements(long productId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Product> pOpt = productDao.findById(conn, productId);
            if (pOpt.isPresent() && pOpt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            return stockMovementDao.findByProductId(conn, productId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load stock movements", e);
        }
    }

    @Override
    public InventorySummaryDto getInventorySummary(long businessId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            List<Product> allProducts = productDao.search(conn, businessId, null, null, true);
            int totalProducts = allProducts.size();
            int totalActive = 0;
            int totalQuantity = 0;
            int lowStockCount = 0;
            int outOfStockCount = 0;

            for (Product p : allProducts) {
                if (p.isArchived()) {
                    continue;
                }
                totalActive++;
                int stock = productDao.getCurrentStock(conn, p.id());
                totalQuantity += stock;
                if (stock <= p.lowStockThreshold()) {
                    lowStockCount++;
                }
                if (stock <= 0) {
                    outOfStockCount++;
                }
            }

            Money valuation = productDao.getTotalInventoryValuation(conn, businessId);

            return new InventorySummaryDto(
                    totalProducts,
                    totalActive,
                    totalQuantity,
                    lowStockCount,
                    outOfStockCount,
                    valuation
            );
        } catch (SQLException e) {
            log.error("Failed to compute inventory summary: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to calculate inventory summary", e);
        }
    }

    @Override
    public Category createCategory(long businessId, String name, String description) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (name == null || name.trim().isBlank()) {
            throw new IllegalArgumentException("Category name is required.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Category> existing = categoryDao.findByNameAndBusiness(conn, businessId, name.trim());
            if (existing.isPresent()) {
                throw new IllegalArgumentException("Category '" + name.trim() + "' already exists.");
            }
            Category cat = new Category(null, businessId, name.trim(), description, LocalDateTime.now());
            return categoryDao.save(conn, cat);
        });
    }

    @Override
    public Category updateCategory(long categoryId, String name, String description) {
        SessionContext.requireAuthenticated();
        if (name == null || name.trim().isBlank()) {
            throw new IllegalArgumentException("Category name cannot be empty.");
        }
        String trimmedName = name.trim();
        return TransactionManager.executeInTransaction(conn -> {
            Optional<Category> opt = categoryDao.findById(conn, categoryId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Category not found with id: " + categoryId);
            }
            Category existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: category belongs to another business.");
            }
            Optional<Category> dup = categoryDao.findByNameAndBusiness(conn, existing.businessId(), trimmedName);
            if (dup.isPresent() && !dup.get().id().equals(categoryId)) {
                throw new IllegalArgumentException("Category '" + trimmedName + "' already exists.");
            }
            Category updated = new Category(categoryId, existing.businessId(), trimmedName, description, existing.createdAt());
            return categoryDao.save(conn, updated);
        });
    }

    @Override
    public void deleteCategory(long categoryId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Category> opt = categoryDao.findById(conn, categoryId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Category not found with id: " + categoryId);
            }
            Category existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: category belongs to another business.");
            }
            categoryDao.delete(conn, categoryId, existing.businessId());
            log.info("Deleted category [{}] (ID: {}) and unlinked products", existing.name(), categoryId);
        });
    }

    @Override
    public List<Category> getCategories(long businessId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return categoryDao.findAll(conn, businessId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load categories", e);
        }
    }

    @Override
    public ProductSupplier saveProductSupplier(ProductSupplierUpsertDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.tradePrice() != null && dto.tradePrice().isNegative()) {
            throw new IllegalArgumentException("Supplier trade price cannot be negative.");
        }
        if (dto.packSize() < 1) {
            throw new IllegalArgumentException("Pack size must be at least 1 base unit.");
        }
        if (dto.minOrderQty() < 0 || dto.leadTimeDays() < 0) {
            throw new IllegalArgumentException("Minimum order quantity and lead time cannot be negative.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Product product = productDao.findById(conn, dto.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found with id: " + dto.productId()));
            if (product.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied: product belongs to another business.");
            }
            var supplier = supplierDao.findById(conn, dto.supplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found with id: " + dto.supplierId()));
            if (supplier.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }

            Optional<ProductSupplier> existingOpt = dto.id() != null
                    ? productSupplierDao.findById(conn, dto.id())
                    : productSupplierDao.findByProductAndSupplier(conn, dto.businessId(), dto.productId(), dto.supplierId());

            ProductSupplier existing = existingOpt.orElse(null);
            if (existing != null && existing.businessId() != dto.businessId()) {
                throw new SecurityException("Cross-business access denied.");
            }

            String purUnit = dto.purchaseUnit() != null && !dto.purchaseUnit().trim().isBlank()
                    ? dto.purchaseUnit().trim()
                    : (product.purchaseUnit() != null ? product.purchaseUnit() : product.unit());

            ProductSupplier toSave = new ProductSupplier(
                    existing != null ? existing.id() : null,
                    dto.businessId(),
                    dto.productId(),
                    dto.supplierId(),
                    dto.tradePrice() != null ? dto.tradePrice() : Money.ZERO,
                    dto.supplierProductCode(),
                    dto.minOrderQty(),
                    purUnit,
                    dto.packSize(),
                    dto.isPreferred(),
                    dto.leadTimeDays(),
                    existing != null ? existing.lastPurchasePrice() : Money.ZERO,
                    existing != null ? existing.lastPurchaseDate() : null,
                    dto.isActive(),
                    existing != null ? existing.createdAt() : null,
                    null
            );

            ProductSupplier saved = productSupplierDao.save(conn, toSave);
            if (saved.isPreferred()) {
                productSupplierDao.clearPreferredForProduct(conn, dto.businessId(), dto.productId(), saved.id());
            }
            return saved;
        });
    }

    @Override
    public void removeProductSupplier(long productSupplierId) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        TransactionManager.runInTransaction(conn -> {
            ProductSupplier existing = productSupplierDao.findById(conn, productSupplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Product-supplier mapping not found: " + productSupplierId));
            if (existing.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            productSupplierDao.delete(conn, productSupplierId, businessId);
        });
    }

    @Override
    public List<ProductSupplierViewDto> getProductSuppliers(long productId, boolean includeInactive) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            Product p = productDao.findById(conn, productId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found with id: " + productId));
            if (p.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            return productSupplierDao.findByProductId(conn, businessId, productId, includeInactive);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to fetch suppliers mapped to product", e);
        }
    }

    @Override
    public List<ProductSupplierViewDto> getSupplierProducts(long supplierId, boolean includeInactive) {
        SessionContext.requireAuthenticated();
        long businessId = SessionContext.getRequiredBusinessId();
        try (Connection conn = DatabaseManager.getConnection()) {
            var s = supplierDao.findById(conn, supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Supplier not found with id: " + supplierId));
            if (s.businessId() != businessId) {
                throw new SecurityException("Cross-business access denied.");
            }
            return productSupplierDao.findBySupplierId(conn, businessId, supplierId, includeInactive);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to fetch products mapped to supplier", e);
        }
    }
}


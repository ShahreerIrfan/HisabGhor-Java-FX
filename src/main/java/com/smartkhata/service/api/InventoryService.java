package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Category;
import com.smartkhata.model.entity.Product;
import com.smartkhata.model.entity.ProductSupplier;
import com.smartkhata.model.entity.StockMovement;

import java.util.List;
import java.util.Optional;

public interface InventoryService {

    Product createProduct(ProductCreateDto dto);

    Product updateProduct(ProductUpdateDto dto);

    void archiveProduct(long productId);

    void restoreProduct(long productId);

    void deleteProduct(long productId);

    Optional<Product> getProductById(long productId);

    List<ProductListItemDto> searchProducts(long businessId, String query, Long categoryId, boolean includeArchived);

    StockMovement recordStockMovement(StockMovement movement);

    StockMovement adjustStock(StockAdjustmentDto dto);

    void updateStockMovementNotes(long movementId, String notes);

    int getCurrentStock(long productId);

    boolean isAvailable(long productId, int requestedQuantity);

    void validateStockAvailability(long productId, int requestedQuantity);

    List<StockMovement> getProductMovements(long productId);

    InventorySummaryDto getInventorySummary(long businessId);

    Category createCategory(long businessId, String name, String description);

    Category updateCategory(long categoryId, String name, String description);

    void deleteCategory(long categoryId);

    List<Category> getCategories(long businessId);

    ProductSupplier saveProductSupplier(ProductSupplierUpsertDto dto);

    void removeProductSupplier(long productSupplierId);

    List<ProductSupplierViewDto> getProductSuppliers(long productId, boolean includeInactive);

    List<ProductSupplierViewDto> getSupplierProducts(long supplierId, boolean includeInactive);
}

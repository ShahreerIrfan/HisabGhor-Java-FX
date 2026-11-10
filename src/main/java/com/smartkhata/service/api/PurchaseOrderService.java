package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.PurchaseOrder;
import com.smartkhata.model.enums.PurchaseOrderStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderService {

    PurchaseOrder savePurchaseOrder(PurchaseOrderCreateDto dto);

    PurchaseOrder confirmPurchaseOrder(long poId);

    void cancelPurchaseOrder(long poId, String reason);

    void closePurchaseOrder(long poId, String reason);

    Optional<PurchaseOrderDetailDto> getPurchaseOrderDetail(long poId);

    List<PurchaseOrderListItemDto> searchPurchaseOrders(long businessId, String query, Long supplierId,
                                                      PurchaseOrderStatus status, LocalDate startDate, LocalDate endDate);

    /**
     * Resolves supplier-specific Trade Price, purchase unit & pack conversion from product_suppliers
     * or falls back to the product's default pricing and unit.
     */
    ProductSupplierViewDto resolveProductPricingForSupplier(long productId, long supplierId);
}


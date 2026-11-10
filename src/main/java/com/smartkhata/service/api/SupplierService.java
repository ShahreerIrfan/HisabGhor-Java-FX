package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.entity.SupplierLedgerEntry;
import com.smartkhata.model.vo.Money;

import java.util.List;
import java.util.Optional;

public interface SupplierService {

    Supplier createSupplier(SupplierCreateDto dto);

    Supplier updateSupplier(SupplierUpdateDto dto);

    void archiveSupplier(long supplierId);

    void restoreSupplier(long supplierId);

    void deleteSupplier(long supplierId);

    List<SupplierListItemDto> searchSuppliers(long businessId, String query, boolean includeArchived);

    Optional<Supplier> getSupplierById(long supplierId);

    List<SupplierLedgerEntry> getSupplierLedger(long supplierId);

    Money getTotalOutstandingPayables(long businessId);

    List<ProductSupplierViewDto> getSupplierProducts(long supplierId);
}


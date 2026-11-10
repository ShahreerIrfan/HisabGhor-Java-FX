package com.smartkhata.service.api;

import com.smartkhata.model.dto.PurchaseCreateDto;
import com.smartkhata.model.dto.PurchaseDetailDto;
import com.smartkhata.model.dto.PurchaseListItemDto;
import com.smartkhata.model.entity.Purchase;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseService {

    Purchase createPurchase(PurchaseCreateDto dto);

    void voidPurchase(long purchaseId, String reason, long actingUserId);

    void updatePurchaseNotes(long purchaseId, String notes);

    void deleteVoidedPurchase(long purchaseId);

    Optional<PurchaseDetailDto> getPurchaseDetail(long purchaseId);

    List<PurchaseListItemDto> searchPurchases(long businessId, String query, Long supplierId,
                                              String paymentStatus, LocalDate startDate, LocalDate endDate);
}


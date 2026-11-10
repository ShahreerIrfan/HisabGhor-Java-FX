package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.GoodsReceiptNote;
import com.smartkhata.model.entity.InventoryCostHistory;
import com.smartkhata.model.entity.PurchaseReturn;
import com.smartkhata.model.enums.GrnStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GrnService {

    GoodsReceiptNote postGoodsReceipt(GrnCreateDto dto);

    void reverseGrn(long grnId, String reason, long actingUserId);

    Optional<GrnDetailDto> getGrnDetail(long grnId);

    List<GrnListItemDto> searchGrns(long businessId, String query, Long supplierId, Long poId,
                                    GrnStatus status, LocalDate startDate, LocalDate endDate);

    List<InventoryCostHistory> getProductCostHistory(long productId);

    PurchaseReturn createPurchaseReturn(PurchaseReturnCreateDto dto);

    List<PurchaseReturnListItemDto> getPurchaseReturns(long businessId);
}


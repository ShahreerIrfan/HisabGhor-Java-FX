package com.smartkhata.service.api;

import com.smartkhata.model.dto.SaleCreateDto;
import com.smartkhata.model.dto.SaleDetailDto;
import com.smartkhata.model.dto.SaleListItemDto;
import com.smartkhata.model.dto.SaleReturnCreateDto;
import com.smartkhata.model.dto.SaleUpdateDto;
import com.smartkhata.model.entity.Sale;
import com.smartkhata.model.entity.SaleReturn;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SaleService {

    Sale createSale(SaleCreateDto dto);

    Sale updateSaleOrder(SaleUpdateDto dto);

    void deleteSaleOrder(long saleId);

    void voidSale(long saleId, String reason, long actingUserId);

    void updateSaleNotes(long saleId, String notes);

    void deleteVoidedSale(long saleId);

    Optional<String> findPrimaryPaymentMethod(long saleId);

    Optional<SaleDetailDto> getSaleDetail(long saleId);

    List<SaleListItemDto> searchSales(long businessId, String query, Long customerId,
                                      String paymentStatus, LocalDate startDate, LocalDate endDate);

    SaleReturn createSaleReturn(SaleReturnCreateDto dto);
}


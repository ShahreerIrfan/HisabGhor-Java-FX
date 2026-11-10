package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.SupplierInvoice;
import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SupplierInvoiceService {

    SupplierInvoice postSupplierInvoice(SupplierInvoiceCreateDto dto);

    /**
     * Simultaneous GRN + Supplier Invoice workflow without double-counting stock or supplier payables.
     */
    SupplierInvoice postGrnAndInvoice(GrnCreateDto grnDto, String supplierBillNo, LocalDate dueDate,
                                      Money tax, Money initialPayment, String paymentMethod, String refTrxId);

    void voidSupplierInvoice(long invoiceId, String reason, long actingUserId);

    Optional<SupplierInvoiceDetailDto> getSupplierInvoiceDetail(long invoiceId);

    List<SupplierInvoiceListItemDto> searchSupplierInvoices(long businessId, String query, Long supplierId,
                                                            String paymentStatus, LocalDate startDate, LocalDate endDate);

    List<GrnListItemDto> getUninvoicedGrns(long businessId, Long supplierId);

    Money getTotalUninvoicedGrnLiability(long businessId);
}


package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Payment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PaymentService {

    Payment receiveCustomerPayment(PaymentCreateDto dto);

    Payment makeSupplierPayment(PaymentCreateDto dto);

    void voidPayment(long paymentId, String reason, long actingUserId);

    void updatePaymentDetails(long paymentId, String paymentMethod, String referenceTrxId, String notes);

    void deleteVoidedPayment(long paymentId);

    Optional<PaymentDetailDto> getPaymentDetail(long paymentId);

    List<PaymentListItemDto> searchPayments(long businessId, String query, String partyType,
                                            Long partyId, String paymentMethod, LocalDate startDate, LocalDate endDate);
}


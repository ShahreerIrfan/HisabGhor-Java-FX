package com.smartkhata.dao;

import com.smartkhata.model.dto.PaymentAllocationDto;
import com.smartkhata.model.dto.PaymentListItemDto;
import com.smartkhata.model.entity.Payment;
import com.smartkhata.model.entity.PaymentAllocation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentDao {
    Payment save(Connection conn, Payment payment) throws SQLException;
    void saveAllocations(Connection conn, List<PaymentAllocation> allocations) throws SQLException;
    Optional<Payment> findById(Connection conn, long id) throws SQLException;
    Optional<Payment> findByPaymentNumber(Connection conn, String paymentNumber) throws SQLException;
    Optional<Payment> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException;
    List<PaymentAllocationDto> findAllocationsByPaymentId(Connection conn, long paymentId) throws SQLException;
    List<PaymentListItemDto> searchPayments(Connection conn, long businessId, String query, String partyType, Long partyId, String paymentMethod, LocalDate startDate, LocalDate endDate) throws SQLException;
    int getNextPaymentSequence(Connection conn, long businessId, String datePrefix, String partyType) throws SQLException;
    void voidPayment(Connection conn, long paymentId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException;
    void updateMetadata(Connection conn, long paymentId, long businessId, String paymentMethod, String referenceTrxId, String notes) throws SQLException;
    boolean deleteVoided(Connection conn, long paymentId, long businessId) throws SQLException;
}


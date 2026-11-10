package com.smartkhata.dao;

import com.smartkhata.model.dto.PurchaseListItemDto;
import com.smartkhata.model.entity.Purchase;
import com.smartkhata.model.entity.PurchaseItem;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PurchaseDao {
    Purchase save(Connection conn, Purchase purchase) throws SQLException;
    void saveItems(Connection conn, List<PurchaseItem> items) throws SQLException;
    Optional<Purchase> findById(Connection conn, long id) throws SQLException;
    Optional<Purchase> findByPurchaseNumber(Connection conn, String purchaseNumber) throws SQLException;
    Optional<Purchase> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException;
    List<PurchaseItem> findItemsByPurchaseId(Connection conn, long purchaseId) throws SQLException;
    List<PurchaseListItemDto> searchPurchases(Connection conn, long businessId, String query, Long supplierId, String paymentStatus, LocalDate startDate, LocalDate endDate) throws SQLException;
    int getNextPurchaseSequence(Connection conn, long businessId, String datePrefix) throws SQLException;
    void voidPurchase(Connection conn, long purchaseId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException;
    void updateNotes(Connection conn, long purchaseId, long businessId, String notes) throws SQLException;
    boolean hasActiveLinkedPayments(Connection conn, long purchaseId) throws SQLException;
    boolean deleteVoided(Connection conn, long purchaseId, long businessId) throws SQLException;
    List<Purchase> findUnpaidPurchasesBySupplierId(Connection conn, long supplierId) throws SQLException;
    void updatePaymentStatus(Connection conn, long purchaseId, Money paidAmount, Money dueAmount, String paymentStatus) throws SQLException;
}


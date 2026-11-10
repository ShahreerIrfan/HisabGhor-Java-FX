package com.smartkhata.dao;

import com.smartkhata.model.dto.SaleListItemDto;
import com.smartkhata.model.entity.Sale;
import com.smartkhata.model.entity.SaleItem;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SaleDao {
    Sale save(Connection conn, Sale sale) throws SQLException;
    void saveItems(Connection conn, List<SaleItem> items) throws SQLException;
    Optional<Sale> findById(Connection conn, long id) throws SQLException;
    Optional<Sale> findByInvoiceNumber(Connection conn, String invoiceNumber) throws SQLException;
    Optional<Sale> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException;
    List<SaleItem> findItemsBySaleId(Connection conn, long saleId) throws SQLException;
    List<SaleListItemDto> searchSales(Connection conn, long businessId, String query, Long customerId, String paymentStatus, LocalDate startDate, LocalDate endDate) throws SQLException;
    int getNextInvoiceSequence(Connection conn, long businessId, String datePrefix) throws SQLException;
    void voidSale(Connection conn, long saleId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException;
    void updateNotes(Connection conn, long saleId, long businessId, String notes) throws SQLException;
    void updateOrderHeader(Connection conn, Sale sale) throws SQLException;
    void replaceItems(Connection conn, long saleId, List<SaleItem> items) throws SQLException;
    Optional<String> findPrimaryPaymentMethodBySaleId(Connection conn, long saleId) throws SQLException;
    boolean hasActiveLinkedPayments(Connection conn, long saleId) throws SQLException;
    boolean deleteVoided(Connection conn, long saleId, long businessId) throws SQLException;
    boolean deleteSaleRecord(Connection conn, long saleId, long businessId) throws SQLException;
    List<Sale> findUnpaidSalesByCustomerId(Connection conn, long customerId) throws SQLException;
    void updatePaymentStatus(Connection conn, long saleId, Money paidAmount, Money dueAmount, String paymentStatus) throws SQLException;
}

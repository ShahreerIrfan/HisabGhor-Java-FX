package com.smartkhata.dao;

import com.smartkhata.model.dto.GrnListItemDto;
import com.smartkhata.model.dto.SupplierInvoiceItemViewDto;
import com.smartkhata.model.dto.SupplierInvoiceListItemDto;
import com.smartkhata.model.entity.SupplierInvoice;
import com.smartkhata.model.entity.SupplierInvoiceItem;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SupplierInvoiceDao {
    SupplierInvoice saveHeader(Connection conn, SupplierInvoice invoice) throws SQLException;
    void saveItems(Connection conn, long invoiceId, List<SupplierInvoiceItem> items) throws SQLException;
    void linkGrn(Connection conn, long grnId, long supplierInvoiceId, Money allocatedAmount) throws SQLException;
    boolean isGrnActivelyInvoiced(Connection conn, long grnId) throws SQLException;
    boolean hasActiveSupplierBillNo(Connection conn, long businessId, long supplierId, String supplierBillNo) throws SQLException;
    Optional<SupplierInvoice> findById(Connection conn, long id) throws SQLException;
    Optional<SupplierInvoice> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException;
    List<SupplierInvoiceItemViewDto> findItemViewsByInvoiceId(Connection conn, long invoiceId) throws SQLException;
    List<SupplierInvoiceListItemDto> searchInvoices(Connection conn, long businessId, String query,
                                                    Long supplierId, String paymentStatus,
                                                    LocalDate startDate, LocalDate endDate) throws SQLException;
    List<SupplierInvoice> findUnpaidBySupplierId(Connection conn, long supplierId) throws SQLException;
    void updatePaymentStatus(Connection conn, long invoiceId, Money paidAmount, Money dueAmount, String paymentStatus) throws SQLException;
    void voidInvoice(Connection conn, long invoiceId, long businessId, String reason) throws SQLException;
    int getNextInvoiceSequence(Connection conn, long businessId, String datePrefix) throws SQLException;
    List<GrnListItemDto> findUninvoicedGrns(Connection conn, long businessId, Long supplierId) throws SQLException;
    Money getTotalUninvoicedGrnLiability(Connection conn, long businessId) throws SQLException;
}


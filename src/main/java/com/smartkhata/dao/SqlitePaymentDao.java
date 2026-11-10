package com.smartkhata.dao;

import com.smartkhata.model.dto.PaymentAllocationDto;
import com.smartkhata.model.dto.PaymentListItemDto;
import com.smartkhata.model.entity.Payment;
import com.smartkhata.model.entity.PaymentAllocation;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqlitePaymentDao implements PaymentDao {

    @Override
    public Payment save(Connection conn, Payment payment) throws SQLException {
        if (payment.id() == null) {
            String sql = "INSERT INTO payments (business_id, payment_number, party_type, party_id, amount_paisha, " +
                    "payment_date, payment_method, reference_trx_id, notes, created_by_user_id, idempotency_key) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, payment.businessId());
                ps.setString(2, payment.paymentNumber());
                ps.setString(3, payment.partyType());
                ps.setLong(4, payment.partyId());
                ps.setLong(5, payment.amount().paisha());
                ps.setString(6, payment.paymentDate() != null ? payment.paymentDate().toString().replace("T", " ") : null);
                ps.setString(7, payment.paymentMethod());
                ps.setString(8, payment.referenceTrxId());
                ps.setString(9, payment.notes());
                ps.setLong(10, payment.createdByUserId());
                ps.setString(11, payment.idempotencyKey());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return payment.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE payments SET is_reversed = ?, reversal_reason = ?, reversed_at = ? " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, payment.isReversed() ? 1 : 0);
                ps.setString(2, payment.reversalReason());
                ps.setString(3, payment.reversedAt() != null ? payment.reversedAt().toString().replace("T", " ") : null);
                ps.setLong(4, payment.id());
                ps.setLong(5, payment.businessId());
                ps.executeUpdate();
            }
        }
        return payment;
    }

    @Override
    public void saveAllocations(Connection conn, List<PaymentAllocation> allocations) throws SQLException {
        if (allocations == null || allocations.isEmpty()) return;

        String sql = "INSERT INTO payment_allocations (payment_id, sale_id, purchase_id, supplier_invoice_id, allocated_amount_paisha) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PaymentAllocation alloc : allocations) {
                ps.setLong(1, alloc.paymentId());
                if (alloc.saleId() != null) ps.setLong(2, alloc.saleId()); else ps.setNull(2, Types.INTEGER);
                if (alloc.purchaseId() != null) ps.setLong(3, alloc.purchaseId()); else ps.setNull(3, Types.INTEGER);
                if (alloc.supplierInvoiceId() != null) ps.setLong(4, alloc.supplierInvoiceId()); else ps.setNull(4, Types.INTEGER);
                ps.setLong(5, alloc.allocatedAmount().paisha());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public Optional<Payment> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, business_id, payment_number, party_type, party_id, amount_paisha, payment_date, " +
                "payment_method, reference_trx_id, notes, created_by_user_id, idempotency_key, is_reversed, " +
                "reversal_reason, reversed_at, created_at FROM payments WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Payment> findByPaymentNumber(Connection conn, String paymentNumber) throws SQLException {
        String sql = "SELECT id, business_id, payment_number, party_type, party_id, amount_paisha, payment_date, " +
                "payment_method, reference_trx_id, notes, created_by_user_id, idempotency_key, is_reversed, " +
                "reversal_reason, reversed_at, created_at FROM payments WHERE payment_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException {
        String sql = "SELECT id, business_id, payment_number, party_type, party_id, amount_paisha, payment_date, " +
                "payment_method, reference_trx_id, notes, created_by_user_id, idempotency_key, is_reversed, " +
                "reversal_reason, reversed_at, created_at FROM payments WHERE business_id = ? AND idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<PaymentAllocationDto> findAllocationsByPaymentId(Connection conn, long paymentId) throws SQLException {
        List<PaymentAllocationDto> list = new ArrayList<>();
        String sql = "SELECT pa.id, pa.sale_id, s.invoice_number, pa.purchase_id, p.purchase_number, " +
                "pa.supplier_invoice_id, si.invoice_number AS supplier_invoice_number, pa.allocated_amount_paisha " +
                "FROM payment_allocations pa " +
                "LEFT JOIN sales s ON pa.sale_id = s.id " +
                "LEFT JOIN purchases p ON pa.purchase_id = p.id " +
                "LEFT JOIN supplier_invoices si ON pa.supplier_invoice_id = si.id " +
                "WHERE pa.payment_id = ? ORDER BY pa.id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long saleId = rs.getObject("sale_id") != null ? rs.getLong("sale_id") : null;
                    String invNum = rs.getString("invoice_number");
                    Long purId = rs.getObject("purchase_id") != null ? rs.getLong("purchase_id") : null;
                    String purNum = rs.getString("purchase_number");
                    Long supInvId = rs.getObject("supplier_invoice_id") != null ? rs.getLong("supplier_invoice_id") : null;
                    String supInvNum = rs.getString("supplier_invoice_number");
                    Money amount = Money.ofPaisha(rs.getLong("allocated_amount_paisha"));

                    list.add(new PaymentAllocationDto(rs.getLong("id"), saleId, invNum, purId, purNum, amount, supInvId, supInvNum));
                }
            }
        }
        return list;
    }

    @Override
    public List<PaymentListItemDto> searchPayments(Connection conn, long businessId, String query, String partyType,
                                                  Long partyId, String paymentMethod, LocalDate startDate, LocalDate endDate) throws SQLException {
        List<PaymentListItemDto> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT py.id, py.payment_number, py.party_type, py.party_id, py.amount_paisha, py.payment_date, " +
                        "py.payment_method, py.reference_trx_id, py.is_reversed, " +
                        "CASE " +
                        "  WHEN py.party_type = 'CUSTOMER' THEN (SELECT name FROM customers WHERE id = py.party_id) " +
                        "  WHEN py.party_type = 'SUPPLIER' THEN (SELECT name FROM suppliers WHERE id = py.party_id) " +
                        "  ELSE 'Counter' " +
                        "END as party_name, " +
                        "(SELECT COUNT(*) FROM payment_allocations pa WHERE pa.payment_id = py.id) as allocation_count " +
                        "FROM payments py " +
                        "WHERE py.business_id = ? "
        );

        if (partyType != null && !partyType.isBlank() && !"ALL".equalsIgnoreCase(partyType)) {
            sql.append("AND py.party_type = ? ");
        }
        if (partyId != null) {
            sql.append("AND py.party_id = ? ");
        }
        if (paymentMethod != null && !paymentMethod.isBlank() && !"ALL".equalsIgnoreCase(paymentMethod)) {
            sql.append("AND py.payment_method = ? ");
        }
        if (startDate != null) {
            sql.append("AND date(py.payment_date) >= ? ");
        }
        if (endDate != null) {
            sql.append("AND date(py.payment_date) <= ? ");
        }
        boolean hasQuery = query != null && !query.trim().isBlank();
        if (hasQuery) {
            sql.append("AND (py.payment_number LIKE ? OR py.reference_trx_id LIKE ?) ");
        }
        sql.append("ORDER BY py.payment_date DESC, py.id DESC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (partyType != null && !partyType.isBlank() && !"ALL".equalsIgnoreCase(partyType)) {
                ps.setString(idx++, partyType.toUpperCase());
            }
            if (partyId != null) {
                ps.setLong(idx++, partyId);
            }
            if (paymentMethod != null && !paymentMethod.isBlank() && !"ALL".equalsIgnoreCase(paymentMethod)) {
                ps.setString(idx++, paymentMethod.toUpperCase());
            }
            if (startDate != null) {
                ps.setString(idx++, startDate.toString());
            }
            if (endDate != null) {
                ps.setString(idx++, endDate.toString());
            }
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pName = rs.getString("party_name");
                    if (pName == null || pName.isBlank()) {
                        pName = rs.getString("party_type") + " #" + rs.getLong("party_id");
                    }
                    list.add(new PaymentListItemDto(
                            rs.getLong("id"),
                            rs.getString("payment_number"),
                            rs.getString("party_type"),
                            rs.getLong("party_id"),
                            pName,
                            Money.ofPaisha(rs.getLong("amount_paisha")),
                            LocalDateTime.parse(rs.getString("payment_date").replace(" ", "T")),
                            rs.getString("payment_method"),
                            rs.getString("reference_trx_id"),
                            rs.getInt("is_reversed") == 1,
                            rs.getInt("allocation_count")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public int getNextPaymentSequence(Connection conn, long businessId, String datePrefix, String partyType) throws SQLException {
        String typeCode = "CUSTOMER".equalsIgnoreCase(partyType) ? "C" : "S";
        String pattern = "PAY-" + typeCode + "-" + datePrefix + "-%";
        String sql = "SELECT COUNT(*) FROM payments WHERE business_id = ? AND payment_number LIKE ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) + 1;
                }
            }
        }
        return 1;
    }

    @Override
    public void voidPayment(Connection conn, long paymentId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException {
        String sql = "UPDATE payments SET is_reversed = 1, reversal_reason = ?, reversed_at = ? " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, reversedAt.toString().replace("T", " "));
            ps.setLong(3, paymentId);
            ps.setLong(4, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void updateMetadata(Connection conn, long paymentId, long businessId, String paymentMethod, String referenceTrxId, String notes) throws SQLException {
        String sql = "UPDATE payments SET payment_method = ?, reference_trx_id = ?, notes = ? WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentMethod);
            if (referenceTrxId != null && !referenceTrxId.isBlank()) {
                ps.setString(2, referenceTrxId.trim());
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            if (notes != null && !notes.isBlank()) {
                ps.setString(3, notes.trim());
            } else {
                ps.setNull(3, Types.VARCHAR);
            }
            ps.setLong(4, paymentId);
            ps.setLong(5, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean deleteVoided(Connection conn, long paymentId, long businessId) throws SQLException {
        String partyType = null;
        long partyId = 0L;
        String lookupSql = "SELECT party_type, party_id FROM payments WHERE id = ? AND business_id = ? AND is_reversed = 1";
        try (PreparedStatement ps = conn.prepareStatement(lookupSql)) {
            ps.setLong(1, paymentId);
            ps.setLong(2, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                partyType = rs.getString("party_type");
                partyId = rs.getLong("party_id");
            }
        }

        if ("CUSTOMER".equalsIgnoreCase(partyType)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM customer_ledger WHERE business_id = ? AND customer_id = ? AND reference_id = ? AND entry_type IN ('PAYMENT_COLLECTION', 'ADJUSTMENT')")) {
                ps.setLong(1, businessId);
                ps.setLong(2, partyId);
                ps.setLong(3, paymentId);
                ps.executeUpdate();
            }
        } else if ("SUPPLIER".equalsIgnoreCase(partyType)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM supplier_ledger WHERE business_id = ? AND supplier_id = ? AND reference_id = ? AND entry_type IN ('PAYMENT_DISBURSEMENT', 'ADJUSTMENT')")) {
                ps.setLong(1, businessId);
                ps.setLong(2, partyId);
                ps.setLong(3, paymentId);
                ps.executeUpdate();
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM payments WHERE id = ? AND business_id = ? AND is_reversed = 1")) {
            ps.setLong(1, paymentId);
            ps.setLong(2, businessId);
            return ps.executeUpdate() > 0;
        }
    }

    private Payment mapRow(ResultSet rs) throws SQLException {
        String revAtStr = rs.getString("reversed_at");
        LocalDateTime revAt = (revAtStr != null && !revAtStr.isBlank()) ? LocalDateTime.parse(revAtStr.replace(" ", "T")) : null;

        return new Payment(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("payment_number"),
                rs.getString("party_type"),
                rs.getLong("party_id"),
                Money.ofPaisha(rs.getLong("amount_paisha")),
                LocalDateTime.parse(rs.getString("payment_date").replace(" ", "T")),
                rs.getString("payment_method"),
                rs.getString("reference_trx_id"),
                rs.getString("notes"),
                rs.getLong("created_by_user_id"),
                rs.getString("idempotency_key"),
                rs.getInt("is_reversed") == 1,
                rs.getString("reversal_reason"),
                revAt,
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T"))
        );
    }
}


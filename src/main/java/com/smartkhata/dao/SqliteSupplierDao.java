package com.smartkhata.dao;

import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteSupplierDao implements SupplierDao {

    private static final String BASE_COLUMNS =
            "id, business_id, name, contact_person, phone, address, opening_balance_paisha, " +
            "is_archived, notes, created_at, updated_at, supplier_code, email, payment_terms, credit_period_days, is_active";

    @Override
    public Supplier save(Connection conn, Supplier supplier) throws SQLException {
        String cleanCode = supplier.supplierCode() != null && !supplier.supplierCode().trim().isBlank()
                ? supplier.supplierCode().trim() : null;
        String cleanEmail = supplier.email() != null && !supplier.email().trim().isBlank()
                ? supplier.email().trim() : null;
        String cleanTerms = supplier.paymentTerms() != null && !supplier.paymentTerms().trim().isBlank()
                ? supplier.paymentTerms().trim() : null;
        int creditDays = Math.max(0, supplier.creditPeriodDays());
        int activeFlag = supplier.isActive() ? 1 : 0;

        if (supplier.id() == null) {
            String sql = "INSERT INTO suppliers (business_id, name, contact_person, phone, address, " +
                    "opening_balance_paisha, is_archived, notes, supplier_code, email, payment_terms, credit_period_days, is_active) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, supplier.businessId());
                ps.setString(2, supplier.name());
                ps.setString(3, supplier.contactPerson());
                ps.setString(4, supplier.phone());
                ps.setString(5, supplier.address());
                ps.setLong(6, supplier.openingBalance() != null ? supplier.openingBalance().paisha() : 0L);
                ps.setInt(7, supplier.isArchived() ? 1 : 0);
                ps.setString(8, supplier.notes());
                if (cleanCode != null) ps.setString(9, cleanCode); else ps.setNull(9, Types.VARCHAR);
                if (cleanEmail != null) ps.setString(10, cleanEmail); else ps.setNull(10, Types.VARCHAR);
                if (cleanTerms != null) ps.setString(11, cleanTerms); else ps.setNull(11, Types.VARCHAR);
                ps.setInt(12, creditDays);
                ps.setInt(13, activeFlag);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long newId = rs.getLong(1);
                        if (cleanCode == null) {
                            String autoCode = String.format("SUP-%05d", newId);
                            try (PreparedStatement psCode = conn.prepareStatement(
                                    "UPDATE suppliers SET supplier_code = ? WHERE id = ?")) {
                                psCode.setString(1, autoCode);
                                psCode.setLong(2, newId);
                                psCode.executeUpdate();
                            }
                            cleanCode = autoCode;
                        }
                        return supplier.withId(newId).withSupplierCode(cleanCode);
                    }
                }
            }
        } else {
            String sql = "UPDATE suppliers SET name = ?, contact_person = ?, phone = ?, address = ?, opening_balance_paisha = ?, " +
                    "is_archived = ?, notes = ?, supplier_code = ?, email = ?, payment_terms = ?, credit_period_days = ?, is_active = ?, " +
                    "updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, supplier.name());
                ps.setString(2, supplier.contactPerson());
                ps.setString(3, supplier.phone());
                ps.setString(4, supplier.address());
                ps.setLong(5, supplier.openingBalance() != null ? supplier.openingBalance().paisha() : 0L);
                ps.setInt(6, supplier.isArchived() ? 1 : 0);
                ps.setString(7, supplier.notes());
                if (cleanCode != null) ps.setString(8, cleanCode); else ps.setNull(8, Types.VARCHAR);
                if (cleanEmail != null) ps.setString(9, cleanEmail); else ps.setNull(9, Types.VARCHAR);
                if (cleanTerms != null) ps.setString(10, cleanTerms); else ps.setNull(10, Types.VARCHAR);
                ps.setInt(11, creditDays);
                ps.setInt(12, activeFlag);
                ps.setLong(13, supplier.id());
                ps.setLong(14, supplier.businessId());
                ps.executeUpdate();
            }
        }
        return supplier;
    }

    @Override
    public Optional<Supplier> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM suppliers WHERE id = ?";
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
    public List<Supplier> findAll(Connection conn, long businessId) throws SQLException {
        return search(conn, businessId, null, false);
    }

    @Override
    public List<Supplier> search(Connection conn, long businessId, String query, boolean includeArchived) throws SQLException {
        List<Supplier> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM suppliers WHERE business_id = ?");

        if (!includeArchived) {
            sql.append(" AND is_archived = 0");
        }

        boolean hasQuery = query != null && !query.trim().isBlank();
        if (hasQuery) {
            sql.append(" AND (name LIKE ? OR contact_person LIKE ? OR phone LIKE ? OR COALESCE(supplier_code, '') LIKE ? OR COALESCE(email, '') LIKE ?)");
        }
        sql.append(" ORDER BY name COLLATE NOCASE ASC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    @Override
    public Optional<Supplier> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " " +
                "FROM suppliers WHERE business_id = ? AND name = ? COLLATE NOCASE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, name != null ? name.trim() : "");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean hasLinkedFinancialRecords(Connection conn, long supplierId) throws SQLException {
        // Checks purchases, purchase orders, GRNs, supplier invoices, payments, or non-opening ledger entries
        String sql = "SELECT (EXISTS(SELECT 1 FROM purchases WHERE supplier_id = ?) OR " +
                "EXISTS(SELECT 1 FROM purchase_orders WHERE supplier_id = ?) OR " +
                "EXISTS(SELECT 1 FROM goods_receipt_notes WHERE supplier_id = ?) OR " +
                "EXISTS(SELECT 1 FROM supplier_invoices WHERE supplier_id = ?) OR " +
                "EXISTS(SELECT 1 FROM payments WHERE party_type = 'SUPPLIER' AND party_id = ?) OR " +
                "EXISTS(SELECT 1 FROM supplier_ledger WHERE supplier_id = ? AND entry_type != 'OPENING_BALANCE'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            ps.setLong(2, supplierId);
            ps.setLong(3, supplierId);
            ps.setLong(4, supplierId);
            ps.setLong(5, supplierId);
            ps.setLong(6, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 1;
                }
            }
        }
        return false;
    }

    @Override
    public void setArchived(Connection conn, long supplierId, long businessId, boolean archived) throws SQLException {
        String sql = "UPDATE suppliers SET is_archived = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, archived ? 1 : 0);
            ps.setLong(2, supplierId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(Connection conn, long supplierId, long businessId) throws SQLException {
        try (PreparedStatement psLedger = conn.prepareStatement(
                "DELETE FROM supplier_ledger WHERE supplier_id = ? AND business_id = ?")) {
            psLedger.setLong(1, supplierId);
            psLedger.setLong(2, businessId);
            psLedger.executeUpdate();
        }
        try (PreparedStatement psSupplier = conn.prepareStatement(
                "DELETE FROM suppliers WHERE id = ? AND business_id = ?")) {
            psSupplier.setLong(1, supplierId);
            psSupplier.setLong(2, businessId);
            psSupplier.executeUpdate();
        }
    }

    @Override
    public Money getTotalOutstandingPayables(Connection conn, long businessId) throws SQLException {
        // Supplier payables: credit increases payable, debit decreases payable
        String sql = "SELECT COALESCE(SUM(credit_paisha) - SUM(debit_paisha), 0) FROM supplier_ledger WHERE business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Money.ofPaisha(rs.getLong(1));
                }
            }
        }
        return Money.ZERO;
    }

    private Supplier mapRow(ResultSet rs) throws SQLException {
        return new Supplier(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("name"),
                rs.getString("contact_person"),
                rs.getString("phone"),
                rs.getString("address"),
                Money.ofPaisha(rs.getLong("opening_balance_paisha")),
                rs.getInt("is_archived") == 1,
                rs.getString("notes"),
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T")),
                rs.getString("supplier_code"),
                rs.getString("email"),
                rs.getString("payment_terms"),
                rs.getInt("credit_period_days"),
                rs.getInt("is_active") == 1
        );
    }
}


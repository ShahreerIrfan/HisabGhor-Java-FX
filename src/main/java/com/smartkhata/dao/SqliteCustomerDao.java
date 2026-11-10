package com.smartkhata.dao;

import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteCustomerDao implements CustomerDao {

    @Override
    public Customer save(Connection conn, Customer customer) throws SQLException {
        if (customer.id() == null) {
            String sql = "INSERT INTO customers (business_id, name, phone, address, credit_limit_paisha, opening_balance_paisha, is_archived, notes) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, customer.businessId());
                ps.setString(2, customer.name());
                ps.setString(3, customer.phone());
                ps.setString(4, customer.address());
                ps.setLong(5, customer.creditLimit() != null ? customer.creditLimit().paisha() : 0L);
                ps.setLong(6, customer.openingBalance() != null ? customer.openingBalance().paisha() : 0L);
                ps.setInt(7, customer.isArchived() ? 1 : 0);
                ps.setString(8, customer.notes());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return customer.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE customers SET name = ?, phone = ?, address = ?, credit_limit_paisha = ?, opening_balance_paisha = ?, " +
                    "is_archived = ?, notes = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, customer.name());
                ps.setString(2, customer.phone());
                ps.setString(3, customer.address());
                ps.setLong(4, customer.creditLimit() != null ? customer.creditLimit().paisha() : 0L);
                ps.setLong(5, customer.openingBalance() != null ? customer.openingBalance().paisha() : 0L);
                ps.setInt(6, customer.isArchived() ? 1 : 0);
                ps.setString(7, customer.notes());
                ps.setLong(8, customer.id());
                ps.setLong(9, customer.businessId());
                ps.executeUpdate();
            }
        }
        return customer;
    }

    @Override
    public Optional<Customer> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, business_id, name, phone, address, credit_limit_paisha, opening_balance_paisha, is_archived, notes, created_at, updated_at FROM customers WHERE id = ?";
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
    public List<Customer> findAll(Connection conn, long businessId) throws SQLException {
        return search(conn, businessId, null, false);
    }

    @Override
    public List<Customer> search(Connection conn, long businessId, String query, boolean includeArchived) throws SQLException {
        List<Customer> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, business_id, name, phone, address, credit_limit_paisha, opening_balance_paisha, " +
                "is_archived, notes, created_at, updated_at FROM customers WHERE business_id = ?");

        if (!includeArchived) {
            sql.append(" AND is_archived = 0");
        }

        boolean hasQuery = query != null && !query.trim().isBlank();
        if (hasQuery) {
            sql.append(" AND (name LIKE ? OR phone LIKE ?)");
        }
        sql.append(" ORDER BY name COLLATE NOCASE ASC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, businessId);
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(2, pattern);
                ps.setString(3, pattern);
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
    public Optional<Customer> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException {
        String sql = "SELECT id, business_id, name, phone, address, credit_limit_paisha, opening_balance_paisha, is_archived, notes, created_at, updated_at " +
                "FROM customers WHERE business_id = ? AND name = ? COLLATE NOCASE";
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
    public boolean hasLinkedFinancialRecords(Connection conn, long customerId) throws SQLException {
        // Checks sales, payments, or non-opening ledger entries
        String sql = "SELECT (EXISTS(SELECT 1 FROM sales WHERE customer_id = ?) OR " +
                "EXISTS(SELECT 1 FROM payments WHERE party_type = 'CUSTOMER' AND party_id = ?) OR " +
                "EXISTS(SELECT 1 FROM customer_ledger WHERE customer_id = ? AND entry_type != 'OPENING_BALANCE'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            ps.setLong(2, customerId);
            ps.setLong(3, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 1;
                }
            }
        }
        return false;
    }

    @Override
    public void setArchived(Connection conn, long customerId, long businessId, boolean archived) throws SQLException {
        String sql = "UPDATE customers SET is_archived = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, archived ? 1 : 0);
            ps.setLong(2, customerId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(Connection conn, long customerId, long businessId) throws SQLException {
        try (PreparedStatement psLedger = conn.prepareStatement(
                "DELETE FROM customer_ledger WHERE customer_id = ? AND business_id = ?")) {
            psLedger.setLong(1, customerId);
            psLedger.setLong(2, businessId);
            psLedger.executeUpdate();
        }
        try (PreparedStatement psCustomer = conn.prepareStatement(
                "DELETE FROM customers WHERE id = ? AND business_id = ?")) {
            psCustomer.setLong(1, customerId);
            psCustomer.setLong(2, businessId);
            psCustomer.executeUpdate();
        }
    }

    @Override
    public Money getTotalOutstanding(Connection conn, long businessId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(debit_paisha) - SUM(credit_paisha), 0) FROM customer_ledger WHERE business_id = ?";
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

    private Customer mapRow(ResultSet rs) throws SQLException {
        return new Customer(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("address"),
                Money.ofPaisha(rs.getLong("credit_limit_paisha")),
                Money.ofPaisha(rs.getLong("opening_balance_paisha")),
                rs.getInt("is_archived") == 1,
                rs.getString("notes"),
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T"))
        );
    }
}

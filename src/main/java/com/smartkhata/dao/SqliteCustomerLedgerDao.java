package com.smartkhata.dao;

import com.smartkhata.model.entity.CustomerLedgerEntry;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SqliteCustomerLedgerDao implements CustomerLedgerDao {

    @Override
    public CustomerLedgerEntry save(Connection conn, CustomerLedgerEntry entry) throws SQLException {
        String sql = "INSERT INTO customer_ledger (business_id, customer_id, entry_date, entry_type, reference_id, " +
                "debit_paisha, credit_paisha, running_balance_paisha, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, entry.businessId());
            ps.setLong(2, entry.customerId());
            ps.setString(3, entry.entryDate() != null ? entry.entryDate().toString().replace("T", " ") : null);
            ps.setString(4, entry.entryType());
            if (entry.referenceId() != null) ps.setLong(5, entry.referenceId()); else ps.setNull(5, Types.INTEGER);
            ps.setLong(6, entry.debit() != null ? entry.debit().paisha() : 0L);
            ps.setLong(7, entry.credit() != null ? entry.credit().paisha() : 0L);
            ps.setLong(8, entry.runningBalance() != null ? entry.runningBalance().paisha() : 0L);
            ps.setString(9, entry.notes());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return entry.withId(rs.getLong(1));
                }
            }
        }
        return entry;
    }

    @Override
    public Money getCurrentBalance(Connection conn, long customerId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(debit_paisha) - SUM(credit_paisha), 0) FROM customer_ledger WHERE customer_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Money.ofPaisha(rs.getLong(1));
                }
            }
        }
        return Money.ZERO;
    }

    @Override
    public List<CustomerLedgerEntry> findByCustomerId(Connection conn, long customerId) throws SQLException {
        List<CustomerLedgerEntry> list = new ArrayList<>();
        String sql = "SELECT id, business_id, customer_id, entry_date, entry_type, reference_id, debit_paisha, " +
                "credit_paisha, running_balance_paisha, notes, created_at FROM customer_ledger WHERE customer_id = ? ORDER BY entry_date ASC, id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long refId = rs.getObject("reference_id") != null ? rs.getLong("reference_id") : null;
                    list.add(new CustomerLedgerEntry(
                            rs.getLong("id"),
                            rs.getLong("business_id"),
                            rs.getLong("customer_id"),
                            LocalDateTime.parse(rs.getString("entry_date").replace(" ", "T")),
                            rs.getString("entry_type"),
                            refId,
                            Money.ofPaisha(rs.getLong("debit_paisha")),
                            Money.ofPaisha(rs.getLong("credit_paisha")),
                            Money.ofPaisha(rs.getLong("running_balance_paisha")),
                            rs.getString("notes"),
                            LocalDateTime.parse(rs.getString("created_at").replace(" ", "T"))
                    ));
                }
            }
        }
        return list;
    }
}


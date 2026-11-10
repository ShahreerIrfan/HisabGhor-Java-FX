package com.smartkhata.dao;

import com.smartkhata.model.entity.OtherIncome;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteOtherIncomeDao implements OtherIncomeDao {

    @Override
    public OtherIncome save(Connection conn, OtherIncome income) throws SQLException {
        if (income.id() == null) {
            String sql = "INSERT INTO other_incomes (business_id, source_name, amount_paisha, income_date, " +
                    "payment_method, notes, created_by_user_id, is_reversed, reversal_reason, reversed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, income.businessId());
                ps.setString(2, income.sourceName());
                ps.setLong(3, income.amount().paisha());
                ps.setString(4, income.incomeDate() != null ? income.incomeDate().toString().replace("T", " ") : null);
                ps.setString(5, income.paymentMethod());
                ps.setString(6, income.notes());
                ps.setLong(7, income.createdByUserId());
                ps.setInt(8, income.isReversed() ? 1 : 0);
                ps.setString(9, income.reversalReason());
                ps.setString(10, income.reversedAt() != null ? income.reversedAt().toString().replace("T", " ") : null);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long generatedId = rs.getLong(1);
                        return new OtherIncome(
                                generatedId,
                                income.businessId(),
                                income.sourceName(),
                                income.amount(),
                                income.incomeDate(),
                                income.paymentMethod(),
                                income.notes(),
                                income.createdByUserId(),
                                income.isReversed(),
                                income.reversalReason(),
                                income.reversedAt(),
                                income.createdAt() != null ? income.createdAt() : LocalDateTime.now()
                        );
                    }
                }
            }
        } else {
            String sql = "UPDATE other_incomes SET source_name = ?, amount_paisha = ?, income_date = ?, " +
                    "payment_method = ?, notes = ?, is_reversed = ?, reversal_reason = ?, reversed_at = ? " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, income.sourceName());
                ps.setLong(2, income.amount().paisha());
                ps.setString(3, income.incomeDate() != null ? income.incomeDate().toString().replace("T", " ") : null);
                ps.setString(4, income.paymentMethod());
                ps.setString(5, income.notes());
                ps.setInt(6, income.isReversed() ? 1 : 0);
                ps.setString(7, income.reversalReason());
                ps.setString(8, income.reversedAt() != null ? income.reversedAt().toString().replace("T", " ") : null);
                ps.setLong(9, income.id());
                ps.setLong(10, income.businessId());
                ps.executeUpdate();
            }
        }
        return income;
    }

    @Override
    public Optional<OtherIncome> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM other_incomes WHERE id = ?";
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
    public List<OtherIncome> search(Connection conn, long businessId, String query,
                                    LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM other_incomes WHERE business_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(businessId);

        if (query != null && !query.isBlank()) {
            sql.append(" AND (source_name LIKE ? OR notes LIKE ?)");
            String pattern = "%" + query.trim() + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (startDate != null) {
            sql.append(" AND income_date >= ?");
            params.add(startDate.toString() + " 00:00:00");
        }
        if (endDate != null) {
            sql.append(" AND income_date <= ?");
            params.add(endDate.toString() + " 23:59:59");
        }

        sql.append(" ORDER BY income_date DESC, id DESC");

        List<OtherIncome> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }
        return result;
    }

    @Override
    public Money getTotalOtherIncome(Connection conn, long businessId, LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount_paisha), 0) FROM other_incomes WHERE business_id = ? AND is_reversed = 0");
        List<Object> params = new ArrayList<>();
        params.add(businessId);

        if (startDate != null) {
            sql.append(" AND income_date >= ?");
            params.add(startDate.toString() + " 00:00:00");
        }
        if (endDate != null) {
            sql.append(" AND income_date <= ?");
            params.add(endDate.toString() + " 23:59:59");
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Money.ofPaisha(rs.getLong(1));
                }
            }
        }
        return Money.ZERO;
    }

    @Override
    public void voidOtherIncome(Connection conn, long incomeId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException {
        String sql = "UPDATE other_incomes SET is_reversed = 1, reversal_reason = ?, reversed_at = ? " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, reversedAt != null ? reversedAt.toString().replace("T", " ") : null);
            ps.setLong(3, incomeId);
            ps.setLong(4, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void deleteVoided(Connection conn, long incomeId, long businessId) throws SQLException {
        String sql = "DELETE FROM other_incomes WHERE id = ? AND business_id = ? AND is_reversed = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, incomeId);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    private OtherIncome mapRow(ResultSet rs) throws SQLException {
        String revAtStr = rs.getString("reversed_at");
        LocalDateTime revAt = (revAtStr != null && !revAtStr.isBlank())
                ? LocalDateTime.parse(revAtStr.replace(" ", "T"))
                : null;

        String createdAtStr = rs.getString("created_at");
        LocalDateTime createdAt = (createdAtStr != null && !createdAtStr.isBlank())
                ? LocalDateTime.parse(createdAtStr.replace(" ", "T"))
                : LocalDateTime.now();

        return new OtherIncome(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("source_name"),
                Money.ofPaisha(rs.getLong("amount_paisha")),
                LocalDateTime.parse(rs.getString("income_date").replace(" ", "T")),
                rs.getString("payment_method"),
                rs.getString("notes"),
                rs.getLong("created_by_user_id"),
                rs.getInt("is_reversed") == 1,
                rs.getString("reversal_reason"),
                revAt,
                createdAt
        );
    }
}


package com.smartkhata.dao;

import com.smartkhata.model.dto.ExpenseCategorySummaryDto;
import com.smartkhata.model.entity.Expense;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteExpenseDao implements ExpenseDao {

    @Override
    public Expense save(Connection conn, Expense expense) throws SQLException {
        if (expense.id() == null) {
            String sql = "INSERT INTO expenses (business_id, category, amount_paisha, expense_date, " +
                    "payment_method, payee_name, notes, created_by_user_id, is_reversed, reversal_reason, reversed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, expense.businessId());
                ps.setString(2, expense.category());
                ps.setLong(3, expense.amount().paisha());
                ps.setString(4, expense.expenseDate() != null ? expense.expenseDate().toString().replace("T", " ") : null);
                ps.setString(5, expense.paymentMethod());
                ps.setString(6, expense.payeeName());
                ps.setString(7, expense.notes());
                ps.setLong(8, expense.createdByUserId());
                ps.setInt(9, expense.isReversed() ? 1 : 0);
                ps.setString(10, expense.reversalReason());
                ps.setString(11, expense.reversedAt() != null ? expense.reversedAt().toString().replace("T", " ") : null);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long generatedId = rs.getLong(1);
                        return new Expense(
                                generatedId,
                                expense.businessId(),
                                expense.category(),
                                expense.amount(),
                                expense.expenseDate(),
                                expense.paymentMethod(),
                                expense.payeeName(),
                                expense.notes(),
                                expense.createdByUserId(),
                                expense.isReversed(),
                                expense.reversalReason(),
                                expense.reversedAt(),
                                expense.createdAt() != null ? expense.createdAt() : LocalDateTime.now()
                        );
                    }
                }
            }
        } else {
            String sql = "UPDATE expenses SET category = ?, amount_paisha = ?, expense_date = ?, " +
                    "payment_method = ?, payee_name = ?, notes = ?, is_reversed = ?, reversal_reason = ?, reversed_at = ? " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, expense.category());
                ps.setLong(2, expense.amount().paisha());
                ps.setString(3, expense.expenseDate() != null ? expense.expenseDate().toString().replace("T", " ") : null);
                ps.setString(4, expense.paymentMethod());
                ps.setString(5, expense.payeeName());
                ps.setString(6, expense.notes());
                ps.setInt(7, expense.isReversed() ? 1 : 0);
                ps.setString(8, expense.reversalReason());
                ps.setString(9, expense.reversedAt() != null ? expense.reversedAt().toString().replace("T", " ") : null);
                ps.setLong(10, expense.id());
                ps.setLong(11, expense.businessId());
                ps.executeUpdate();
            }
        }
        return expense;
    }

    @Override
    public Optional<Expense> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM expenses WHERE id = ?";
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
    public List<Expense> search(Connection conn, long businessId, String query, String category,
                                 String paymentMethod, LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM expenses WHERE business_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(businessId);

        if (query != null && !query.isBlank()) {
            sql.append(" AND (payee_name LIKE ? OR notes LIKE ?)");
            String pattern = "%" + query.trim() + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category.trim());
        }
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            sql.append(" AND payment_method = ?");
            params.add(paymentMethod.trim());
        }
        if (startDate != null) {
            sql.append(" AND expense_date >= ?");
            params.add(startDate.toString() + " 00:00:00");
        }
        if (endDate != null) {
            sql.append(" AND expense_date <= ?");
            params.add(endDate.toString() + " 23:59:59");
        }

        sql.append(" ORDER BY expense_date DESC, id DESC");

        List<Expense> result = new ArrayList<>();
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
    public Money getTotalExpenses(Connection conn, long businessId, LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount_paisha), 0) FROM expenses WHERE business_id = ? AND is_reversed = 0");
        List<Object> params = new ArrayList<>();
        params.add(businessId);

        if (startDate != null) {
            sql.append(" AND expense_date >= ?");
            params.add(startDate.toString() + " 00:00:00");
        }
        if (endDate != null) {
            sql.append(" AND expense_date <= ?");
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
    public List<ExpenseCategorySummaryDto> getCategoryBreakdown(Connection conn, long businessId,
                                                                 LocalDate startDate, LocalDate endDate) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT category, COALESCE(SUM(amount_paisha), 0) as total_paisha, COUNT(*) as cnt " +
                "FROM expenses WHERE business_id = ? AND is_reversed = 0");
        List<Object> params = new ArrayList<>();
        params.add(businessId);

        if (startDate != null) {
            sql.append(" AND expense_date >= ?");
            params.add(startDate.toString() + " 00:00:00");
        }
        if (endDate != null) {
            sql.append(" AND expense_date <= ?");
            params.add(endDate.toString() + " 23:59:59");
        }

        sql.append(" GROUP BY category ORDER BY total_paisha DESC");

        List<ExpenseCategorySummaryDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ExpenseCategorySummaryDto(
                            rs.getString("category"),
                            Money.ofPaisha(rs.getLong("total_paisha")),
                            rs.getInt("cnt")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public void voidExpense(Connection conn, long expenseId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException {
        String sql = "UPDATE expenses SET is_reversed = 1, reversal_reason = ?, reversed_at = ? " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, reversedAt != null ? reversedAt.toString().replace("T", " ") : null);
            ps.setLong(3, expenseId);
            ps.setLong(4, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void deleteVoided(Connection conn, long expenseId, long businessId) throws SQLException {
        String sql = "DELETE FROM expenses WHERE id = ? AND business_id = ? AND is_reversed = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, expenseId);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    private Expense mapRow(ResultSet rs) throws SQLException {
        String revAtStr = rs.getString("reversed_at");
        LocalDateTime revAt = (revAtStr != null && !revAtStr.isBlank())
                ? LocalDateTime.parse(revAtStr.replace(" ", "T"))
                : null;

        String createdAtStr = rs.getString("created_at");
        LocalDateTime createdAt = (createdAtStr != null && !createdAtStr.isBlank())
                ? LocalDateTime.parse(createdAtStr.replace(" ", "T"))
                : LocalDateTime.now();

        return new Expense(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("category"),
                Money.ofPaisha(rs.getLong("amount_paisha")),
                LocalDateTime.parse(rs.getString("expense_date").replace(" ", "T")),
                rs.getString("payment_method"),
                rs.getString("payee_name"),
                rs.getString("notes"),
                rs.getLong("created_by_user_id"),
                rs.getInt("is_reversed") == 1,
                rs.getString("reversal_reason"),
                revAt,
                createdAt
        );
    }
}


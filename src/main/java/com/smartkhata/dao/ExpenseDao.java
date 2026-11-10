package com.smartkhata.dao;

import com.smartkhata.model.dto.ExpenseCategorySummaryDto;
import com.smartkhata.model.entity.Expense;
import com.smartkhata.model.vo.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExpenseDao {

    Expense save(Connection conn, Expense expense) throws SQLException;

    Optional<Expense> findById(Connection conn, long id) throws SQLException;

    List<Expense> search(Connection conn, long businessId, String query, String category,
                         String paymentMethod, LocalDate startDate, LocalDate endDate) throws SQLException;

    Money getTotalExpenses(Connection conn, long businessId, LocalDate startDate, LocalDate endDate) throws SQLException;

    List<ExpenseCategorySummaryDto> getCategoryBreakdown(Connection conn, long businessId,
                                                         LocalDate startDate, LocalDate endDate) throws SQLException;

    void voidExpense(Connection conn, long expenseId, long businessId, String reason,
                     LocalDateTime reversedAt) throws SQLException;

    void deleteVoided(Connection conn, long expenseId, long businessId) throws SQLException;
}


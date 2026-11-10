package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Expense;
import com.smartkhata.model.entity.OtherIncome;
import com.smartkhata.model.vo.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExpenseService {

    Expense recordExpense(ExpenseCreateDto dto);

    Expense updateExpense(ExpenseUpdateDto dto);

    void voidExpense(long expenseId, String reason, long actingUserId);

    void deleteVoidedExpense(long expenseId);

    Optional<Expense> getExpenseById(long expenseId);

    OtherIncome recordOtherIncome(OtherIncomeCreateDto dto);

    OtherIncome updateOtherIncome(OtherIncomeUpdateDto dto);

    void voidOtherIncome(long incomeId, String reason, long actingUserId);

    void deleteVoidedOtherIncome(long incomeId);

    Optional<OtherIncome> getOtherIncomeById(long incomeId);

    List<Expense> searchExpenses(long businessId, String query, String category,
                                 String paymentMethod, LocalDate startDate, LocalDate endDate);

    List<OtherIncome> searchOtherIncomes(long businessId, String query,
                                         LocalDate startDate, LocalDate endDate);

    CashFlowSummaryDto getCashFlowSummary(long businessId, LocalDate startDate, LocalDate endDate);

    List<ExpenseCategorySummaryDto> getExpenseBreakdownByCategory(long businessId, LocalDate startDate, LocalDate endDate);

    Money getTotalExpenses(long businessId, LocalDate startDate, LocalDate endDate);

    Money getTotalOtherIncome(long businessId, LocalDate startDate, LocalDate endDate);
}


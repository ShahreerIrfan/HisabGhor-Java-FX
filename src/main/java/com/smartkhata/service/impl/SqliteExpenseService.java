package com.smartkhata.service.impl;

import com.smartkhata.dao.ExpenseDao;
import com.smartkhata.dao.OtherIncomeDao;
import com.smartkhata.dao.SqliteExpenseDao;
import com.smartkhata.dao.SqliteOtherIncomeDao;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Expense;
import com.smartkhata.model.entity.OtherIncome;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.ExpenseService;
import com.smartkhata.service.api.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class SqliteExpenseService implements ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(SqliteExpenseService.class);

    private static final Set<String> VALID_EXPENSE_CATEGORIES = Set.of(
            "RENT", "UTILITIES", "SALARY", "TRANSPORT", "ENTERTAINMENT", "MAINTENANCE", "OTHER"
    );

    private static final Set<String> VALID_PAYMENT_METHODS = Set.of(
            "CASH", "BANK", "BKASH", "NAGAD", "ROCKET", "OTHER"
    );

    private final ExpenseDao expenseDao;
    private final OtherIncomeDao otherIncomeDao;

    public SqliteExpenseService() {
        this(new SqliteExpenseDao(), new SqliteOtherIncomeDao());
    }

    public SqliteExpenseService(ExpenseDao expenseDao, OtherIncomeDao otherIncomeDao) {
        this.expenseDao = expenseDao;
        this.otherIncomeDao = otherIncomeDao;
    }

    @Override
    public Expense recordExpense(ExpenseCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.amount() == null || !dto.amount().isPositive()) {
            throw new IllegalArgumentException("Expense amount must be greater than zero.");
        }

        String category = dto.category().trim().toUpperCase();
        if (!VALID_EXPENSE_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Invalid expense category: " + dto.category() +
                    ". Allowed: " + VALID_EXPENSE_CATEGORIES);
        }

        String method = dto.paymentMethod().trim().toUpperCase();
        if (!VALID_PAYMENT_METHODS.contains(method)) {
            throw new IllegalArgumentException("Invalid payment method: " + dto.paymentMethod() +
                    ". Allowed: " + VALID_PAYMENT_METHODS);
        }

        LocalDateTime date = dto.expenseDate() != null ? dto.expenseDate() : LocalDateTime.now();

        return TransactionManager.executeInTransaction(conn -> {
            Expense expense = new Expense(
                    null,
                    dto.businessId(),
                    category,
                    dto.amount(),
                    date,
                    method,
                    dto.payeeName() != null ? dto.payeeName().trim() : null,
                    dto.notes() != null ? dto.notes().trim() : null,
                    dto.actingUserId(),
                    false,
                    null,
                    null,
                    LocalDateTime.now()
            );
            Expense saved = expenseDao.save(conn, expense);
            log.info("Recorded business expense id [{}] category [{}] amount [{}]",
                    saved.id(), saved.category(), saved.amount().toString());
            return saved;
        });
    }

    @Override
    public Expense updateExpense(ExpenseUpdateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.amount() == null || !dto.amount().isPositive()) {
            throw new IllegalArgumentException("Expense amount must be greater than zero.");
        }
        String category = dto.category().trim().toUpperCase();
        if (!VALID_EXPENSE_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Invalid expense category: " + dto.category() +
                    ". Allowed: " + VALID_EXPENSE_CATEGORIES);
        }
        String method = dto.paymentMethod().trim().toUpperCase();
        if (!VALID_PAYMENT_METHODS.contains(method)) {
            throw new IllegalArgumentException("Invalid payment method: " + dto.paymentMethod() +
                    ". Allowed: " + VALID_PAYMENT_METHODS);
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Expense> opt = expenseDao.findById(conn, dto.expenseId());
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Expense not found: " + dto.expenseId());
            }
            Expense existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: expense belongs to another business.");
            }
            if (existing.isReversed()) {
                throw new IllegalStateException("Cannot edit a voided expense voucher.");
            }
            Expense updated = new Expense(
                    existing.id(),
                    existing.businessId(),
                    category,
                    dto.amount(),
                    dto.expenseDate() != null ? dto.expenseDate() : existing.expenseDate(),
                    method,
                    dto.payeeName() != null ? dto.payeeName().trim() : null,
                    dto.notes() != null ? dto.notes().trim() : null,
                    existing.createdByUserId(),
                    false,
                    null,
                    null,
                    existing.createdAt()
            );
            return expenseDao.save(conn, updated);
        });
    }

    @Override
    public void voidExpense(long expenseId, String reason, long actingUserId) {
        SessionContext.requireAuthenticated();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A valid audit reason is required to void an expense.");
        }

        TransactionManager.executeInTransaction(conn -> {
            Optional<Expense> opt = expenseDao.findById(conn, expenseId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Expense not found: " + expenseId);
            }
            Expense exp = opt.get();
            if (exp.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: expense belongs to another business.");
            }
            if (exp.isReversed()) {
                throw new IllegalStateException("Expense has already been voided / reversed.");
            }

            expenseDao.voidExpense(conn, expenseId, exp.businessId(), reason.trim(), LocalDateTime.now());
            log.info("Voided expense [{}] by user [{}]. Reason: {}", expenseId, actingUserId, reason);
            return null;
        });
    }

    @Override
    public void deleteVoidedExpense(long expenseId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Expense> opt = expenseDao.findById(conn, expenseId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Expense not found: " + expenseId);
            }
            Expense exp = opt.get();
            if (exp.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: expense belongs to another business.");
            }
            if (!exp.isReversed()) {
                throw new IllegalStateException("Only VOIDED expense vouchers can be deleted. Void this expense first.");
            }
            expenseDao.deleteVoided(conn, expenseId, exp.businessId());
            log.info("Deleted voided expense [{}] from business [{}]", expenseId, exp.businessId());
        });
    }

    @Override
    public Optional<Expense> getExpenseById(long expenseId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Expense> opt = expenseDao.findById(conn, expenseId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied.");
            }
            return opt;
        } catch (SQLException e) {
            log.error("Failed to fetch expense [{}]: {}", expenseId, e.getMessage(), e);
            throw new DatabaseException("Failed to fetch expense", e);
        }
    }

    @Override
    public OtherIncome recordOtherIncome(OtherIncomeCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.amount() == null || !dto.amount().isPositive()) {
            throw new IllegalArgumentException("Income amount must be greater than zero.");
        }
        if (dto.sourceName() == null || dto.sourceName().isBlank()) {
            throw new IllegalArgumentException("Income source name is required.");
        }

        String method = dto.paymentMethod().trim().toUpperCase();
        if (!VALID_PAYMENT_METHODS.contains(method)) {
            throw new IllegalArgumentException("Invalid payment method: " + dto.paymentMethod() +
                    ". Allowed: " + VALID_PAYMENT_METHODS);
        }

        LocalDateTime date = dto.incomeDate() != null ? dto.incomeDate() : LocalDateTime.now();

        return TransactionManager.executeInTransaction(conn -> {
            OtherIncome income = new OtherIncome(
                    null,
                    dto.businessId(),
                    dto.sourceName().trim(),
                    dto.amount(),
                    date,
                    method,
                    dto.notes() != null ? dto.notes().trim() : null,
                    dto.actingUserId(),
                    false,
                    null,
                    null,
                    LocalDateTime.now()
            );
            OtherIncome saved = otherIncomeDao.save(conn, income);
            log.info("Recorded other income id [{}] source [{}] amount [{}]",
                    saved.id(), saved.sourceName(), saved.amount().toString());
            return saved;
        });
    }

    @Override
    public OtherIncome updateOtherIncome(OtherIncomeUpdateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.amount() == null || !dto.amount().isPositive()) {
            throw new IllegalArgumentException("Income amount must be greater than zero.");
        }
        if (dto.sourceName() == null || dto.sourceName().isBlank()) {
            throw new IllegalArgumentException("Income source name is required.");
        }
        String method = dto.paymentMethod().trim().toUpperCase();
        if (!VALID_PAYMENT_METHODS.contains(method)) {
            throw new IllegalArgumentException("Invalid payment method: " + dto.paymentMethod() +
                    ". Allowed: " + VALID_PAYMENT_METHODS);
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<OtherIncome> opt = otherIncomeDao.findById(conn, dto.incomeId());
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Other income record not found: " + dto.incomeId());
            }
            OtherIncome existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: record belongs to another business.");
            }
            if (existing.isReversed()) {
                throw new IllegalStateException("Cannot edit a voided other income voucher.");
            }
            OtherIncome updated = new OtherIncome(
                    existing.id(),
                    existing.businessId(),
                    dto.sourceName().trim(),
                    dto.amount(),
                    dto.incomeDate() != null ? dto.incomeDate() : existing.incomeDate(),
                    method,
                    dto.notes() != null ? dto.notes().trim() : null,
                    existing.createdByUserId(),
                    false,
                    null,
                    null,
                    existing.createdAt()
            );
            return otherIncomeDao.save(conn, updated);
        });
    }

    @Override
    public void voidOtherIncome(long incomeId, String reason, long actingUserId) {
        SessionContext.requireAuthenticated();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A valid audit reason is required to void other income.");
        }

        TransactionManager.executeInTransaction(conn -> {
            Optional<OtherIncome> opt = otherIncomeDao.findById(conn, incomeId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Other income record not found: " + incomeId);
            }
            OtherIncome inc = opt.get();
            if (inc.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: record belongs to another business.");
            }
            if (inc.isReversed()) {
                throw new IllegalStateException("Other income record has already been voided / reversed.");
            }

            otherIncomeDao.voidOtherIncome(conn, incomeId, inc.businessId(), reason.trim(), LocalDateTime.now());
            log.info("Voided other income [{}] by user [{}]. Reason: {}", incomeId, actingUserId, reason);
            return null;
        });
    }

    @Override
    public void deleteVoidedOtherIncome(long incomeId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<OtherIncome> opt = otherIncomeDao.findById(conn, incomeId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Other income record not found: " + incomeId);
            }
            OtherIncome inc = opt.get();
            if (inc.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: record belongs to another business.");
            }
            if (!inc.isReversed()) {
                throw new IllegalStateException("Only VOIDED other income vouchers can be deleted. Void this entry first.");
            }
            otherIncomeDao.deleteVoided(conn, incomeId, inc.businessId());
            log.info("Deleted voided other income [{}] from business [{}]", incomeId, inc.businessId());
        });
    }

    @Override
    public Optional<OtherIncome> getOtherIncomeById(long incomeId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<OtherIncome> opt = otherIncomeDao.findById(conn, incomeId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied.");
            }
            return opt;
        } catch (SQLException e) {
            log.error("Failed to fetch other income [{}]: {}", incomeId, e.getMessage(), e);
            throw new DatabaseException("Failed to fetch other income", e);
        }
    }

    @Override
    public List<Expense> searchExpenses(long businessId, String query, String category,
                                         String paymentMethod, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return expenseDao.search(conn, businessId, query, category, paymentMethod, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to search expenses: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to search expenses", e);
        }
    }

    @Override
    public List<OtherIncome> searchOtherIncomes(long businessId, String query,
                                                 LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return otherIncomeDao.search(conn, businessId, query, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to search other incomes: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to search other incomes", e);
        }
    }

    @Override
    public CashFlowSummaryDto getCashFlowSummary(long businessId, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            Money totalExpenses = expenseDao.getTotalExpenses(conn, businessId, startDate, endDate);
            Money totalIncome = otherIncomeDao.getTotalOtherIncome(conn, businessId, startDate, endDate);
            Money netFlow = totalIncome.subtract(totalExpenses);

            List<Expense> expenses = expenseDao.search(conn, businessId, null, null, null, startDate, endDate);
            long activeExpenses = expenses.stream().filter(e -> !e.isReversed()).count();

            List<OtherIncome> incomes = otherIncomeDao.search(conn, businessId, null, startDate, endDate);
            long activeIncomes = incomes.stream().filter(i -> !i.isReversed()).count();

            return new CashFlowSummaryDto(
                    totalExpenses,
                    totalIncome,
                    netFlow,
                    (int) activeExpenses,
                    (int) activeIncomes
            );
        } catch (SQLException e) {
            log.error("Failed to calculate cash flow summary: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to calculate cash flow summary", e);
        }
    }

    @Override
    public List<ExpenseCategorySummaryDto> getExpenseBreakdownByCategory(long businessId, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return expenseDao.getCategoryBreakdown(conn, businessId, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to get category breakdown: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to get category breakdown", e);
        }
    }

    @Override
    public Money getTotalExpenses(long businessId, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return expenseDao.getTotalExpenses(conn, businessId, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to get total expenses: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to get total expenses", e);
        }
    }

    @Override
    public Money getTotalOtherIncome(long businessId, LocalDate startDate, LocalDate endDate) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return otherIncomeDao.getTotalOtherIncome(conn, businessId, startDate, endDate);
        } catch (SQLException e) {
            log.error("Failed to get total other income: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to get total other income", e);
        }
    }
}

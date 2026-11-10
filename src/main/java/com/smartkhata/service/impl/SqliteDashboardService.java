package com.smartkhata.service.impl;

import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.DashboardService;
import com.smartkhata.ui.ViewRouter;
import com.smartkhata.util.BengaliMoneyFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class SqliteDashboardService implements DashboardService {

    private static final Logger log = LoggerFactory.getLogger(SqliteDashboardService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a");

    @Override
    public DashboardKPIs getKPIs(long businessId, LocalDate startDate, LocalDate endDate) {
        String startStr = startDate.toString();
        String endStr = endDate.toString();

        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        LocalDate prevStart = startDate.minusDays(days);
        LocalDate prevEnd = startDate.minusDays(1);
        String prevStartStr = prevStart.toString();
        String prevEndStr = prevEnd.toString();

        long salesPaisha = 0L;
        long prevSalesPaisha = 0L;
        long collectionsPaisha = 0L;
        long prevCollectionsPaisha = 0L;
        long expensesPaisha = 0L;
        long prevExpensesPaisha = 0L;
        long inventoryValuePaisha = 0L;
        int totalStockItemsCount = 0;

        try (Connection conn = DatabaseManager.getConnection()) {
            // 1. Total Sales in period
            String sqlSales = "SELECT COALESCE(SUM(net_amount_paisha), 0) FROM sales " +
                    "WHERE business_id = ? AND is_reversed = 0 AND date(invoice_date) BETWEEN ? AND ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlSales)) {
                ps.setLong(1, businessId);
                ps.setString(2, startStr);
                ps.setString(3, endStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) salesPaisha = rs.getLong(1);
                }
            }

            // 1b. Previous period sales
            try (PreparedStatement ps = conn.prepareStatement(sqlSales)) {
                ps.setLong(1, businessId);
                ps.setString(2, prevStartStr);
                ps.setString(3, prevEndStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) prevSalesPaisha = rs.getLong(1);
                }
            }

            // 2. Due Collections in period
            String sqlCol = "SELECT COALESCE(SUM(amount_paisha), 0) FROM payments " +
                    "WHERE business_id = ? AND is_reversed = 0 AND party_type = 'CUSTOMER' AND date(payment_date) BETWEEN ? AND ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlCol)) {
                ps.setLong(1, businessId);
                ps.setString(2, startStr);
                ps.setString(3, endStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) collectionsPaisha = rs.getLong(1);
                }
            }

            // 2b. Previous period collections
            try (PreparedStatement ps = conn.prepareStatement(sqlCol)) {
                ps.setLong(1, businessId);
                ps.setString(2, prevStartStr);
                ps.setString(3, prevEndStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) prevCollectionsPaisha = rs.getLong(1);
                }
            }

            // 3. Operational Expenses in period
            String sqlExp = "SELECT COALESCE(SUM(amount_paisha), 0) FROM expenses " +
                    "WHERE business_id = ? AND is_reversed = 0 AND date(expense_date) BETWEEN ? AND ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlExp)) {
                ps.setLong(1, businessId);
                ps.setString(2, startStr);
                ps.setString(3, endStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) expensesPaisha = rs.getLong(1);
                }
            }

            // 3b. Previous period expenses
            try (PreparedStatement ps = conn.prepareStatement(sqlExp)) {
                ps.setLong(1, businessId);
                ps.setString(2, prevStartStr);
                ps.setString(3, prevEndStr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) prevExpensesPaisha = rs.getLong(1);
                }
            }

            // 4. Inventory Value from current stock * cost_price and total units in stock
            String sqlInv = "SELECT " +
                    "COALESCE(SUM(MAX(0, (SELECT COALESCE(SUM(sm.quantity), 0) FROM stock_movements sm WHERE sm.product_id = p.id))), 0) as total_units, " +
                    "COALESCE(SUM(p.cost_price_paisha * MAX(0, (SELECT COALESCE(SUM(sm.quantity), 0) FROM stock_movements sm WHERE sm.product_id = p.id))), 0) as total_value " +
                    "FROM products p WHERE p.business_id = ? AND p.is_archived = 0";
            try (PreparedStatement ps = conn.prepareStatement(sqlInv)) {
                ps.setLong(1, businessId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalStockItemsCount = rs.getInt("total_units");
                        inventoryValuePaisha = rs.getLong("total_value");
                    }
                }
            }

            double salesGrowth = calculateGrowth(salesPaisha, prevSalesPaisha);
            double colGrowth = calculateGrowth(collectionsPaisha, prevCollectionsPaisha);
            double expGrowth = calculateGrowth(expensesPaisha, prevExpensesPaisha);

            return new DashboardKPIs(
                    Money.ofPaisha(salesPaisha),
                    salesGrowth,
                    Money.ofPaisha(collectionsPaisha),
                    colGrowth,
                    Money.ofPaisha(expensesPaisha),
                    expGrowth,
                    Money.ofPaisha(inventoryValuePaisha),
                    totalStockItemsCount
            );

        } catch (SQLException e) {
            log.error("Failed to load dashboard KPIs: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to load dashboard KPIs", e);
        }
    }

    private double calculateGrowth(long current, long previous) {
        if (previous <= 0L) {
            return current > 0L ? 100.0 : 0.0;
        }
        return Math.round(((double) (current - previous) / previous * 100.0) * 10.0) / 10.0;
    }

    @Override
    public List<SalesTrendPoint> getTrend(long businessId, LocalDate startDate, LocalDate endDate, TrendMetric metric) {
        List<SalesTrendPoint> list = new ArrayList<>();
        String sql;
        if (metric == TrendMetric.COLLECTION) {
            sql = "SELECT strftime('%m-%d', payment_date) as d, COALESCE(SUM(amount_paisha), 0) FROM payments " +
                    "WHERE business_id = ? AND is_reversed = 0 AND party_type = 'CUSTOMER' AND date(payment_date) BETWEEN ? AND ? GROUP BY d ORDER BY d ASC";
        } else if (metric == TrendMetric.EXPENSES) {
            sql = "SELECT strftime('%m-%d', expense_date) as d, COALESCE(SUM(amount_paisha), 0) FROM expenses " +
                    "WHERE business_id = ? AND is_reversed = 0 AND date(expense_date) BETWEEN ? AND ? GROUP BY d ORDER BY d ASC";
        } else {
            sql = "SELECT strftime('%m-%d', invoice_date) as d, COALESCE(SUM(net_amount_paisha), 0) FROM sales " +
                    "WHERE business_id = ? AND is_reversed = 0 AND date(invoice_date) BETWEEN ? AND ? GROUP BY d ORDER BY d ASC";
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, startDate.toString());
            ps.setString(3, endDate.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String d = rs.getString(1);
                    long paisha = rs.getLong(2);
                    list.add(new SalesTrendPoint(d, Money.ofPaisha(paisha)));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load trend: {}", e.getMessage(), e);
        }
        return list;
    }

    @Override
    public TrendSummaryMetrics getTrendSummaryMetrics(long businessId, LocalDate startDate, LocalDate endDate, TrendMetric metric) {
        long days = Math.max(1, ChronoUnit.DAYS.between(startDate, endDate) + 1);
        String sql;
        String countLabel;

        if (metric == TrendMetric.COLLECTION) {
            sql = "SELECT COALESCE(SUM(amount_paisha), 0), COUNT(*) FROM payments " +
                    "WHERE business_id = ? AND is_reversed = 0 AND party_type = 'CUSTOMER' AND date(payment_date) BETWEEN ? AND ?";
            countLabel = "Collection Vouchers";
        } else if (metric == TrendMetric.EXPENSES) {
            sql = "SELECT COALESCE(SUM(amount_paisha), 0), COUNT(*) FROM expenses " +
                    "WHERE business_id = ? AND is_reversed = 0 AND date(expense_date) BETWEEN ? AND ?";
            countLabel = "Expense Vouchers";
        } else {
            sql = "SELECT COALESCE(SUM(net_amount_paisha), 0), COUNT(*) FROM sales " +
                    "WHERE business_id = ? AND is_reversed = 0 AND date(invoice_date) BETWEEN ? AND ?";
            countLabel = "Total Invoices";
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, startDate.toString());
            ps.setString(3, endDate.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long totalPaisha = rs.getLong(1);
                    int count = rs.getInt(2);
                    long avgDailyPaisha = totalPaisha / days;
                    return new TrendSummaryMetrics(
                            Money.ofPaisha(totalPaisha),
                            Money.ofPaisha(avgDailyPaisha),
                            count,
                            countLabel
                    );
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load trend summary metrics: {}", e.getMessage(), e);
        }
        return TrendSummaryMetrics.empty(metric == TrendMetric.SALES ? "Total Invoices" : "Vouchers");
    }

    @Override
    public List<RecentTransactionDto> getRecentTransactions(long businessId, int limit) {
        List<RecentTransactionDto> list = new ArrayList<>();
        // Query approved recent sales, collections, supplier payments, purchases, expenses, and other incomes
        String sql = "SELECT s.invoice_date as tx_time, COALESCE(c.name, 'Walk-in Customer') as party, " +
                "'Sale' as tx_type, s.net_amount_paisha as amt, s.payment_status as st " +
                "FROM sales s LEFT JOIN customers c ON s.customer_id = c.id WHERE s.business_id = ? AND s.is_reversed = 0 " +
                "UNION ALL " +
                "SELECT p.payment_date as tx_time, COALESCE(c.name, 'Customer') as party, " +
                "'Customer Collection' as tx_type, p.amount_paisha as amt, 'Received' as st " +
                "FROM payments p LEFT JOIN customers c ON p.party_id = c.id WHERE p.business_id = ? AND p.party_type = 'CUSTOMER' AND p.is_reversed = 0 " +
                "UNION ALL " +
                "SELECT p.payment_date as tx_time, COALESCE(s.name, 'Supplier') as party, " +
                "'Supplier Payment' as tx_type, p.amount_paisha as amt, 'Disbursed' as st " +
                "FROM payments p LEFT JOIN suppliers s ON p.party_id = s.id WHERE p.business_id = ? AND p.party_type = 'SUPPLIER' AND p.is_reversed = 0 " +
                "UNION ALL " +
                "SELECT pr.purchase_date as tx_time, COALESCE(s.name, 'Supplier') as party, " +
                "'Purchase' as tx_type, pr.total_amount_paisha as amt, pr.payment_status as st " +
                "FROM purchases pr LEFT JOIN suppliers s ON pr.supplier_id = s.id WHERE pr.business_id = ? AND pr.is_reversed = 0 " +
                "UNION ALL " +
                "SELECT e.expense_date as tx_time, COALESCE(e.payee_name, e.category) as party, " +
                "'Expense' as tx_type, e.amount_paisha as amt, 'Paid' as st " +
                "FROM expenses e WHERE e.business_id = ? AND e.is_reversed = 0 " +
                "UNION ALL " +
                "SELECT oi.income_date as tx_time, oi.source_name as party, " +
                "'Other Income' as tx_type, oi.amount_paisha as amt, 'Received' as st " +
                "FROM other_incomes oi WHERE oi.business_id = ? AND oi.is_reversed = 0 " +
                "ORDER BY tx_time DESC LIMIT ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, businessId);
            ps.setLong(3, businessId);
            ps.setLong(4, businessId);
            ps.setLong(5, businessId);
            ps.setLong(6, businessId);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String timeStr = rs.getString("tx_time");
                    LocalDateTime ldt = LocalDateTime.parse(timeStr.replace(" ", "T"));
                    list.add(new RecentTransactionDto(
                            ldt,
                            ldt.format(DATE_FMT),
                            rs.getString("party"),
                            rs.getString("tx_type"),
                            Money.ofPaisha(rs.getLong("amt")),
                            rs.getString("st")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load recent transactions: {}", e.getMessage(), e);
        }
        return list;
    }

    @Override
    public List<LowStockItemDto> getLowStockAlerts(long businessId, int limit) {
        List<LowStockItemDto> list = new ArrayList<>();
        String sql = "SELECT p.id, p.name, p.unit, p.low_stock_threshold, " +
                "COALESCE((SELECT SUM(sm.quantity) FROM stock_movements sm WHERE sm.product_id = p.id), 0) as current_qty " +
                "FROM products p WHERE p.business_id = ? AND p.is_archived = 0 " +
                "AND current_qty <= p.low_stock_threshold " +
                "ORDER BY current_qty ASC, p.id ASC LIMIT ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new LowStockItemDto(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getInt("current_qty"),
                            rs.getString("unit")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load low stock alerts: {}", e.getMessage(), e);
        }
        return list;
    }

    @Override
    public OutstandingDuesSummary getOutstandingDues(long businessId) {
        // Accurately counts ONLY customers who currently hold positive unpaid due balances
        String sql = "SELECT COALESCE(SUM(balance), 0), COUNT(*) FROM (" +
                "SELECT customer_id, (SUM(debit_paisha) - SUM(credit_paisha)) as balance " +
                "FROM customer_ledger WHERE business_id = ? GROUP BY customer_id HAVING balance > 0)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new OutstandingDuesSummary(
                            Money.ofPaisha(rs.getLong(1)),
                            rs.getInt(2)
                    );
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load outstanding dues: {}", e.getMessage(), e);
        }
        return OutstandingDuesSummary.empty();
    }

    @Override
    public List<SearchResultItemDto> globalSearch(long businessId, String query, int limit) {
        List<SearchResultItemDto> results = new ArrayList<>();
        if (query == null || query.trim().length() < 2) {
            return results;
        }

        String pattern = "%" + query.trim() + "%";
        int perCategoryLimit = Math.max(2, limit / 4);

        try (Connection conn = DatabaseManager.getConnection()) {
            // 1. Search Customers
            String sqlCust = "SELECT c.id, c.name, c.phone, " +
                    "COALESCE((SELECT SUM(debit_paisha) - SUM(credit_paisha) FROM customer_ledger WHERE customer_id = c.id), 0) as due " +
                    "FROM customers c WHERE c.business_id = ? AND c.is_archived = 0 " +
                    "AND (c.name LIKE ? OR c.phone LIKE ?) LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlCust)) {
                ps.setLong(1, businessId);
                ps.setString(2, pattern);
                ps.setString(3, pattern);
                ps.setInt(4, perCategoryLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String name = rs.getString("name");
                        String phone = rs.getString("phone");
                        Money due = Money.ofPaisha(rs.getLong("due"));
                        results.add(new SearchResultItemDto(
                                "Customer",
                                name + (phone != null ? " (" + phone + ")" : ""),
                                "Current Due: " + BengaliMoneyFormatter.format(due),
                                ViewRouter.ViewId.CUSTOMERS,
                                id
                        ));
                    }
                }
            }

            // 2. Search Products
            String sqlProd = "SELECT p.id, p.name, p.unit, p.selling_price_paisha, " +
                    "COALESCE((SELECT SUM(quantity) FROM stock_movements WHERE product_id = p.id), 0) as stock " +
                    "FROM products p WHERE p.business_id = ? AND p.is_archived = 0 " +
                    "AND (p.name LIKE ? OR p.barcode LIKE ? OR p.sku LIKE ?) LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlProd)) {
                ps.setLong(1, businessId);
                ps.setString(2, pattern);
                ps.setString(3, pattern);
                ps.setString(4, pattern);
                ps.setInt(5, perCategoryLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String name = rs.getString("name");
                        String unit = rs.getString("unit");
                        int stock = rs.getInt("stock");
                        Money price = Money.ofPaisha(rs.getLong("selling_price_paisha"));
                        results.add(new SearchResultItemDto(
                                "Product",
                                name + " — " + BengaliMoneyFormatter.format(price),
                                "Stock: " + stock + " " + unit,
                                ViewRouter.ViewId.INVENTORY,
                                id
                        ));
                    }
                }
            }

            // 3. Search Suppliers
            String sqlSupp = "SELECT s.id, s.name, s.phone, " +
                    "COALESCE((SELECT SUM(credit_paisha) - SUM(debit_paisha) FROM supplier_ledger WHERE supplier_id = s.id), 0) as payable " +
                    "FROM suppliers s WHERE s.business_id = ? AND s.is_archived = 0 " +
                    "AND (s.name LIKE ? OR s.phone LIKE ?) LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlSupp)) {
                ps.setLong(1, businessId);
                ps.setString(2, pattern);
                ps.setString(3, pattern);
                ps.setInt(4, perCategoryLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String name = rs.getString("name");
                        String phone = rs.getString("phone");
                        Money payable = Money.ofPaisha(rs.getLong("payable"));
                        results.add(new SearchResultItemDto(
                                "Supplier",
                                name + (phone != null ? " (" + phone + ")" : ""),
                                "Payable Due: " + BengaliMoneyFormatter.format(payable),
                                ViewRouter.ViewId.SUPPLIERS,
                                id
                        ));
                    }
                }
            }

            // 4. Search Sales Invoices
            String sqlSale = "SELECT id, invoice_number, net_amount_paisha, payment_status " +
                    "FROM sales WHERE business_id = ? AND is_reversed = 0 AND invoice_number LIKE ? LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlSale)) {
                ps.setLong(1, businessId);
                ps.setString(2, pattern);
                ps.setInt(3, perCategoryLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String inv = rs.getString("invoice_number");
                        Money net = Money.ofPaisha(rs.getLong("net_amount_paisha"));
                        String st = rs.getString("payment_status");
                        results.add(new SearchResultItemDto(
                                "Invoice",
                                inv + " (" + st + ")",
                                "Invoice Total: " + BengaliMoneyFormatter.format(net),
                                ViewRouter.ViewId.SALES,
                                id
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to execute global search for [{}]: {}", query, e.getMessage(), e);
        }

        return results;
    }
}

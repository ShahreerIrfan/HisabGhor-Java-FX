package com.smartkhata.service.impl;

import com.smartkhata.dao.BusinessDao;
import com.smartkhata.dao.SqliteBusinessDao;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.model.dto.ReportCriteria;
import com.smartkhata.model.dto.ReportData;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.enums.ReportType;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.ReportService;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.util.BengaliMoneyFormatter;
import com.smartkhata.util.CsvExporter;
import com.smartkhata.util.PdfReportExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SqliteReportService implements ReportService {

    private static final Logger log = LoggerFactory.getLogger(SqliteReportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final BusinessDao businessDao;

    public SqliteReportService() {
        this(new SqliteBusinessDao());
    }

    public SqliteReportService(BusinessDao businessDao) {
        this.businessDao = businessDao;
    }

    @Override
    public ReportData generateReport(ReportCriteria criteria) {
        SessionContext.requireAuthenticated();
        if (criteria.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match report business.");
        }

        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Business> bizOpt = businessDao.findById(conn, criteria.businessId());
            Business biz = bizOpt.orElse(new Business(criteria.businessId(), "SmartKhata Business", null, null, null, null, "BDT", null, null));

            return switch (criteria.reportType()) {
                case SALES_SUMMARY -> generateSalesSummary(conn, biz, criteria);
                case PURCHASE_SUMMARY -> generatePurchaseSummary(conn, biz, criteria);
                case BUSINESS_PROFIT_LOSS -> generateBusinessProfitLoss(conn, biz, criteria);
                case PRODUCT_PROFITABILITY -> generateProductProfitability(conn, biz, criteria);
                case CATEGORY_PROFITABILITY -> generateCategoryProfitability(conn, biz, criteria);
                case SUPPLIER_PURCHASE_ANALYSIS -> generateSupplierPurchaseAnalysis(conn, biz, criteria);
                case CUSTOMER_OUTSTANDING -> generateCustomerOutstanding(conn, biz, criteria);
                case SUPPLIER_OUTSTANDING -> generateSupplierOutstanding(conn, biz, criteria);
                case CUSTOMER_LEDGER -> generateCustomerLedger(conn, biz, criteria);
                case SUPPLIER_LEDGER -> generateSupplierLedger(conn, biz, criteria);
                case PAYMENTS_SUMMARY -> generatePaymentsSummary(conn, biz, criteria);
                case EXPENSE_SUMMARY -> generateExpenseSummary(conn, biz, criteria);
                case OTHER_INCOME_SUMMARY -> generateOtherIncomeSummary(conn, biz, criteria);
                case INVENTORY_VALUATION -> generateInventoryValuation(conn, biz, criteria);
            };
        } catch (SQLException e) {
            log.error("Failed to generate report [{}]: {}", criteria.reportType(), e.getMessage(), e);
            throw new DatabaseException("Failed to generate financial report", e);
        }
    }

    @Override
    public byte[] exportToPdf(ReportData data) {
        try {
            return PdfReportExporter.exportToPdf(data);
        } catch (IOException e) {
            log.error("Failed to export report to PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Could not generate PDF report", e);
        }
    }

    @Override
    public String exportToCsv(ReportData data) {
        return CsvExporter.exportToCsv(data);
    }

    private ReportData generateSalesSummary(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String sql = "SELECT s.invoice_number, s.invoice_date, COALESCE(c.name, 'Walk-in Customer') as customer_name, " +
                "s.total_amount_paisha, s.discount_paisha, s.net_amount_paisha, s.paid_amount_paisha, s.due_amount_paisha, s.payment_status " +
                "FROM sales s LEFT JOIN customers c ON s.customer_id = c.id " +
                "WHERE s.business_id = ? AND s.is_reversed = 0 AND date(s.invoice_date) BETWEEN ? AND ? " +
                "ORDER BY s.invoice_date DESC, s.id DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalSalesPaisha = 0L;
        long totalPaidPaisha = 0L;
        long totalDuePaisha = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long net = rs.getLong("net_amount_paisha");
                    long paid = rs.getLong("paid_amount_paisha");
                    long due = rs.getLong("due_amount_paisha");
                    totalSalesPaisha += net;
                    totalPaidPaisha += paid;
                    totalDuePaisha += due;

                    rows.add(List.of(
                            rs.getString("invoice_number"),
                            rs.getString("invoice_date").substring(0, 16),
                            rs.getString("customer_name"),
                            BengaliMoneyFormatter.format(Money.ofPaisha(rs.getLong("total_amount_paisha"))),
                            BengaliMoneyFormatter.format(Money.ofPaisha(rs.getLong("discount_paisha"))),
                            BengaliMoneyFormatter.format(Money.ofPaisha(net)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(paid)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(due)),
                            rs.getString("payment_status")
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Sales Volume", BengaliMoneyFormatter.format(Money.ofPaisha(totalSalesPaisha)));
        summary.put("Total Collected", BengaliMoneyFormatter.format(Money.ofPaisha(totalPaidPaisha)));
        summary.put("Total Outstanding Due", BengaliMoneyFormatter.format(Money.ofPaisha(totalDuePaisha)));
        summary.put("Total Invoices", String.valueOf(rows.size()));

        return new ReportData(
                "Sales Summary Report (বিক্রয় সারসংক্ষেপ বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Invoice No", "Date & Time", "Customer", "Subtotal", "Discount", "Net Amount", "Paid", "Due", "Status"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generatePurchaseSummary(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String sql = "SELECT p.purchase_number, p.purchase_date, s.name as supplier_name, " +
                "p.total_amount_paisha, p.paid_amount_paisha, p.due_amount_paisha, p.payment_status " +
                "FROM purchases p LEFT JOIN suppliers s ON p.supplier_id = s.id " +
                "WHERE p.business_id = ? AND p.is_reversed = 0 AND date(p.purchase_date) BETWEEN ? AND ? " +
                "ORDER BY p.purchase_date DESC, p.id DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalPurPaisha = 0L;
        long totalPaidPaisha = 0L;
        long totalDuePaisha = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long tot = rs.getLong("total_amount_paisha");
                    long paid = rs.getLong("paid_amount_paisha");
                    long due = rs.getLong("due_amount_paisha");
                    totalPurPaisha += tot;
                    totalPaidPaisha += paid;
                    totalDuePaisha += due;

                    rows.add(List.of(
                            rs.getString("purchase_number"),
                            rs.getString("purchase_date").substring(0, 16),
                            rs.getString("supplier_name") != null ? rs.getString("supplier_name") : "—",
                            BengaliMoneyFormatter.format(Money.ofPaisha(tot)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(paid)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(due)),
                            rs.getString("payment_status")
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Purchases", BengaliMoneyFormatter.format(Money.ofPaisha(totalPurPaisha)));
        summary.put("Total Paid", BengaliMoneyFormatter.format(Money.ofPaisha(totalPaidPaisha)));
        summary.put("Total Payable Due", BengaliMoneyFormatter.format(Money.ofPaisha(totalDuePaisha)));
        summary.put("Total Bills", String.valueOf(rows.size()));

        return new ReportData(
                "Purchase Summary Report (ক্রয় সারসংক্ষেপ বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Bill No", "Date & Time", "Supplier", "Total Amount", "Paid Amount", "Due Balance", "Status"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateCustomerOutstanding(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        String sql = "SELECT c.id, c.name, c.phone, c.address, c.credit_limit_paisha, " +
                "COALESCE((SELECT SUM(debit_paisha) - SUM(credit_paisha) FROM customer_ledger WHERE customer_id = c.id), 0) as balance " +
                "FROM customers c WHERE c.business_id = ? AND c.is_archived = 0 " +
                "HAVING balance > 0 ORDER BY balance DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalReceivables = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long bal = rs.getLong("balance");
                    totalReceivables += bal;
                    rows.add(List.of(
                            String.valueOf(rs.getLong("id")),
                            rs.getString("name"),
                            rs.getString("phone") != null ? rs.getString("phone") : "—",
                            rs.getString("address") != null ? rs.getString("address") : "—",
                            BengaliMoneyFormatter.format(Money.ofPaisha(rs.getLong("credit_limit_paisha"))),
                            BengaliMoneyFormatter.format(Money.ofPaisha(bal))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Receivables (মোট বাকি)", BengaliMoneyFormatter.format(Money.ofPaisha(totalReceivables)));
        summary.put("Customers with Due", String.valueOf(rows.size()));

        return new ReportData(
                "Customer Outstanding Statement (গ্রাহক বাকি খাতা বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "As of Snapshot: " + LocalDate.now().format(DATE_FMT),
                List.of("Customer ID", "Customer Name", "Phone", "Address", "Credit Limit", "Current Due (BDT)"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateSupplierOutstanding(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        String sql = "SELECT s.id, s.name, s.phone, s.contact_person, " +
                "COALESCE((SELECT SUM(credit_paisha) - SUM(debit_paisha) FROM supplier_ledger WHERE supplier_id = s.id), 0) as balance " +
                "FROM suppliers s WHERE s.business_id = ? AND s.is_archived = 0 " +
                "HAVING balance > 0 ORDER BY balance DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalPayables = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long bal = rs.getLong("balance");
                    totalPayables += bal;
                    rows.add(List.of(
                            String.valueOf(rs.getLong("id")),
                            rs.getString("name"),
                            rs.getString("phone") != null ? rs.getString("phone") : "—",
                            rs.getString("contact_person") != null ? rs.getString("contact_person") : "—",
                            BengaliMoneyFormatter.format(Money.ofPaisha(bal))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Payables (মোট পাওনা)", BengaliMoneyFormatter.format(Money.ofPaisha(totalPayables)));
        summary.put("Suppliers to Disburse", String.valueOf(rows.size()));

        return new ReportData(
                "Supplier Outstanding Statement (সরবরাহকারী পাওনা বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "As of Snapshot: " + LocalDate.now().format(DATE_FMT),
                List.of("Supplier ID", "Supplier Name", "Phone", "Contact Person", "Current Payable (BDT)"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateCustomerLedger(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        if (c.partyId() == null) {
            throw new IllegalArgumentException("A specific Customer ID is required to generate a customer ledger statement.");
        }

        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String custName = "Customer #" + c.partyId();
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM customers WHERE id = ? AND business_id = ?")) {
            ps.setLong(1, c.partyId());
            ps.setLong(2, biz.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) custName = rs.getString(1);
            }
        }

        // 1. Opening Balance prior to start date
        long openingBalancePaisha = 0L;
        String sqlOp = "SELECT COALESCE(SUM(debit_paisha) - SUM(credit_paisha), 0) FROM customer_ledger " +
                "WHERE customer_id = ? AND date(entry_date) < ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlOp)) {
            ps.setLong(1, c.partyId());
            ps.setString(2, start.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) openingBalancePaisha = rs.getLong(1);
            }
        }

        // 2. Entries in period
        String sql = "SELECT entry_date, entry_type, reference_id, debit_paisha, credit_paisha, running_balance_paisha, notes " +
                "FROM customer_ledger WHERE customer_id = ? AND date(entry_date) BETWEEN ? AND ? " +
                "ORDER BY entry_date ASC, id ASC";

        List<List<String>> rows = new ArrayList<>();
        long totalDebit = 0L;
        long totalCredit = 0L;
        long currentRunning = openingBalancePaisha;

        // Opening balance row
        rows.add(List.of(
                start.toString() + " 00:00",
                "OPENING BALANCE (পূর্বের বাকি)",
                "—",
                "—",
                "—",
                BengaliMoneyFormatter.format(Money.ofPaisha(openingBalancePaisha))
        ));

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, c.partyId());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long dr = rs.getLong("debit_paisha");
                    long cr = rs.getLong("credit_paisha");
                    long rb = rs.getLong("running_balance_paisha");
                    totalDebit += dr;
                    totalCredit += cr;
                    currentRunning = rb;

                    rows.add(List.of(
                            rs.getString("entry_date").substring(0, 16),
                            rs.getString("entry_type"),
                            rs.getString("reference_id") != null ? String.valueOf(rs.getLong("reference_id")) : "—",
                            dr > 0 ? BengaliMoneyFormatter.format(Money.ofPaisha(dr)) : "—",
                            cr > 0 ? BengaliMoneyFormatter.format(Money.ofPaisha(cr)) : "—",
                            BengaliMoneyFormatter.format(Money.ofPaisha(rb))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Opening Due", BengaliMoneyFormatter.format(Money.ofPaisha(openingBalancePaisha)));
        summary.put("Total Invoiced (Debit)", BengaliMoneyFormatter.format(Money.ofPaisha(totalDebit)));
        summary.put("Total Collected (Credit)", BengaliMoneyFormatter.format(Money.ofPaisha(totalCredit)));
        summary.put("Closing Due Balance", BengaliMoneyFormatter.format(Money.ofPaisha(currentRunning)));

        return new ReportData(
                "Customer Ledger Statement — " + custName + " (গ্রাহক খতিয়ান)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Date & Time", "Transaction Type", "Ref ID", "Debit (+Due)", "Credit (-Paid)", "Running Due"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateSupplierLedger(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        if (c.partyId() == null) {
            throw new IllegalArgumentException("A specific Supplier ID is required to generate a supplier ledger statement.");
        }

        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String suppName = "Supplier #" + c.partyId();
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM suppliers WHERE id = ? AND business_id = ?")) {
            ps.setLong(1, c.partyId());
            ps.setLong(2, biz.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) suppName = rs.getString(1);
            }
        }

        // 1. Opening Balance prior to start date
        long openingBalancePaisha = 0L;
        String sqlOp = "SELECT COALESCE(SUM(credit_paisha) - SUM(debit_paisha), 0) FROM supplier_ledger " +
                "WHERE supplier_id = ? AND date(entry_date) < ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlOp)) {
            ps.setLong(1, c.partyId());
            ps.setString(2, start.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) openingBalancePaisha = rs.getLong(1);
            }
        }

        // 2. Entries in period
        String sql = "SELECT entry_date, entry_type, reference_id, credit_paisha, debit_paisha, running_balance_paisha, notes " +
                "FROM supplier_ledger WHERE supplier_id = ? AND date(entry_date) BETWEEN ? AND ? " +
                "ORDER BY entry_date ASC, id ASC";

        List<List<String>> rows = new ArrayList<>();
        long totalCredit = 0L;
        long totalDebit = 0L;
        long currentRunning = openingBalancePaisha;

        // Opening balance row
        rows.add(List.of(
                start.toString() + " 00:00",
                "OPENING BALANCE (পূর্বের পাওনা)",
                "—",
                "—",
                "—",
                BengaliMoneyFormatter.format(Money.ofPaisha(openingBalancePaisha))
        ));

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, c.partyId());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cr = rs.getLong("credit_paisha");
                    long dr = rs.getLong("debit_paisha");
                    long rb = rs.getLong("running_balance_paisha");
                    totalCredit += cr;
                    totalDebit += dr;
                    currentRunning = rb;

                    rows.add(List.of(
                            rs.getString("entry_date").substring(0, 16),
                            rs.getString("entry_type"),
                            rs.getString("reference_id") != null ? String.valueOf(rs.getLong("reference_id")) : "—",
                            cr > 0 ? BengaliMoneyFormatter.format(Money.ofPaisha(cr)) : "—",
                            dr > 0 ? BengaliMoneyFormatter.format(Money.ofPaisha(dr)) : "—",
                            BengaliMoneyFormatter.format(Money.ofPaisha(rb))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Opening Payable", BengaliMoneyFormatter.format(Money.ofPaisha(openingBalancePaisha)));
        summary.put("Total Purchases (Credit)", BengaliMoneyFormatter.format(Money.ofPaisha(totalCredit)));
        summary.put("Total Paid (Debit)", BengaliMoneyFormatter.format(Money.ofPaisha(totalDebit)));
        summary.put("Closing Payable Balance", BengaliMoneyFormatter.format(Money.ofPaisha(currentRunning)));

        return new ReportData(
                "Supplier Ledger Statement — " + suppName + " (সরবরাহকারী খতিয়ান)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Date & Time", "Transaction Type", "Ref ID", "Credit (+Payable)", "Debit (-Paid)", "Running Payable"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generatePaymentsSummary(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String sql = "SELECT p.payment_number, p.payment_date, p.party_type, " +
                "COALESCE(CASE WHEN p.party_type = 'CUSTOMER' THEN (SELECT name FROM customers WHERE id = p.party_id) " +
                "              ELSE (SELECT name FROM suppliers WHERE id = p.party_id) END, 'Unknown') as party_name, " +
                "p.payment_method, p.reference_trx_id, p.amount_paisha " +
                "FROM payments p WHERE p.business_id = ? AND p.is_reversed = 0 AND date(p.payment_date) BETWEEN ? AND ? " +
                "ORDER BY p.payment_date DESC, p.id DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalCollections = 0L;
        long totalDisbursements = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long amt = rs.getLong("amount_paisha");
                    String type = rs.getString("party_type");
                    if ("CUSTOMER".equalsIgnoreCase(type)) {
                        totalCollections += amt;
                    } else {
                        totalDisbursements += amt;
                    }

                    rows.add(List.of(
                            rs.getString("payment_number"),
                            rs.getString("payment_date").substring(0, 16),
                            type,
                            rs.getString("party_name"),
                            rs.getString("payment_method"),
                            rs.getString("reference_trx_id") != null ? rs.getString("reference_trx_id") : "—",
                            BengaliMoneyFormatter.format(Money.ofPaisha(amt))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Customer Collections", BengaliMoneyFormatter.format(Money.ofPaisha(totalCollections)));
        summary.put("Total Supplier Payments", BengaliMoneyFormatter.format(Money.ofPaisha(totalDisbursements)));
        summary.put("Net Cash Flow", BengaliMoneyFormatter.format(Money.ofPaisha(totalCollections - totalDisbursements)));
        summary.put("Total Vouchers", String.valueOf(rows.size()));

        return new ReportData(
                "Payments & Collections Statement (টাকা আদায় ও পরিশোধ বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Voucher No", "Date & Time", "Party Type", "Party Name", "Method", "Trx Reference", "Amount (BDT)"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateExpenseSummary(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String sql = "SELECT expense_date, category, payee_name, payment_method, amount_paisha, notes " +
                "FROM expenses WHERE business_id = ? AND is_reversed = 0 AND date(expense_date) BETWEEN ? AND ? " +
                "ORDER BY expense_date DESC, id DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalExpensePaisha = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long amt = rs.getLong("amount_paisha");
                    totalExpensePaisha += amt;
                    rows.add(List.of(
                            rs.getString("expense_date").substring(0, 16),
                            rs.getString("category"),
                            rs.getString("payee_name") != null ? rs.getString("payee_name") : "—",
                            rs.getString("payment_method"),
                            BengaliMoneyFormatter.format(Money.ofPaisha(amt)),
                            rs.getString("notes") != null ? rs.getString("notes") : "—"
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Operating Expenses", BengaliMoneyFormatter.format(Money.ofPaisha(totalExpensePaisha)));
        summary.put("Expense Count", String.valueOf(rows.size()));

        return new ReportData(
                "Operating Expenses Summary (ব্যবসায়িক খরচ বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Date & Time", "Category", "Payee", "Method", "Amount (BDT)", "Notes"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateOtherIncomeSummary(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();

        String sql = "SELECT income_date, source_name, payment_method, amount_paisha, notes " +
                "FROM other_incomes WHERE business_id = ? AND is_reversed = 0 AND date(income_date) BETWEEN ? AND ? " +
                "ORDER BY income_date DESC, id DESC";

        List<List<String>> rows = new ArrayList<>();
        long totalIncomePaisha = 0L;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long amt = rs.getLong("amount_paisha");
                    totalIncomePaisha += amt;
                    rows.add(List.of(
                            rs.getString("income_date").substring(0, 16),
                            rs.getString("source_name"),
                            rs.getString("payment_method"),
                            BengaliMoneyFormatter.format(Money.ofPaisha(amt)),
                            rs.getString("notes") != null ? rs.getString("notes") : "—"
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Other Income", BengaliMoneyFormatter.format(Money.ofPaisha(totalIncomePaisha)));
        summary.put("Receipt Count", String.valueOf(rows.size()));

        return new ReportData(
                "Other Business Income Summary (অন্যান্য আয় বিবরণী)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Date & Time", "Source / Particulars", "Method", "Amount (BDT)", "Notes"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateInventoryValuation(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT p.id, p.sku, p.name, c.name as category_name, " +
                "p.cost_price_paisha, p.selling_price_paisha, " +
                "COALESCE((SELECT SUM(sm.quantity) FROM stock_movements sm WHERE sm.product_id = p.id), 0) as current_stock, " +
                "p.unit, p.low_stock_threshold " +
                "FROM products p LEFT JOIN categories c ON p.category_id = c.id " +
                "WHERE p.business_id = ? AND p.is_archived = 0");

        if (c.lowStockOnly()) {
            sql.append(" AND current_stock <= p.low_stock_threshold");
        }
        sql.append(" ORDER BY current_stock ASC, p.name ASC");

        List<List<String>> rows = new ArrayList<>();
        long totalValuationPaisha = 0L;
        int totalUnits = 0;
        int lowStockCount = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, biz.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int stock = rs.getInt("current_stock");
                    long cost = rs.getLong("cost_price_paisha");
                    int threshold = rs.getInt("low_stock_threshold");
                    long lineValuation = cost * Math.max(0, stock);

                    totalValuationPaisha += lineValuation;
                    totalUnits += Math.max(0, stock);
                    if (stock <= threshold) {
                        lowStockCount++;
                    }

                    rows.add(List.of(
                            rs.getString("sku") != null ? rs.getString("sku") : String.valueOf(rs.getLong("id")),
                            rs.getString("name"),
                            rs.getString("category_name") != null ? rs.getString("category_name") : "General",
                            BengaliMoneyFormatter.format(Money.ofPaisha(cost)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(rs.getLong("selling_price_paisha"))),
                            String.valueOf(stock),
                            rs.getString("unit"),
                            BengaliMoneyFormatter.format(Money.ofPaisha(lineValuation))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Inventory Valuation (মজুদ মূল্য)", BengaliMoneyFormatter.format(Money.ofPaisha(totalValuationPaisha)));
        summary.put("Total Stocked Units", String.valueOf(totalUnits));
        summary.put("Low Stock Alerts", String.valueOf(lowStockCount));
        summary.put("Total Listed Products", String.valueOf(rows.size()));

        return new ReportData(
                c.lowStockOnly() ? "Low Stock Inventory Alerts (স্বল্প মজুদ রিপোর্ট)" : "Inventory Valuation & Stock Report (মজুদ ও মূল্যায়ন রিপোর্ট)",
                biz.name(),
                biz.address(),
                biz.phone(),
                "As of Snapshot: " + LocalDate.now().format(DATE_FMT),
                List.of("SKU / ID", "Product Name", "Category", "Cost Price", "Sell Price", "Current Stock", "Unit", "Total Valuation"),
                rows,
                summary,
                LocalDateTime.now()
        );
    }

    private ReportData generateBusinessProfitLoss(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();
        String sStr = start.toString();
        String eStr = end.toString();

        long grossSalesPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(net_amount_paisha), 0) FROM sales WHERE business_id = ? AND is_reversed = 0 AND date(invoice_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long grossCogsPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(total_cogs_paisha), 0) FROM sales WHERE business_id = ? AND is_reversed = 0 AND date(invoice_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long returnRefundPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(total_refund_paisha), 0) FROM sale_returns WHERE business_id = ? AND date(return_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long returnCogsPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(total_cogs_restored_paisha), 0) FROM sale_returns WHERE business_id = ? AND date(return_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long otherIncomePaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(amount_paisha), 0) FROM other_incomes WHERE business_id = ? AND is_reversed = 0 AND date(income_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long expensesPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(amount_paisha), 0) FROM expenses WHERE business_id = ? AND is_reversed = 0 AND date(expense_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long periodGrnCostPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(total_cost_paisha), 0) FROM goods_receipt_notes WHERE business_id = ? AND status = 'POSTED' AND date(receipt_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);
        long periodLegacyPurPaisha = scalarLong(conn,
                "SELECT COALESCE(SUM(net_amount_paisha), 0) FROM purchases WHERE business_id = ? AND is_reversed = 0 AND date(purchase_date) BETWEEN ? AND ?",
                biz.id(), sStr, eStr);

        long netSalesPaisha = grossSalesPaisha - returnRefundPaisha;
        long netCogsPaisha = grossCogsPaisha - returnCogsPaisha;
        long grossProfitPaisha = netSalesPaisha - netCogsPaisha;
        long netProfitPaisha = grossProfitPaisha + otherIncomePaisha - expensesPaisha;

        double marginPct = com.smartkhata.util.PricingCalculator.calculateProfitMarginPercent(
                Money.ofPaisha(netSalesPaisha), Money.ofPaisha(netCogsPaisha));
        double markupPct = com.smartkhata.util.PricingCalculator.calculateMarkupPercent(
                Money.ofPaisha(netSalesPaisha), Money.ofPaisha(netCogsPaisha));

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Gross Sales Revenue (মোট বিক্রয়)", BengaliMoneyFormatter.format(Money.ofPaisha(grossSalesPaisha)), "Net of invoice discounts, active sales"));
        rows.add(List.of("Less: Sales Returns (বিক্রয় ফেরত)", BengaliMoneyFormatter.format(Money.ofPaisha(returnRefundPaisha)), "Partial/full customer returns"));
        rows.add(List.of("Net Sales Revenue (নিট বিক্রয়)", BengaliMoneyFormatter.format(Money.ofPaisha(netSalesPaisha)), "Gross Sales - Sales Returns"));
        rows.add(List.of("Gross Cost of Goods Sold (COGS)", BengaliMoneyFormatter.format(Money.ofPaisha(grossCogsPaisha)), "Weighted Avg Cost snapshot at sale"));
        rows.add(List.of("Less: COGS Restored on Returns", BengaliMoneyFormatter.format(Money.ofPaisha(returnCogsPaisha)), "Inventory value restored on returns"));
        rows.add(List.of("Net Cost of Goods Sold (নিট COGS)", BengaliMoneyFormatter.format(Money.ofPaisha(netCogsPaisha)), "Cost recognized upon goods sold"));
        rows.add(List.of("Gross Profit (মোট লাভ)", BengaliMoneyFormatter.format(Money.ofPaisha(grossProfitPaisha)),
                String.format("Margin: %.2f%% | Markup: %.2f%%", marginPct, markupPct)));
        rows.add(List.of("Plus: Other Business Income", BengaliMoneyFormatter.format(Money.ofPaisha(otherIncomePaisha)), "Non-inventory misc business receipts"));
        rows.add(List.of("Less: Operating Expenses (পরিচালন ব্যয়)", BengaliMoneyFormatter.format(Money.ofPaisha(expensesPaisha)), "Stock purchases excluded to prevent double-count"));
        rows.add(List.of(netProfitPaisha >= 0 ? "NET PROFIT (নিট মুনাফা)" : "NET LOSS (নিট লোকসান)",
                BengaliMoneyFormatter.format(Money.ofPaisha(netProfitPaisha)), "Gross Profit + Other Income - Operating Expenses"));
        rows.add(List.of("Memo: Inventory Purchases in Period",
                BengaliMoneyFormatter.format(Money.ofPaisha(periodGrnCostPaisha + periodLegacyPurPaisha)),
                "GRNs + Direct Bills (capitalized to Inventory, NOT expensed twice)"));

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Net Sales Revenue", BengaliMoneyFormatter.format(Money.ofPaisha(netSalesPaisha)));
        summary.put("Net COGS", BengaliMoneyFormatter.format(Money.ofPaisha(netCogsPaisha)));
        summary.put("Gross Profit (Margin)", BengaliMoneyFormatter.format(Money.ofPaisha(grossProfitPaisha)) + String.format(" (%.1f%%)", marginPct));
        summary.put("Operating Expenses", BengaliMoneyFormatter.format(Money.ofPaisha(expensesPaisha)));
        summary.put(netProfitPaisha >= 0 ? "Net Profit (নিট মুনাফা)" : "Net Loss", BengaliMoneyFormatter.format(Money.ofPaisha(netProfitPaisha)));

        return new ReportData(
                "Business Profit & Loss Statement (ব্যবসায়িক লাভ-ক্ষতি বিবরণী)",
                biz.name(), biz.address(), biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Account Line Item", "Amount (BDT)", "Accounting Basis / Notes"),
                rows, summary, LocalDateTime.now()
        );
    }

    private ReportData generateProductProfitability(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();
        String sStr = start.toString();
        String eStr = end.toString();

        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.name, COALESCE(p.sku, CAST(p.id AS TEXT)) AS sku, COALESCE(cat.name, 'General') AS cat_name, p.unit, " +
                "COALESCE((SELECT SUM(si.quantity - si.returned_qty) FROM sale_items si JOIN sales s ON s.id = si.sale_id " +
                " WHERE si.product_id = p.id AND s.business_id = ? AND s.is_reversed = 0 AND date(s.invoice_date) BETWEEN ? AND ?), 0) AS net_qty, " +
                "COALESCE((SELECT SUM(si.line_total_paisha) FROM sale_items si JOIN sales s ON s.id = si.sale_id " +
                " WHERE si.product_id = p.id AND s.business_id = ? AND s.is_reversed = 0 AND date(s.invoice_date) BETWEEN ? AND ?), 0) " +
                "- COALESCE((SELECT SUM(sri.line_refund_paisha) FROM sale_return_items sri JOIN sale_returns sr ON sr.id = sri.sale_return_id " +
                " WHERE sri.product_id = p.id AND sr.business_id = ? AND date(sr.return_date) BETWEEN ? AND ?), 0) AS net_rev, " +
                "COALESCE((SELECT SUM(si.quantity * si.unit_cost_paisha) FROM sale_items si JOIN sales s ON s.id = si.sale_id " +
                " WHERE si.product_id = p.id AND s.business_id = ? AND s.is_reversed = 0 AND date(s.invoice_date) BETWEEN ? AND ?), 0) " +
                "- COALESCE((SELECT SUM(sri.line_cogs_restored_paisha) FROM sale_return_items sri JOIN sale_returns sr ON sr.id = sri.sale_return_id " +
                " WHERE sri.product_id = p.id AND sr.business_id = ? AND date(sr.return_date) BETWEEN ? AND ?), 0) AS net_cogs " +
                "FROM products p LEFT JOIN categories cat ON cat.id = p.category_id " +
                "WHERE p.business_id = ? "
        );
        if (c.category() != null && !c.category().isBlank() && !"ALL".equalsIgnoreCase(c.category())) {
            sql.append("AND cat.name = ? ");
        }
        sql.append("ORDER BY net_rev DESC, p.name ASC");

        List<List<String>> rows = new ArrayList<>();
        long totRev = 0L;
        long totCogs = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (int k = 0; k < 5; k++) {
                ps.setLong(idx++, biz.id());
                ps.setString(idx++, sStr);
                ps.setString(idx++, eStr);
            }
            ps.setLong(idx++, biz.id());
            if (c.category() != null && !c.category().isBlank() && !"ALL".equalsIgnoreCase(c.category())) {
                ps.setString(idx++, c.category());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int qty = rs.getInt("net_qty");
                    long rev = rs.getLong("net_rev");
                    long cogs = rs.getLong("net_cogs");
                    if (qty == 0 && rev == 0L && cogs == 0L) continue;
                    long profit = rev - cogs;
                    totRev += rev;
                    totCogs += cogs;
                    double margin = com.smartkhata.util.PricingCalculator.calculateProfitMarginPercent(Money.ofPaisha(rev), Money.ofPaisha(cogs));
                    double markup = com.smartkhata.util.PricingCalculator.calculateMarkupPercent(Money.ofPaisha(rev), Money.ofPaisha(cogs));
                    long avgSell = qty > 0 ? Math.round((double) rev / qty) : 0L;
                    long avgCost = qty > 0 ? Math.round((double) cogs / qty) : 0L;

                    rows.add(List.of(
                            rs.getString("name"),
                            rs.getString("cat_name"),
                            qty + " " + rs.getString("unit"),
                            BengaliMoneyFormatter.format(Money.ofPaisha(avgSell)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(avgCost)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(rev)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(cogs)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(profit)),
                            String.format("%.1f%% / %.1f%%", margin, markup)
                    ));
                }
            }
        }

        long totProfit = totRev - totCogs;
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Sales Revenue", BengaliMoneyFormatter.format(Money.ofPaisha(totRev)));
        summary.put("Total COGS", BengaliMoneyFormatter.format(Money.ofPaisha(totCogs)));
        summary.put("Total Gross Profit", BengaliMoneyFormatter.format(Money.ofPaisha(totProfit)));
        summary.put("Overall Margin %", String.format("%.2f%%",
                com.smartkhata.util.PricingCalculator.calculateProfitMarginPercent(Money.ofPaisha(totRev), Money.ofPaisha(totCogs))));

        return new ReportData(
                "Product Profitability & COGS Analysis (পণ্যভিত্তিক লাভ-ক্ষতি)",
                biz.name(), biz.address(), biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Product", "Category", "Sold Qty", "Avg Price", "Avg COGS", "Sales Revenue", "Total COGS", "Gross Profit", "Margin / Markup"),
                rows, summary, LocalDateTime.now()
        );
    }

    private ReportData generateCategoryProfitability(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();
        String sStr = start.toString();
        String eStr = end.toString();

        String sql = "SELECT COALESCE(cat.name, 'General') AS cat_name, " +
                "SUM(si.quantity - si.returned_qty) AS qty_sold, " +
                "SUM(si.line_total_paisha) AS rev_paisha, " +
                "SUM(si.quantity * si.unit_cost_paisha) AS cogs_paisha " +
                "FROM sale_items si " +
                "JOIN sales s ON s.id = si.sale_id " +
                "JOIN products p ON p.id = si.product_id " +
                "LEFT JOIN categories cat ON cat.id = p.category_id " +
                "WHERE s.business_id = ? AND s.is_reversed = 0 AND date(s.invoice_date) BETWEEN ? AND ? " +
                "GROUP BY COALESCE(cat.name, 'General') ORDER BY rev_paisha DESC";

        List<List<String>> rows = new ArrayList<>();
        long totRev = 0L;
        long totCogs = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, biz.id());
            ps.setString(2, sStr);
            ps.setString(3, eStr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rev = rs.getLong("rev_paisha");
                    long cogs = rs.getLong("cogs_paisha");
                    long profit = rev - cogs;
                    totRev += rev;
                    totCogs += cogs;
                    double margin = com.smartkhata.util.PricingCalculator.calculateProfitMarginPercent(Money.ofPaisha(rev), Money.ofPaisha(cogs));
                    rows.add(List.of(
                            rs.getString("cat_name"),
                            String.valueOf(rs.getInt("qty_sold")),
                            BengaliMoneyFormatter.format(Money.ofPaisha(rev)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(cogs)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(profit)),
                            String.format("%.2f%%", margin)
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Category Revenue", BengaliMoneyFormatter.format(Money.ofPaisha(totRev)));
        summary.put("Total Category COGS", BengaliMoneyFormatter.format(Money.ofPaisha(totCogs)));
        summary.put("Total Gross Profit", BengaliMoneyFormatter.format(Money.ofPaisha(totRev - totCogs)));

        return new ReportData(
                "Category Profitability Summary (ক্যাটাগরিভিত্তিক লাভ-ক্ষতি)",
                biz.name(), biz.address(), biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Category", "Net Units Sold", "Sales Revenue", "Total COGS", "Gross Profit", "Profit Margin %"),
                rows, summary, LocalDateTime.now()
        );
    }

    private ReportData generateSupplierPurchaseAnalysis(Connection conn, Business biz, ReportCriteria c) throws SQLException {
        LocalDate start = c.startDate() != null ? c.startDate() : LocalDate.now().withDayOfMonth(1);
        LocalDate end = c.endDate() != null ? c.endDate() : LocalDate.now();
        String sStr = start.toString();
        String eStr = end.toString();

        StringBuilder sql = new StringBuilder(
                "SELECT s.id, COALESCE(s.supplier_code, 'SUP-' || s.id) AS sup_code, s.name AS sup_name, " +
                "COALESCE((SELECT COUNT(*) FROM goods_receipt_notes g WHERE g.supplier_id = s.id AND g.status = 'POSTED' AND date(g.receipt_date) BETWEEN ? AND ?), 0) AS grn_cnt, " +
                "COALESCE((SELECT SUM(g.total_cost_paisha) FROM goods_receipt_notes g WHERE g.supplier_id = s.id AND g.status = 'POSTED' AND date(g.receipt_date) BETWEEN ? AND ?), 0) " +
                "+ COALESCE((SELECT SUM(p.net_amount_paisha) FROM purchases p WHERE p.supplier_id = s.id AND p.is_reversed = 0 AND date(p.purchase_date) BETWEEN ? AND ?), 0) AS total_pur, " +
                "COALESCE((SELECT SUM(si.net_amount_paisha) FROM supplier_invoices si WHERE si.supplier_id = s.id AND si.status = 'POSTED' AND date(si.invoice_date) BETWEEN ? AND ?), 0) AS total_inv, " +
                "COALESCE((SELECT SUM(credit_paisha) - SUM(debit_paisha) FROM supplier_ledger sl WHERE sl.supplier_id = s.id), 0) AS cur_payable " +
                "FROM suppliers s WHERE s.business_id = ? "
        );
        if (c.partyId() != null) {
            sql.append("AND s.id = ? ");
        }
        sql.append("ORDER BY total_pur DESC, s.name ASC");

        List<List<String>> rows = new ArrayList<>();
        long totPurchases = 0L;
        long totPayables = 0L;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (int k = 0; k < 4; k++) {
                ps.setString(idx++, sStr);
                ps.setString(idx++, eStr);
            }
            ps.setLong(idx++, biz.id());
            if (c.partyId() != null) ps.setLong(idx++, c.partyId());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int grnCnt = rs.getInt("grn_cnt");
                    long totPur = rs.getLong("total_pur");
                    long totInv = rs.getLong("total_inv");
                    long payable = rs.getLong("cur_payable");
                    totPurchases += totPur;
                    totPayables += payable;
                    rows.add(List.of(
                            rs.getString("sup_code"),
                            rs.getString("sup_name"),
                            String.valueOf(grnCnt),
                            BengaliMoneyFormatter.format(Money.ofPaisha(totPur)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(totInv)),
                            BengaliMoneyFormatter.format(Money.ofPaisha(payable))
                    ));
                }
            }
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total Purchases (GRN + Bills)", BengaliMoneyFormatter.format(Money.ofPaisha(totPurchases)));
        summary.put("Total Current Payables", BengaliMoneyFormatter.format(Money.ofPaisha(totPayables)));
        summary.put("Suppliers Tracked", String.valueOf(rows.size()));

        return new ReportData(
                "Supplier Purchase & Payable Analysis (সরবরাহকারী ক্রয় বিশ্লেষণ)",
                biz.name(), biz.address(), biz.phone(),
                "Period: " + start.format(DATE_FMT) + " to " + end.format(DATE_FMT),
                List.of("Code", "Supplier Name", "Posted GRNs", "Total Purchased Cost", "Billed Amount", "Current Payable"),
                rows, summary, LocalDateTime.now()
        );
    }

    private long scalarLong(Connection conn, String sql, long bizId, String sStr, String eStr) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bizId);
            ps.setString(2, sStr);
            ps.setString(3, eStr);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}

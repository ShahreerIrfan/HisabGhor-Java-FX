package com.smartkhata.dao;

import com.smartkhata.model.dto.SaleListItemDto;
import com.smartkhata.model.entity.Sale;
import com.smartkhata.model.entity.SaleItem;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteSaleDao implements SaleDao {

    @Override
    public Sale save(Connection conn, Sale sale) throws SQLException {
        if (sale.id() == null) {
            String sql = "INSERT INTO sales (business_id, invoice_number, customer_id, total_amount_paisha, discount_paisha, " +
                    "net_amount_paisha, paid_amount_paisha, due_amount_paisha, payment_status, idempotency_key, invoice_date, " +
                    "notes, created_by_user_id, total_cogs_paisha, gross_profit_paisha) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, sale.businessId());
                ps.setString(2, sale.invoiceNumber());
                if (sale.customerId() != null) ps.setLong(3, sale.customerId()); else ps.setNull(3, Types.INTEGER);
                ps.setLong(4, sale.totalAmount().paisha());
                ps.setLong(5, sale.discount() != null ? sale.discount().paisha() : 0L);
                ps.setLong(6, sale.netAmount().paisha());
                ps.setLong(7, sale.paidAmount().paisha());
                ps.setLong(8, sale.dueAmount().paisha());
                ps.setString(9, sale.paymentStatus());
                ps.setString(10, sale.idempotencyKey());
                ps.setString(11, sale.invoiceDate() != null ? sale.invoiceDate().toString().replace("T", " ") : null);
                ps.setString(12, sale.notes());
                ps.setLong(13, sale.createdByUserId());
                ps.setLong(14, sale.totalCogs() != null ? sale.totalCogs().paisha() : 0L);
                ps.setLong(15, sale.grossProfit() != null ? sale.grossProfit().paisha() : 0L);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return sale.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE sales SET is_reversed = ?, reversal_reason = ?, reversed_at = ?, updated_at = datetime('now', 'localtime') " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, sale.isReversed() ? 1 : 0);
                ps.setString(2, sale.reversalReason());
                ps.setString(3, sale.reversedAt() != null ? sale.reversedAt().toString().replace("T", " ") : null);
                ps.setLong(4, sale.id());
                ps.setLong(5, sale.businessId());
                ps.executeUpdate();
            }
        }
        return sale;
    }

    @Override
    public void saveItems(Connection conn, List<SaleItem> items) throws SQLException {
        if (items == null || items.isEmpty()) return;

        String sql = "INSERT INTO sale_items (sale_id, product_id, quantity, unit_price_paisha, unit_cost_paisha, " +
                "discount_paisha, line_total_paisha, gross_profit_paisha, returned_qty) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (SaleItem item : items) {
                ps.setLong(1, item.saleId());
                ps.setLong(2, item.productId());
                ps.setInt(3, item.quantity());
                ps.setLong(4, item.unitPrice().paisha());
                ps.setLong(5, item.unitCost() != null ? item.unitCost().paisha() : 0L);
                ps.setLong(6, item.discount() != null ? item.discount().paisha() : 0L);
                ps.setLong(7, item.lineTotal().paisha());
                ps.setLong(8, item.grossProfit() != null ? item.grossProfit().paisha() : 0L);
                ps.setInt(9, item.returnedQty());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public Optional<Sale> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM sales WHERE id = ?";
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
    public Optional<Sale> findByInvoiceNumber(Connection conn, String invoiceNumber) throws SQLException {
        String sql = "SELECT * FROM sales WHERE invoice_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, invoiceNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Sale> findByIdempotencyKey(Connection conn, long businessId, String idempotencyKey) throws SQLException {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM sales WHERE business_id = ? AND idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, idempotencyKey.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<SaleItem> findItemsBySaleId(Connection conn, long saleId) throws SQLException {
        List<SaleItem> list = new ArrayList<>();
        String sql = "SELECT * FROM sale_items WHERE sale_id = ? ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new SaleItem(
                            rs.getLong("id"),
                            rs.getLong("sale_id"),
                            rs.getLong("product_id"),
                            rs.getInt("quantity"),
                            Money.ofPaisha(rs.getLong("unit_price_paisha")),
                            Money.ofPaisha(rs.getLong("unit_cost_paisha")),
                            Money.ofPaisha(rs.getLong("discount_paisha")),
                            Money.ofPaisha(rs.getLong("line_total_paisha")),
                            Money.ofPaisha(rs.getLong("gross_profit_paisha")),
                            rs.getInt("returned_qty")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<SaleListItemDto> searchSales(Connection conn, long businessId, String query, Long customerId,
                                            String paymentStatus, LocalDate startDate, LocalDate endDate) throws SQLException {
        List<SaleListItemDto> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT s.id, s.invoice_number, s.customer_id, c.name AS customer_name, " +
                "s.invoice_date, s.net_amount_paisha, s.paid_amount_paisha, s.due_amount_paisha, s.payment_status, s.is_reversed, " +
                "(SELECT COUNT(*) FROM sale_items si WHERE si.sale_id = s.id) AS item_count " +
                "FROM sales s LEFT JOIN customers c ON s.customer_id = c.id " +
                "WHERE s.business_id = ?");

        if (customerId != null && customerId > 0) {
            sql.append(" AND s.customer_id = ?");
        }
        boolean hasStatusFilter = paymentStatus != null && !paymentStatus.isBlank()
                && !"ALL".equalsIgnoreCase(paymentStatus)
                && !"All Statuses".equalsIgnoreCase(paymentStatus);
        if (hasStatusFilter) {
            sql.append(" AND s.payment_status = ?");
        }
        if (startDate != null) {
            sql.append(" AND date(s.invoice_date) >= ?");
        }
        if (endDate != null) {
            sql.append(" AND date(s.invoice_date) <= ?");
        }
        boolean hasQuery = query != null && !query.trim().isBlank();
        if (hasQuery) {
            sql.append(" AND (s.invoice_number LIKE ? OR c.name LIKE ?)");
        }
        sql.append(" ORDER BY s.invoice_date DESC, s.id DESC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, businessId);

            if (customerId != null && customerId > 0) {
                ps.setLong(idx++, customerId);
            }
            if (hasStatusFilter) {
                ps.setString(idx++, paymentStatus.trim());
            }
            if (startDate != null) {
                ps.setString(idx++, startDate.toString());
            }
            if (endDate != null) {
                ps.setString(idx++, endDate.toString());
            }
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(idx++, pattern);
                ps.setString(idx++, pattern);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long custId = rs.getObject("customer_id") != null ? rs.getLong("customer_id") : null;
                    String custName = rs.getString("customer_name");
                    if (custName == null || custName.isBlank()) {
                        custName = "Walk-in Cash Customer";
                    }

                    list.add(new SaleListItemDto(
                            rs.getLong("id"),
                            rs.getString("invoice_number"),
                            custId,
                            custName,
                            LocalDateTime.parse(rs.getString("invoice_date").replace(" ", "T")),
                            Money.ofPaisha(rs.getLong("net_amount_paisha")),
                            Money.ofPaisha(rs.getLong("paid_amount_paisha")),
                            Money.ofPaisha(rs.getLong("due_amount_paisha")),
                            rs.getString("payment_status"),
                            rs.getInt("is_reversed") == 1,
                            rs.getInt("item_count")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public int getNextInvoiceSequence(Connection conn, long businessId, String datePrefix) throws SQLException {
        String pattern = "INV-" + datePrefix + "-%";
        String sql = "SELECT invoice_number FROM sales WHERE business_id = ? AND invoice_number LIKE ?";
        int maxSeq = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String num = rs.getString(1);
                    if (num != null) {
                        int dash = num.lastIndexOf('-');
                        if (dash >= 0 && dash + 1 < num.length()) {
                            try {
                                int parsed = Integer.parseInt(num.substring(dash + 1).trim());
                                if (parsed > maxSeq) maxSeq = parsed;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        }
        return maxSeq + 1;
    }

    @Override
    public void voidSale(Connection conn, long saleId, long businessId, String reason, LocalDateTime reversedAt) throws SQLException {
        String sql = "UPDATE sales SET is_reversed = 1, reversal_reason = ?, reversed_at = ?, updated_at = datetime('now', 'localtime') " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, reversedAt.toString().replace("T", " "));
            ps.setLong(3, saleId);
            ps.setLong(4, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public void updateNotes(Connection conn, long saleId, long businessId, String notes) throws SQLException {
        String sql = "UPDATE sales SET notes = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (notes != null && !notes.isBlank()) {
                ps.setString(1, notes.trim());
            } else {
                ps.setNull(1, Types.VARCHAR);
            }
            ps.setLong(2, saleId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean hasActiveLinkedPayments(Connection conn, long saleId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM payment_allocations pa " +
                "JOIN payments py ON pa.payment_id = py.id " +
                "WHERE pa.sale_id = ? AND py.is_reversed = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    @Override
    public boolean deleteVoided(Connection conn, long saleId, long businessId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payment_allocations WHERE sale_id = ?")) {
            ps.setLong(1, saleId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM stock_movements WHERE business_id = ? AND reference_type = 'SALE' AND reference_id = ?")) {
            ps.setLong(1, businessId);
            ps.setLong(2, saleId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM customer_ledger WHERE business_id = ? AND reference_id = ? AND entry_type IN ('SALE_INVOICE', 'INVOICE_REVERSAL')")) {
            ps.setLong(1, businessId);
            ps.setLong(2, saleId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sales WHERE id = ? AND business_id = ? AND is_reversed = 1")) {
            ps.setLong(1, saleId);
            ps.setLong(2, businessId);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public List<Sale> findUnpaidSalesByCustomerId(Connection conn, long customerId) throws SQLException {
        List<Sale> sales = new ArrayList<>();
        String sql = "SELECT * FROM sales " +
                "WHERE customer_id = ? AND is_reversed = 0 AND due_amount_paisha > 0 " +
                "ORDER BY invoice_date ASC, id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sales.add(mapRow(rs));
                }
            }
        }
        return sales;
    }

    @Override
    public void updatePaymentStatus(Connection conn, long saleId, Money paidAmount, Money dueAmount, String paymentStatus) throws SQLException {
        String sql = "UPDATE sales SET paid_amount_paisha = ?, due_amount_paisha = ?, payment_status = ?, updated_at = datetime('now', 'localtime') WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paidAmount.paisha());
            ps.setLong(2, dueAmount.paisha());
            ps.setString(3, paymentStatus);
            ps.setLong(4, saleId);
            ps.executeUpdate();
        }
    }

    @Override
    public void updateOrderHeader(Connection conn, Sale sale) throws SQLException {
        String sql = "UPDATE sales SET customer_id = ?, total_amount_paisha = ?, discount_paisha = ?, " +
                "net_amount_paisha = ?, paid_amount_paisha = ?, due_amount_paisha = ?, payment_status = ?, " +
                "notes = ?, total_cogs_paisha = ?, gross_profit_paisha = ?, updated_at = datetime('now', 'localtime') " +
                "WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (sale.customerId() != null) ps.setLong(1, sale.customerId()); else ps.setNull(1, Types.INTEGER);
            ps.setLong(2, sale.totalAmount().paisha());
            ps.setLong(3, sale.discount() != null ? sale.discount().paisha() : 0L);
            ps.setLong(4, sale.netAmount().paisha());
            ps.setLong(5, sale.paidAmount().paisha());
            ps.setLong(6, sale.dueAmount().paisha());
            ps.setString(7, sale.paymentStatus());
            ps.setString(8, sale.notes());
            ps.setLong(9, sale.totalCogs() != null ? sale.totalCogs().paisha() : 0L);
            ps.setLong(10, sale.grossProfit() != null ? sale.grossProfit().paisha() : 0L);
            ps.setLong(11, sale.id());
            ps.setLong(12, sale.businessId());
            ps.executeUpdate();
        }
    }

    @Override
    public void replaceItems(Connection conn, long saleId, List<SaleItem> items) throws SQLException {
        try (PreparedStatement psDel = conn.prepareStatement("DELETE FROM sale_items WHERE sale_id = ?")) {
            psDel.setLong(1, saleId);
            psDel.executeUpdate();
        }
        saveItems(conn, items);
    }

    @Override
    public Optional<String> findPrimaryPaymentMethodBySaleId(Connection conn, long saleId) throws SQLException {
        String sql = "SELECT py.payment_method FROM payment_allocations pa " +
                "JOIN payments py ON pa.payment_id = py.id " +
                "WHERE pa.sale_id = ? AND py.is_reversed = 0 ORDER BY py.id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean deleteSaleRecord(Connection conn, long saleId, long businessId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sale_items WHERE sale_id = ?")) {
            ps.setLong(1, saleId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sales WHERE id = ? AND business_id = ?")) {
            ps.setLong(1, saleId);
            ps.setLong(2, businessId);
            return ps.executeUpdate() > 0;
        }
    }

    private Sale mapRow(ResultSet rs) throws SQLException {
        Long custId = rs.getObject("customer_id") != null ? rs.getLong("customer_id") : null;
        String revAtStr = rs.getString("reversed_at");
        LocalDateTime revAt = (revAtStr != null && !revAtStr.isBlank()) ? LocalDateTime.parse(revAtStr.replace(" ", "T")) : null;

        return new Sale(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("invoice_number"),
                custId,
                Money.ofPaisha(rs.getLong("total_amount_paisha")),
                Money.ofPaisha(rs.getLong("discount_paisha")),
                Money.ofPaisha(rs.getLong("net_amount_paisha")),
                Money.ofPaisha(rs.getLong("paid_amount_paisha")),
                Money.ofPaisha(rs.getLong("due_amount_paisha")),
                rs.getString("payment_status"),
                rs.getString("idempotency_key"),
                LocalDateTime.parse(rs.getString("invoice_date").replace(" ", "T")),
                rs.getString("notes"),
                rs.getLong("created_by_user_id"),
                rs.getInt("is_reversed") == 1,
                rs.getString("reversal_reason"),
                revAt,
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T")),
                Money.ofPaisha(rs.getLong("total_cogs_paisha")),
                Money.ofPaisha(rs.getLong("gross_profit_paisha"))
        );
    }
}

package com.smartkhata.dao;

import com.smartkhata.model.entity.Product;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteProductDao implements ProductDao {

    private static final String BASE_COLUMNS =
            "id, business_id, category_id, name, barcode, sku, unit, cost_price_paisha, " +
            "selling_price_paisha, low_stock_threshold, is_archived, created_at, updated_at, image_path, " +
            "trade_price_paisha, mrp_paisha, last_purchase_price_paisha, purchase_unit, pack_size";

    @Override
    public Product save(Connection conn, Product product) throws SQLException {
        Money cost = product.costPrice() != null ? product.costPrice() : Money.ZERO;
        Money sell = product.sellingPrice() != null ? product.sellingPrice() : Money.ZERO;
        Money tp = product.tradePrice() != null ? product.tradePrice() : cost;
        Money mrp = product.mrp() != null ? product.mrp() : sell;
        Money lastPur = product.lastPurchasePrice() != null ? product.lastPurchasePrice() : cost;
        String baseUnit = product.unit() != null && !product.unit().isBlank() ? product.unit().trim() : "pcs";
        String purUnit = product.purchaseUnit() != null && !product.purchaseUnit().isBlank()
                ? product.purchaseUnit().trim() : baseUnit;
        int packSize = Math.max(1, product.packSize());

        if (product.id() == null) {
            String sql = "INSERT INTO products (business_id, category_id, name, barcode, sku, unit, " +
                    "cost_price_paisha, selling_price_paisha, low_stock_threshold, is_archived, image_path, " +
                    "trade_price_paisha, mrp_paisha, last_purchase_price_paisha, purchase_unit, pack_size) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, product.businessId());
                if (product.categoryId() != null) ps.setLong(2, product.categoryId()); else ps.setNull(2, Types.INTEGER);
                ps.setString(3, product.name());
                ps.setString(4, product.barcode());
                ps.setString(5, product.sku());
                ps.setString(6, baseUnit);
                ps.setLong(7, cost.paisha());
                ps.setLong(8, sell.paisha());
                ps.setInt(9, product.lowStockThreshold());
                ps.setInt(10, product.isArchived() ? 1 : 0);
                if (product.imagePath() != null && !product.imagePath().isBlank()) {
                    ps.setString(11, product.imagePath().trim());
                } else {
                    ps.setNull(11, Types.VARCHAR);
                }
                ps.setLong(12, tp.paisha());
                ps.setLong(13, mrp.paisha());
                ps.setLong(14, lastPur.paisha());
                ps.setString(15, purUnit);
                ps.setInt(16, packSize);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return product.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE products SET category_id = ?, name = ?, barcode = ?, sku = ?, unit = ?, " +
                    "cost_price_paisha = ?, selling_price_paisha = ?, low_stock_threshold = ?, is_archived = ?, " +
                    "image_path = ?, trade_price_paisha = ?, mrp_paisha = ?, last_purchase_price_paisha = ?, " +
                    "purchase_unit = ?, pack_size = ?, updated_at = datetime('now', 'localtime') " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (product.categoryId() != null) ps.setLong(1, product.categoryId()); else ps.setNull(1, Types.INTEGER);
                ps.setString(2, product.name());
                ps.setString(3, product.barcode());
                ps.setString(4, product.sku());
                ps.setString(5, baseUnit);
                ps.setLong(6, cost.paisha());
                ps.setLong(7, sell.paisha());
                ps.setInt(8, product.lowStockThreshold());
                ps.setInt(9, product.isArchived() ? 1 : 0);
                if (product.imagePath() != null && !product.imagePath().isBlank()) {
                    ps.setString(10, product.imagePath().trim());
                } else {
                    ps.setNull(10, Types.VARCHAR);
                }
                ps.setLong(11, tp.paisha());
                ps.setLong(12, mrp.paisha());
                ps.setLong(13, lastPur.paisha());
                ps.setString(14, purUnit);
                ps.setInt(15, packSize);
                ps.setLong(16, product.id());
                ps.setLong(17, product.businessId());
                ps.executeUpdate();
            }
        }
        return product;
    }

    @Override
    public Optional<Product> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM products WHERE id = ?";
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
    public List<Product> findAll(Connection conn, long businessId) throws SQLException {
        return search(conn, businessId, null, null, false);
    }

    @Override
    public List<Product> search(Connection conn, long businessId, String query, Long categoryId, boolean includeArchived) throws SQLException {
        List<Product> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM products WHERE business_id = ?");

        if (!includeArchived) {
            sql.append(" AND is_archived = 0");
        }
        if (categoryId != null && categoryId > 0) {
            sql.append(" AND category_id = ?");
        }
        boolean hasQuery = query != null && !query.trim().isBlank();
        if (hasQuery) {
            sql.append(" AND (name LIKE ? OR barcode LIKE ? OR sku LIKE ?)");
        }
        sql.append(" ORDER BY name COLLATE NOCASE ASC");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int paramIndex = 1;
            ps.setLong(paramIndex++, businessId);

            if (categoryId != null && categoryId > 0) {
                ps.setLong(paramIndex++, categoryId);
            }
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                ps.setString(paramIndex++, pattern);
                ps.setString(paramIndex++, pattern);
                ps.setString(paramIndex++, pattern);
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
    public Optional<Product> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM products WHERE business_id = ? AND name = ? COLLATE NOCASE";
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
    public Optional<Product> findByBarcodeAndBusiness(Connection conn, long businessId, String barcode) throws SQLException {
        if (barcode == null || barcode.trim().isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT " + BASE_COLUMNS + " FROM products WHERE business_id = ? AND barcode = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, barcode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void setArchived(Connection conn, long productId, long businessId, boolean archived) throws SQLException {
        String sql = "UPDATE products SET is_archived = ?, updated_at = datetime('now', 'localtime') WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, archived ? 1 : 0);
            ps.setLong(2, productId);
            ps.setLong(3, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean hasLinkedTransactionRecords(Connection conn, long productId) throws SQLException {
        String sql = "SELECT (" +
                "  (SELECT COUNT(*) FROM sale_items WHERE product_id = ?) + " +
                "  (SELECT COUNT(*) FROM purchase_items WHERE product_id = ?) + " +
                "  (SELECT COUNT(*) FROM stock_movements WHERE product_id = ? AND movement_type != 'OPENING')" +
                ") AS cnt";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setLong(2, productId);
            ps.setLong(3, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("cnt") > 0;
                }
            }
        }
        return false;
    }

    @Override
    public void delete(Connection conn, long productId, long businessId) throws SQLException {
        try (PreparedStatement psMov = conn.prepareStatement(
                "DELETE FROM stock_movements WHERE product_id = ? AND business_id = ?")) {
            psMov.setLong(1, productId);
            psMov.setLong(2, businessId);
            psMov.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM products WHERE id = ? AND business_id = ?")) {
            ps.setLong(1, productId);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    @Override
    public int getCurrentStock(Connection conn, long productId) throws SQLException {
        String sql = "SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    @Override
    public int getLowStockCount(Connection conn, long businessId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM products p " +
                "WHERE p.business_id = ? AND p.is_archived = 0 " +
                "AND (SELECT COALESCE(SUM(sm.quantity), 0) FROM stock_movements sm WHERE sm.product_id = p.id) <= p.low_stock_threshold";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    @Override
    public Money getTotalInventoryValuation(Connection conn, long businessId) throws SQLException {
        // Documented Cost Method: Current recorded cost_price_paisha * non-negative stock_movements quantity
        String sql = "SELECT COALESCE(SUM(p.cost_price_paisha * MAX(0, (SELECT COALESCE(SUM(sm.quantity), 0) FROM stock_movements sm WHERE sm.product_id = p.id))), 0) " +
                "FROM products p WHERE p.business_id = ? AND p.is_archived = 0";
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

    @Override
    public void updateCostPrice(Connection conn, long productId, Money newCostPrice) throws SQLException {
        String sql = "UPDATE products SET cost_price_paisha = ?, updated_at = datetime('now', 'localtime') WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newCostPrice != null ? newCostPrice.paisha() : 0L);
            ps.setLong(2, productId);
            ps.executeUpdate();
        }
    }

    @Override
    public void updateCostAndLastPurchase(Connection conn, long productId, Money newWeightedAvgCost, Money lastPurchasePrice) throws SQLException {
        String sql = "UPDATE products SET cost_price_paisha = ?, last_purchase_price_paisha = ?, updated_at = datetime('now', 'localtime') WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newWeightedAvgCost != null ? newWeightedAvgCost.paisha() : 0L);
            ps.setLong(2, lastPurchasePrice != null ? lastPurchasePrice.paisha() : 0L);
            ps.setLong(3, productId);
            ps.executeUpdate();
        }
    }

    private Product mapRow(ResultSet rs) throws SQLException {
        Long catId = rs.getObject("category_id") != null ? rs.getLong("category_id") : null;
        String unit = rs.getString("unit");
        String purUnit = rs.getString("purchase_unit");
        int packSize = Math.max(1, rs.getInt("pack_size"));
        return new Product(
                rs.getLong("id"),
                rs.getLong("business_id"),
                catId,
                rs.getString("name"),
                rs.getString("barcode"),
                rs.getString("sku"),
                unit,
                Money.ofPaisha(rs.getLong("cost_price_paisha")),
                Money.ofPaisha(rs.getLong("selling_price_paisha")),
                rs.getInt("low_stock_threshold"),
                rs.getInt("is_archived") == 1,
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T")),
                rs.getString("image_path"),
                Money.ofPaisha(rs.getLong("trade_price_paisha")),
                Money.ofPaisha(rs.getLong("mrp_paisha")),
                Money.ofPaisha(rs.getLong("last_purchase_price_paisha")),
                purUnit != null && !purUnit.isBlank() ? purUnit : unit,
                packSize
        );
    }
}

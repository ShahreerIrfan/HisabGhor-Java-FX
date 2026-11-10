package com.smartkhata.dao;

import com.smartkhata.model.dto.ProductSupplierViewDto;
import com.smartkhata.model.entity.ProductSupplier;
import com.smartkhata.model.vo.Money;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteProductSupplierDao implements ProductSupplierDao {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String BASE_COLUMNS =
            "id, business_id, product_id, supplier_id, trade_price_paisha, supplier_product_code, " +
            "min_order_qty, purchase_unit, pack_size, is_preferred, lead_time_days, " +
            "last_purchase_price_paisha, last_purchase_date, is_active, created_at, updated_at";

    @Override
    public ProductSupplier save(Connection conn, ProductSupplier mapping) throws SQLException {
        if (mapping.id() == null) {
            String sql = "INSERT INTO product_suppliers (business_id, product_id, supplier_id, " +
                    "trade_price_paisha, supplier_product_code, min_order_qty, purchase_unit, pack_size, " +
                    "is_preferred, lead_time_days, last_purchase_price_paisha, last_purchase_date, is_active) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, mapping.businessId());
                ps.setLong(2, mapping.productId());
                ps.setLong(3, mapping.supplierId());
                ps.setLong(4, mapping.tradePrice() != null ? mapping.tradePrice().paisha() : 0L);
                setNullableString(ps, 5, mapping.supplierProductCode());
                ps.setInt(6, Math.max(0, mapping.minOrderQty()));
                ps.setString(7, mapping.purchaseUnit() != null && !mapping.purchaseUnit().isBlank() ? mapping.purchaseUnit().trim() : "pcs");
                ps.setInt(8, Math.max(1, mapping.packSize()));
                ps.setInt(9, mapping.isPreferred() ? 1 : 0);
                ps.setInt(10, Math.max(0, mapping.leadTimeDays()));
                ps.setLong(11, mapping.lastPurchasePrice() != null ? mapping.lastPurchasePrice().paisha() : 0L);
                if (mapping.lastPurchaseDate() != null) {
                    ps.setString(12, mapping.lastPurchaseDate().format(ISO_DT));
                } else {
                    ps.setNull(12, Types.VARCHAR);
                }
                ps.setInt(13, mapping.isActive() ? 1 : 0);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return findById(conn, rs.getLong(1)).orElse(mapping.withId(rs.getLong(1)));
                    }
                }
            }
        } else {
            String sql = "UPDATE product_suppliers SET trade_price_paisha = ?, supplier_product_code = ?, " +
                    "min_order_qty = ?, purchase_unit = ?, pack_size = ?, is_preferred = ?, " +
                    "lead_time_days = ?, is_active = ?, updated_at = datetime('now', 'localtime') " +
                    "WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, mapping.tradePrice() != null ? mapping.tradePrice().paisha() : 0L);
                setNullableString(ps, 2, mapping.supplierProductCode());
                ps.setInt(3, Math.max(0, mapping.minOrderQty()));
                ps.setString(4, mapping.purchaseUnit() != null && !mapping.purchaseUnit().isBlank() ? mapping.purchaseUnit().trim() : "pcs");
                ps.setInt(5, Math.max(1, mapping.packSize()));
                ps.setInt(6, mapping.isPreferred() ? 1 : 0);
                ps.setInt(7, Math.max(0, mapping.leadTimeDays()));
                ps.setInt(8, mapping.isActive() ? 1 : 0);
                ps.setLong(9, mapping.id());
                ps.setLong(10, mapping.businessId());
                ps.executeUpdate();
                return findById(conn, mapping.id()).orElse(mapping);
            }
        }
        return mapping;
    }

    @Override
    public Optional<ProductSupplier> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM product_suppliers WHERE id = ?";
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
    public Optional<ProductSupplier> findByProductAndSupplier(Connection conn, long businessId, long productId, long supplierId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM product_suppliers WHERE business_id = ? AND product_id = ? AND supplier_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, productId);
            ps.setLong(3, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ProductSupplierViewDto> findByProductId(Connection conn, long businessId, long productId, boolean includeInactive) throws SQLException {
        String sql = "SELECT ps.*, p.name AS product_name, p.sku AS product_sku, p.unit AS base_unit, " +
                "s.name AS supplier_name, s.supplier_code " +
                "FROM product_suppliers ps " +
                "JOIN products p ON p.id = ps.product_id " +
                "JOIN suppliers s ON s.id = ps.supplier_id " +
                "WHERE ps.business_id = ? AND ps.product_id = ? " +
                (includeInactive ? "" : "AND ps.is_active = 1 ") +
                "ORDER BY ps.is_preferred DESC, s.name COLLATE NOCASE ASC";
        List<ProductSupplierViewDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapViewDto(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<ProductSupplierViewDto> findBySupplierId(Connection conn, long businessId, long supplierId, boolean includeInactive) throws SQLException {
        String sql = "SELECT ps.*, p.name AS product_name, p.sku AS product_sku, p.unit AS base_unit, " +
                "s.name AS supplier_name, s.supplier_code " +
                "FROM product_suppliers ps " +
                "JOIN products p ON p.id = ps.product_id " +
                "JOIN suppliers s ON s.id = ps.supplier_id " +
                "WHERE ps.business_id = ? AND ps.supplier_id = ? " +
                (includeInactive ? "" : "AND ps.is_active = 1 ") +
                "ORDER BY p.name COLLATE NOCASE ASC";
        List<ProductSupplierViewDto> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapViewDto(rs));
                }
            }
        }
        return list;
    }

    @Override
    public void clearPreferredForProduct(Connection conn, long businessId, long productId, Long exceptMappingId) throws SQLException {
        String sql = "UPDATE product_suppliers SET is_preferred = 0, updated_at = datetime('now', 'localtime') " +
                "WHERE business_id = ? AND product_id = ?" +
                (exceptMappingId != null ? " AND id != ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setLong(2, productId);
            if (exceptMappingId != null) {
                ps.setLong(3, exceptMappingId);
            }
            ps.executeUpdate();
        }
    }

    @Override
    public void updateLastPurchase(Connection conn, long businessId, long productId, long supplierId,
                                   Money lastPrice, LocalDateTime purchaseDate) throws SQLException {
        String sql = "UPDATE product_suppliers SET last_purchase_price_paisha = ?, last_purchase_date = ?, " +
                "updated_at = datetime('now', 'localtime') WHERE business_id = ? AND product_id = ? AND supplier_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastPrice != null ? lastPrice.paisha() : 0L);
            ps.setString(2, (purchaseDate != null ? purchaseDate : LocalDateTime.now()).format(ISO_DT));
            ps.setLong(3, businessId);
            ps.setLong(4, productId);
            ps.setLong(5, supplierId);
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(Connection conn, long id, long businessId) throws SQLException {
        String sql = "DELETE FROM product_suppliers WHERE id = ? AND business_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    private ProductSupplier mapRow(ResultSet rs) throws SQLException {
        String lastDateStr = rs.getString("last_purchase_date");
        return new ProductSupplier(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getLong("product_id"),
                rs.getLong("supplier_id"),
                Money.ofPaisha(rs.getLong("trade_price_paisha")),
                rs.getString("supplier_product_code"),
                rs.getInt("min_order_qty"),
                rs.getString("purchase_unit"),
                rs.getInt("pack_size"),
                rs.getInt("is_preferred") == 1,
                rs.getInt("lead_time_days"),
                Money.ofPaisha(rs.getLong("last_purchase_price_paisha")),
                lastDateStr != null && !lastDateStr.isBlank() ? parseDateTime(lastDateStr) : null,
                rs.getInt("is_active") == 1,
                parseDateTime(rs.getString("created_at")),
                parseDateTime(rs.getString("updated_at"))
        );
    }

    private ProductSupplierViewDto mapViewDto(ResultSet rs) throws SQLException {
        String lastDateStr = rs.getString("last_purchase_date");
        return new ProductSupplierViewDto(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("product_sku"),
                rs.getString("base_unit"),
                rs.getLong("supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("supplier_code"),
                Money.ofPaisha(rs.getLong("trade_price_paisha")),
                rs.getString("supplier_product_code"),
                rs.getInt("min_order_qty"),
                rs.getString("purchase_unit"),
                rs.getInt("pack_size"),
                rs.getInt("is_preferred") == 1,
                rs.getInt("lead_time_days"),
                Money.ofPaisha(rs.getLong("last_purchase_price_paisha")),
                lastDateStr != null && !lastDateStr.isBlank() ? parseDateTime(lastDateStr) : null,
                rs.getInt("is_active") == 1
        );
    }

    private static void setNullableString(PreparedStatement ps, int index, String val) throws SQLException {
        if (val != null && !val.trim().isBlank()) {
            ps.setString(index, val.trim());
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        return LocalDateTime.parse(s.trim().replace(" ", "T"));
    }
}


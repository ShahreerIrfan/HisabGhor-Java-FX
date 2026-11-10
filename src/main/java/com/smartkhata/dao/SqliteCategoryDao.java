package com.smartkhata.dao;

import com.smartkhata.model.entity.Category;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteCategoryDao implements CategoryDao {

    @Override
    public Category save(Connection conn, Category category) throws SQLException {
        if (category.id() == null) {
            String sql = "INSERT INTO categories (business_id, name, description) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, category.businessId());
                ps.setString(2, category.name());
                ps.setString(3, category.description());
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return category.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE categories SET name = ?, description = ? WHERE id = ? AND business_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, category.name());
                ps.setString(2, category.description());
                ps.setLong(3, category.id());
                ps.setLong(4, category.businessId());
                ps.executeUpdate();
            }
        }
        return category;
    }

    @Override
    public Optional<Category> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, business_id, name, description, created_at FROM categories WHERE id = ?";
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
    public Optional<Category> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException {
        String sql = "SELECT id, business_id, name, description, created_at FROM categories WHERE business_id = ? AND name = ? COLLATE NOCASE";
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
    public List<Category> findAll(Connection conn, long businessId) throws SQLException {
        List<Category> list = new ArrayList<>();
        String sql = "SELECT id, business_id, name, description, created_at FROM categories WHERE business_id = ? ORDER BY name COLLATE NOCASE ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    @Override
    public void delete(Connection conn, long categoryId, long businessId) throws SQLException {
        try (PreparedStatement unlink = conn.prepareStatement(
                "UPDATE products SET category_id = NULL WHERE category_id = ? AND business_id = ?")) {
            unlink.setLong(1, categoryId);
            unlink.setLong(2, businessId);
            unlink.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM categories WHERE id = ? AND business_id = ?")) {
            ps.setLong(1, categoryId);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    private Category mapRow(ResultSet rs) throws SQLException {
        return new Category(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("name"),
                rs.getString("description"),
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T"))
        );
    }
}


package com.smartkhata.dao;

import com.smartkhata.model.entity.Business;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.Optional;

public class SqliteBusinessDao implements BusinessDao {

    @Override
    public Business save(Connection conn, Business business) throws SQLException {
        if (business.id() == null) {
            String sql = "INSERT INTO businesses (name, legal_name, phone, address, trade_license, currency_code) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, business.name());
                ps.setString(2, business.legalName());
                ps.setString(3, business.phone());
                ps.setString(4, business.address());
                ps.setString(5, business.tradeLicense());
                ps.setString(6, business.currencyCode() != null ? business.currencyCode() : "BDT");
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long generatedId = rs.getLong(1);
                        return business.withId(generatedId);
                    }
                }
            }
        } else {
            String sql = "UPDATE businesses SET name = ?, legal_name = ?, phone = ?, address = ?, trade_license = ?, currency_code = ?, updated_at = datetime('now', 'localtime') WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, business.name());
                ps.setString(2, business.legalName());
                ps.setString(3, business.phone());
                ps.setString(4, business.address());
                ps.setString(5, business.tradeLicense());
                ps.setString(6, business.currencyCode());
                ps.setLong(7, business.id());
                ps.executeUpdate();
            }
        }
        return business;
    }

    @Override
    public Optional<Business> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, name, legal_name, phone, address, trade_license, currency_code, created_at, updated_at FROM businesses WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Business(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("legal_name"),
                            rs.getString("phone"),
                            rs.getString("address"),
                            rs.getString("trade_license"),
                            rs.getString("currency_code"),
                            LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                            LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T"))
                    ));
                }
            }
        }
        return Optional.empty();
    }
}


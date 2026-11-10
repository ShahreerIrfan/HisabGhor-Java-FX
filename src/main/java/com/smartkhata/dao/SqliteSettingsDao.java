package com.smartkhata.dao;

import com.smartkhata.model.dto.SettingEntryDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteSettingsDao implements SettingsDao {

    @Override
    public Optional<String> get(Connection conn, long businessId, String key) throws SQLException {
        String sql = "SELECT setting_value FROM settings WHERE business_id = ? AND setting_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void set(Connection conn, long businessId, String key, String value) throws SQLException {
        String sql = "INSERT INTO settings (business_id, setting_key, setting_value) VALUES (?, ?, ?) " +
                "ON CONFLICT(business_id, setting_key) DO UPDATE SET setting_value = excluded.setting_value, updated_at = datetime('now', 'localtime')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        }
    }

    @Override
    public void remove(Connection conn, long businessId, String key) throws SQLException {
        String sql = "DELETE FROM settings WHERE business_id = ? AND setting_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    @Override
    public List<SettingEntryDto> findAllByBusiness(Connection conn, long businessId) throws SQLException {
        List<SettingEntryDto> list = new ArrayList<>();
        String sql = "SELECT setting_key, setting_value, updated_at FROM settings " +
                "WHERE business_id = ? AND setting_key NOT LIKE 'owner_recovery_%' ORDER BY setting_key ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("setting_key");
                    String value = rs.getString("setting_value");
                    String updatedStr = rs.getString("updated_at");
                    LocalDateTime updatedAt = (updatedStr != null && !updatedStr.isBlank())
                            ? LocalDateTime.parse(updatedStr.replace(" ", "T"))
                            : LocalDateTime.now();
                    list.add(new SettingEntryDto(key, value, updatedAt));
                }
            }
        }
        return list;
    }
}


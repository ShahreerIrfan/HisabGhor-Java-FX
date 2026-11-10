package com.smartkhata.dao;

import com.smartkhata.model.dto.SettingEntryDto;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface SettingsDao {
    Optional<String> get(Connection conn, long businessId, String key) throws SQLException;
    void set(Connection conn, long businessId, String key, String value) throws SQLException;
    void remove(Connection conn, long businessId, String key) throws SQLException;
    List<SettingEntryDto> findAllByBusiness(Connection conn, long businessId) throws SQLException;
}


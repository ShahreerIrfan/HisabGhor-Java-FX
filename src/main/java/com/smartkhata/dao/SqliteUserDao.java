package com.smartkhata.dao;

import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteUserDao implements UserDao {

    @Override
    public User save(Connection conn, User user) throws SQLException {
        if (user.id() == null) {
            String sql = "INSERT INTO users (business_id, username, full_name, password_hash, salt, role, is_active) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, user.businessId());
                ps.setString(2, user.username());
                ps.setString(3, user.fullName());
                ps.setString(4, user.passwordHash());
                ps.setString(5, user.salt());
                ps.setString(6, user.role().name());
                ps.setInt(7, user.isActive() ? 1 : 0);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return user.withId(rs.getLong(1));
                    }
                }
            }
        } else {
            String sql = "UPDATE users SET full_name = ?, password_hash = ?, salt = ?, role = ?, is_active = ?, " +
                    "updated_at = datetime('now', 'localtime') WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, user.fullName());
                ps.setString(2, user.passwordHash());
                ps.setString(3, user.salt());
                ps.setString(4, user.role().name());
                ps.setInt(5, user.isActive() ? 1 : 0);
                ps.setLong(6, user.id());
                ps.executeUpdate();
            }
        }
        return user;
    }

    @Override
    public Optional<User> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, business_id, username, full_name, password_hash, salt, role, is_active, last_login_at, created_at, updated_at " +
                "FROM users WHERE id = ?";
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
    public Optional<User> findByUsername(Connection conn, String username) throws SQLException {
        String sql = "SELECT id, business_id, username, full_name, password_hash, salt, role, is_active, last_login_at, created_at, updated_at " +
                "FROM users WHERE username = ? COLLATE NOCASE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<User> findFirstByRole(Connection conn, UserRole role) throws SQLException {
        String sql = "SELECT id, business_id, username, full_name, password_hash, salt, role, is_active, last_login_at, created_at, updated_at " +
                "FROM users WHERE role = ? AND is_active = 1 LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean hasAnyOwner(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE role = 'OWNER' AND is_active = 1";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public void updateLastLogin(Connection conn, long userId) throws SQLException {
        String sql = "UPDATE users SET last_login_at = datetime('now', 'localtime') WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    @Override
    public List<User> findAllByBusiness(Connection conn, long businessId) throws SQLException {
        List<User> list = new ArrayList<>();
        String sql = "SELECT id, business_id, username, full_name, password_hash, salt, role, is_active, last_login_at, created_at, updated_at " +
                "FROM users WHERE business_id = ? ORDER BY id ASC";
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
    public boolean hasLinkedTransactions(Connection conn, long userId) throws SQLException {
        String[] checks = new String[] {
                "SELECT 1 FROM sales WHERE created_by_user_id = ? LIMIT 1",
                "SELECT 1 FROM purchases WHERE created_by_user_id = ? LIMIT 1",
                "SELECT 1 FROM payments WHERE created_by_user_id = ? LIMIT 1",
                "SELECT 1 FROM expenses WHERE created_by_user_id = ? LIMIT 1",
                "SELECT 1 FROM other_incomes WHERE created_by_user_id = ? LIMIT 1",
                "SELECT 1 FROM stock_movements WHERE created_by_user_id = ? LIMIT 1"
        };
        for (String sql : checks) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public int countActiveOwners(Connection conn, long businessId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE business_id = ? AND role = 'OWNER' AND is_active = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, businessId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public void delete(Connection conn, long userId, long businessId) throws SQLException {
        try (PreparedStatement psAudit = conn.prepareStatement("UPDATE audit_logs SET user_id = NULL WHERE user_id = ? AND business_id = ?")) {
            psAudit.setLong(1, userId);
            psAudit.setLong(2, businessId);
            psAudit.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ? AND business_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, businessId);
            ps.executeUpdate();
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        String lastLoginStr = rs.getString("last_login_at");
        LocalDateTime lastLogin = (lastLoginStr != null && !lastLoginStr.isBlank())
                ? LocalDateTime.parse(lastLoginStr.replace(" ", "T")) : null;

        return new User(
                rs.getLong("id"),
                rs.getLong("business_id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getString("salt"),
                UserRole.valueOf(rs.getString("role")),
                rs.getInt("is_active") == 1,
                lastLogin,
                LocalDateTime.parse(rs.getString("created_at").replace(" ", "T")),
                LocalDateTime.parse(rs.getString("updated_at").replace(" ", "T"))
        );
    }
}


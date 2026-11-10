package com.smartkhata.dao;

import com.smartkhata.model.entity.User;
import com.smartkhata.model.enums.UserRole;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface UserDao {
    User save(Connection conn, User user) throws SQLException;
    Optional<User> findById(Connection conn, long id) throws SQLException;
    Optional<User> findByUsername(Connection conn, String username) throws SQLException;
    Optional<User> findFirstByRole(Connection conn, UserRole role) throws SQLException;
    boolean hasAnyOwner(Connection conn) throws SQLException;
    void updateLastLogin(Connection conn, long userId) throws SQLException;
    List<User> findAllByBusiness(Connection conn, long businessId) throws SQLException;
    boolean hasLinkedTransactions(Connection conn, long userId) throws SQLException;
    int countActiveOwners(Connection conn, long businessId) throws SQLException;
    void delete(Connection conn, long userId, long businessId) throws SQLException;
}


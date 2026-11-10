package com.smartkhata.dao;

import com.smartkhata.model.entity.Category;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface CategoryDao {
    Category save(Connection conn, Category category) throws SQLException;
    Optional<Category> findById(Connection conn, long id) throws SQLException;
    Optional<Category> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException;
    List<Category> findAll(Connection conn, long businessId) throws SQLException;
    void delete(Connection conn, long categoryId, long businessId) throws SQLException;
}

package com.smartkhata.dao;

import com.smartkhata.model.entity.Business;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

public interface BusinessDao {
    Business save(Connection conn, Business business) throws SQLException;
    Optional<Business> findById(Connection conn, long id) throws SQLException;
}


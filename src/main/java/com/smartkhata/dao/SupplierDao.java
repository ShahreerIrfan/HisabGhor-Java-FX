package com.smartkhata.dao;

import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface SupplierDao {
    Supplier save(Connection conn, Supplier supplier) throws SQLException;
    Optional<Supplier> findById(Connection conn, long id) throws SQLException;
    List<Supplier> findAll(Connection conn, long businessId) throws SQLException;
    List<Supplier> search(Connection conn, long businessId, String query, boolean includeArchived) throws SQLException;
    Optional<Supplier> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException;
    boolean hasLinkedFinancialRecords(Connection conn, long supplierId) throws SQLException;
    void setArchived(Connection conn, long supplierId, long businessId, boolean archived) throws SQLException;
    void delete(Connection conn, long supplierId, long businessId) throws SQLException;
    Money getTotalOutstandingPayables(Connection conn, long businessId) throws SQLException;
}


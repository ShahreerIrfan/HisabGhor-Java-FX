package com.smartkhata.dao;

import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface CustomerDao {
    Customer save(Connection conn, Customer customer) throws SQLException;
    Optional<Customer> findById(Connection conn, long id) throws SQLException;
    List<Customer> findAll(Connection conn, long businessId) throws SQLException;
    List<Customer> search(Connection conn, long businessId, String query, boolean includeArchived) throws SQLException;
    Optional<Customer> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException;
    boolean hasLinkedFinancialRecords(Connection conn, long customerId) throws SQLException;
    void setArchived(Connection conn, long customerId, long businessId, boolean archived) throws SQLException;
    void delete(Connection conn, long customerId, long businessId) throws SQLException;
    Money getTotalOutstanding(Connection conn, long businessId) throws SQLException;
}

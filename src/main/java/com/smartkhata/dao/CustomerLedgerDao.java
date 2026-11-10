package com.smartkhata.dao;

import com.smartkhata.model.entity.CustomerLedgerEntry;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface CustomerLedgerDao {
    CustomerLedgerEntry save(Connection conn, CustomerLedgerEntry entry) throws SQLException;
    Money getCurrentBalance(Connection conn, long customerId) throws SQLException;
    List<CustomerLedgerEntry> findByCustomerId(Connection conn, long customerId) throws SQLException;
}


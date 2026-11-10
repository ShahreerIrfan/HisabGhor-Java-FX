package com.smartkhata.dao;

import com.smartkhata.model.entity.SupplierLedgerEntry;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface SupplierLedgerDao {
    SupplierLedgerEntry save(Connection conn, SupplierLedgerEntry entry) throws SQLException;
    Money getCurrentPayableBalance(Connection conn, long supplierId) throws SQLException;
    List<SupplierLedgerEntry> findBySupplierId(Connection conn, long supplierId) throws SQLException;
}


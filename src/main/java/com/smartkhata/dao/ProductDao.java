package com.smartkhata.dao;

import com.smartkhata.model.entity.Product;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface ProductDao {
    Product save(Connection conn, Product product) throws SQLException;
    Optional<Product> findById(Connection conn, long id) throws SQLException;
    List<Product> findAll(Connection conn, long businessId) throws SQLException;
    List<Product> search(Connection conn, long businessId, String query, Long categoryId, boolean includeArchived) throws SQLException;
    Optional<Product> findByNameAndBusiness(Connection conn, long businessId, String name) throws SQLException;
    Optional<Product> findByBarcodeAndBusiness(Connection conn, long businessId, String barcode) throws SQLException;
    void setArchived(Connection conn, long productId, long businessId, boolean archived) throws SQLException;
    boolean hasLinkedTransactionRecords(Connection conn, long productId) throws SQLException;
    void delete(Connection conn, long productId, long businessId) throws SQLException;
    int getCurrentStock(Connection conn, long productId) throws SQLException;
    int getLowStockCount(Connection conn, long businessId) throws SQLException;
    Money getTotalInventoryValuation(Connection conn, long businessId) throws SQLException;
    void updateCostPrice(Connection conn, long productId, Money newCostPrice) throws SQLException;
    void updateCostAndLastPurchase(Connection conn, long productId, Money newWeightedAvgCost, Money lastPurchasePrice) throws SQLException;
}

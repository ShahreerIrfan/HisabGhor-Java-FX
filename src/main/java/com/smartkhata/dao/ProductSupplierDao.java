package com.smartkhata.dao;

import com.smartkhata.model.dto.ProductSupplierViewDto;
import com.smartkhata.model.entity.ProductSupplier;
import com.smartkhata.model.vo.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProductSupplierDao {
    ProductSupplier save(Connection conn, ProductSupplier mapping) throws SQLException;
    Optional<ProductSupplier> findById(Connection conn, long id) throws SQLException;
    Optional<ProductSupplier> findByProductAndSupplier(Connection conn, long businessId, long productId, long supplierId) throws SQLException;
    List<ProductSupplierViewDto> findByProductId(Connection conn, long businessId, long productId, boolean includeInactive) throws SQLException;
    List<ProductSupplierViewDto> findBySupplierId(Connection conn, long businessId, long supplierId, boolean includeInactive) throws SQLException;
    void clearPreferredForProduct(Connection conn, long businessId, long productId, Long exceptMappingId) throws SQLException;
    void updateLastPurchase(Connection conn, long businessId, long productId, long supplierId, Money lastPrice, LocalDateTime purchaseDate) throws SQLException;
    void delete(Connection conn, long id, long businessId) throws SQLException;
}


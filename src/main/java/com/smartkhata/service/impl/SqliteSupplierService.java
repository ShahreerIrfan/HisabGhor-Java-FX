package com.smartkhata.service.impl;

import com.smartkhata.dao.ProductSupplierDao;
import com.smartkhata.dao.SqliteProductSupplierDao;
import com.smartkhata.dao.SqliteSupplierDao;
import com.smartkhata.dao.SqliteSupplierLedgerDao;
import com.smartkhata.dao.SupplierDao;
import com.smartkhata.dao.SupplierLedgerDao;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Supplier;
import com.smartkhata.model.entity.SupplierLedgerEntry;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.SessionContext;
import com.smartkhata.service.api.SupplierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteSupplierService implements SupplierService {

    private static final Logger log = LoggerFactory.getLogger(SqliteSupplierService.class);

    private final SupplierDao supplierDao;
    private final SupplierLedgerDao ledgerDao;
    private final ProductSupplierDao productSupplierDao;

    public SqliteSupplierService() {
        this(new SqliteSupplierDao(), new SqliteSupplierLedgerDao(), new SqliteProductSupplierDao());
    }

    public SqliteSupplierService(SupplierDao supplierDao, SupplierLedgerDao ledgerDao) {
        this(supplierDao, ledgerDao, new SqliteProductSupplierDao());
    }

    public SqliteSupplierService(SupplierDao supplierDao, SupplierLedgerDao ledgerDao, ProductSupplierDao productSupplierDao) {
        this.supplierDao = supplierDao;
        this.ledgerDao = ledgerDao;
        this.productSupplierDao = productSupplierDao;
    }

    @Override
    public Supplier createSupplier(SupplierCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.name() == null || dto.name().trim().isBlank()) {
            throw new IllegalArgumentException("Supplier name is required.");
        }

        Money opening = dto.openingBalance() != null ? dto.openingBalance() : Money.ZERO;

        return TransactionManager.executeInTransaction(conn -> {
            // Check for exact duplicate supplier name in the same business
            Optional<Supplier> existing = supplierDao.findByNameAndBusiness(conn, dto.businessId(), dto.name().trim());
            if (existing.isPresent()) {
                throw new IllegalArgumentException("A supplier with the name '" + dto.name().trim() + "' already exists in this store.");
            }

            Supplier supplier = new Supplier(
                    null,
                    dto.businessId(),
                    dto.name().trim(),
                    dto.contactPerson() != null ? dto.contactPerson().trim() : null,
                    dto.phone() != null ? dto.phone().trim() : null,
                    dto.address() != null ? dto.address().trim() : null,
                    opening,
                    false,
                    dto.notes(),
                    null, null,
                    dto.supplierCode(),
                    dto.email(),
                    dto.paymentTerms(),
                    dto.creditPeriodDays(),
                    dto.isActive()
            );

            Supplier saved = supplierDao.save(conn, supplier);

            // Record traceable opening payable balance into supplier ledger
            // Payables sign convention: credit increases payable (what business owes supplier)
            if (opening.isPositive()) {
                SupplierLedgerEntry openingEntry = new SupplierLedgerEntry(
                        null,
                        saved.businessId(),
                        saved.id(),
                        LocalDateTime.now(),
                        "OPENING_BALANCE",
                        null,
                        opening,     // Credit: increases supplier payable
                        Money.ZERO,  // Debit
                        opening,     // Running Balance
                        "Verified Opening Payable Balance",
                        null
                );
                ledgerDao.save(conn, openingEntry);
                log.info("Recorded initial opening payable of {} for supplier [{}]", opening, saved.name());
            }

            log.info("Supplier [{}] (ID: {}) created successfully", saved.name(), saved.id());
            return saved;
        });
    }

    @Override
    public Supplier updateSupplier(SupplierUpdateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.name() == null || dto.name().trim().isBlank()) {
            throw new IllegalArgumentException("Supplier name cannot be blank.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Supplier> opt = supplierDao.findById(conn, dto.supplierId());
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + dto.supplierId());
            }

            Supplier existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }

            // Check for duplicate name if name changed
            if (!existing.name().equalsIgnoreCase(dto.name().trim())) {
                Optional<Supplier> dup = supplierDao.findByNameAndBusiness(conn, existing.businessId(), dto.name().trim());
                if (dup.isPresent() && !dup.get().id().equals(existing.id())) {
                    throw new IllegalArgumentException("A supplier with the name '" + dto.name().trim() + "' already exists.");
                }
            }

            Supplier updated = new Supplier(
                    existing.id(),
                    existing.businessId(),
                    dto.name().trim(),
                    dto.contactPerson() != null ? dto.contactPerson().trim() : null,
                    dto.phone() != null ? dto.phone().trim() : null,
                    dto.address() != null ? dto.address().trim() : null,
                    existing.openingBalance(), // Opening balance is immutable
                    existing.isArchived(),
                    dto.notes(),
                    existing.createdAt(),
                    null,
                    dto.supplierCode() != null && !dto.supplierCode().trim().isBlank() ? dto.supplierCode().trim() : existing.supplierCode(),
                    dto.email(),
                    dto.paymentTerms(),
                    dto.creditPeriodDays(),
                    dto.isActive()
            );

            return supplierDao.save(conn, updated);
        });
    }

    @Override
    public void archiveSupplier(long supplierId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Supplier> opt = supplierDao.findById(conn, supplierId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + supplierId);
            }
            Supplier supplier = opt.get();
            if (supplier.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            supplierDao.setArchived(conn, supplierId, supplier.businessId(), true);
            log.info("Archived supplier [{}] (ID: {}). Historical records retained.", supplier.name(), supplierId);
        });
    }

    @Override
    public void restoreSupplier(long supplierId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Supplier> opt = supplierDao.findById(conn, supplierId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + supplierId);
            }
            Supplier supplier = opt.get();
            if (supplier.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            supplierDao.setArchived(conn, supplierId, supplier.businessId(), false);
            log.info("Restored archived supplier [{}] (ID: {}).", supplier.name(), supplierId);
        });
    }

    @Override
    public void deleteSupplier(long supplierId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Supplier> opt = supplierDao.findById(conn, supplierId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + supplierId);
            }
            Supplier supplier = opt.get();
            if (supplier.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            if (supplierDao.hasLinkedFinancialRecords(conn, supplierId)) {
                throw new IllegalStateException(
                        "Cannot delete supplier '" + supplier.name() + "' with purchase bills, payments, or ledger history. Please Archive it instead."
                );
            }
            supplierDao.delete(conn, supplierId, supplier.businessId());
            log.info("Deleted supplier [{}] (ID: {}).", supplier.name(), supplierId);
        });
    }

    @Override
    public List<SupplierListItemDto> searchSuppliers(long businessId, String query, boolean includeArchived) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            List<Supplier> suppliers = supplierDao.search(conn, businessId, query, includeArchived);
            List<SupplierListItemDto> dtos = new ArrayList<>(suppliers.size());

            for (Supplier s : suppliers) {
                Money payable = ledgerDao.getCurrentPayableBalance(conn, s.id());
                dtos.add(new SupplierListItemDto(
                        s.id(),
                        s.name(),
                        s.contactPerson(),
                        s.phone(),
                        s.address(),
                        payable,
                        s.isArchived(),
                        s.createdAt(),
                        s.supplierCode(),
                        s.email(),
                        s.paymentTerms(),
                        s.creditPeriodDays(),
                        s.isActive()
                ));
            }
            return dtos;
        } catch (SQLException e) {
            log.error("Failed to search suppliers: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve supplier records", e);
        }
    }

    @Override
    public Optional<Supplier> getSupplierById(long supplierId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Supplier> opt = supplierDao.findById(conn, supplierId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            return opt;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load supplier", e);
        }
    }

    @Override
    public List<SupplierLedgerEntry> getSupplierLedger(long supplierId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Supplier> opt = supplierDao.findById(conn, supplierId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            return ledgerDao.findBySupplierId(conn, supplierId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load supplier ledger", e);
        }
    }

    @Override
    public Money getTotalOutstandingPayables(long businessId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return supplierDao.getTotalOutstandingPayables(conn, businessId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to calculate total supplier payables", e);
        }
    }

    @Override
    public List<ProductSupplierViewDto> getSupplierProducts(long supplierId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Supplier> opt = supplierDao.findById(conn, supplierId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Supplier not found with id: " + supplierId);
            }
            if (opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: supplier belongs to another business.");
            }
            return productSupplierDao.findBySupplierId(conn, opt.get().businessId(), supplierId, true);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load supplier linked products", e);
        }
    }
}


package com.smartkhata.service.impl;

import com.smartkhata.dao.CustomerDao;
import com.smartkhata.dao.CustomerLedgerDao;
import com.smartkhata.dao.SqliteCustomerDao;
import com.smartkhata.dao.SqliteCustomerLedgerDao;
import com.smartkhata.database.DatabaseException;
import com.smartkhata.database.DatabaseManager;
import com.smartkhata.database.TransactionManager;
import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.CustomerLedgerEntry;
import com.smartkhata.model.vo.Money;
import com.smartkhata.service.api.CustomerService;
import com.smartkhata.service.api.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteCustomerService implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(SqliteCustomerService.class);

    private final CustomerDao customerDao;
    private final CustomerLedgerDao ledgerDao;

    public SqliteCustomerService() {
        this(new SqliteCustomerDao(), new SqliteCustomerLedgerDao());
    }

    public SqliteCustomerService(CustomerDao customerDao, CustomerLedgerDao ledgerDao) {
        this.customerDao = customerDao;
        this.ledgerDao = ledgerDao;
    }

    @Override
    public Customer createCustomer(CustomerCreateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.businessId() != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match request business.");
        }
        if (dto.name() == null || dto.name().trim().isBlank()) {
            throw new IllegalArgumentException("Customer name is required.");
        }

        Money limit = dto.creditLimit() != null ? dto.creditLimit() : Money.ZERO;
        Money opening = dto.openingBalance() != null ? dto.openingBalance() : Money.ZERO;

        return TransactionManager.executeInTransaction(conn -> {
            // Check for exact duplicate customer name in the same business
            Optional<Customer> existing = customerDao.findByNameAndBusiness(conn, dto.businessId(), dto.name().trim());
            if (existing.isPresent()) {
                throw new IllegalArgumentException("A customer with the name '" + dto.name().trim() + "' already exists in this store.");
            }

            Customer customer = new Customer(
                    null,
                    dto.businessId(),
                    dto.name().trim(),
                    dto.phone() != null ? dto.phone().trim() : null,
                    dto.address() != null ? dto.address().trim() : null,
                    limit,
                    opening,
                    false,
                    dto.notes(),
                    null, null
            );

            Customer saved = customerDao.save(conn, customer);

            // Record traceable opening receivable balance into customer ledger
            if (opening.isPositive()) {
                CustomerLedgerEntry openingEntry = new CustomerLedgerEntry(
                        null,
                        saved.businessId(),
                        saved.id(),
                        LocalDateTime.now(),
                        "OPENING_BALANCE",
                        null,
                        opening,     // Debit: customer owes business
                        Money.ZERO,  // Credit
                        opening,     // Running Balance
                        "Verified Opening Receivable Balance",
                        null
                );
                ledgerDao.save(conn, openingEntry);
                log.info("Recorded initial opening balance of {} for customer [{}]", opening, saved.name());
            }

            log.info("Customer [{}] (ID: {}) created successfully", saved.name(), saved.id());
            return saved;
        });
    }

    @Override
    public Customer updateCustomer(CustomerUpdateDto dto) {
        SessionContext.requireAuthenticated();
        if (dto.name() == null || dto.name().trim().isBlank()) {
            throw new IllegalArgumentException("Customer name cannot be blank.");
        }

        return TransactionManager.executeInTransaction(conn -> {
            Optional<Customer> opt = customerDao.findById(conn, dto.customerId());
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Customer not found with id: " + dto.customerId());
            }

            Customer existing = opt.get();
            if (existing.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: customer belongs to another business.");
            }

            // Check for duplicate name if name changed
            if (!existing.name().equalsIgnoreCase(dto.name().trim())) {
                Optional<Customer> dup = customerDao.findByNameAndBusiness(conn, existing.businessId(), dto.name().trim());
                if (dup.isPresent() && !dup.get().id().equals(existing.id())) {
                    throw new IllegalArgumentException("A customer with the name '" + dto.name().trim() + "' already exists.");
                }
            }

            Customer updated = new Customer(
                    existing.id(),
                    existing.businessId(),
                    dto.name().trim(),
                    dto.phone() != null ? dto.phone().trim() : null,
                    dto.address() != null ? dto.address().trim() : null,
                    dto.creditLimit() != null ? dto.creditLimit() : existing.creditLimit(),
                    existing.openingBalance(),
                    existing.isArchived(),
                    dto.notes(),
                    existing.createdAt(),
                    null
            );

            return customerDao.save(conn, updated);
        });
    }

    @Override
    public void archiveCustomer(long customerId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Customer> opt = customerDao.findById(conn, customerId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Customer not found with id: " + customerId);
            }
            Customer customer = opt.get();
            if (customer.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: customer belongs to another business.");
            }
            customerDao.setArchived(conn, customerId, customer.businessId(), true);
            log.info("Archived customer [{}] (ID: {}). Historical records retained.", customer.name(), customerId);
        });
    }

    @Override
    public void restoreCustomer(long customerId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Customer> opt = customerDao.findById(conn, customerId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Customer not found with id: " + customerId);
            }
            Customer customer = opt.get();
            if (customer.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: customer belongs to another business.");
            }
            customerDao.setArchived(conn, customerId, customer.businessId(), false);
            log.info("Restored archived customer [{}] (ID: {}).", customer.name(), customerId);
        });
    }

    @Override
    public void deleteCustomer(long customerId) {
        SessionContext.requireOwner();
        TransactionManager.runInTransaction(conn -> {
            Optional<Customer> opt = customerDao.findById(conn, customerId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("Customer not found with id: " + customerId);
            }
            Customer customer = opt.get();
            if (customer.businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: customer belongs to another business.");
            }
            if (customerDao.hasLinkedFinancialRecords(conn, customerId)) {
                throw new IllegalStateException(
                        "Cannot delete customer '" + customer.name() + "' with sales, payments, or ledger history. Please Archive it instead."
                );
            }
            customerDao.delete(conn, customerId, customer.businessId());
            log.info("Deleted customer [{}] (ID: {}).", customer.name(), customerId);
        });
    }

    @Override
    public List<CustomerListItemDto> searchCustomers(long businessId, String query, boolean includeArchived) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            List<Customer> customers = customerDao.search(conn, businessId, query, includeArchived);
            List<CustomerListItemDto> dtos = new ArrayList<>(customers.size());

            for (Customer c : customers) {
                Money due = ledgerDao.getCurrentBalance(conn, c.id());
                dtos.add(new CustomerListItemDto(
                        c.id(),
                        c.name(),
                        c.phone(),
                        c.address(),
                        c.creditLimit(),
                        due,
                        c.isArchived(),
                        c.createdAt()
                ));
            }
            return dtos;
        } catch (SQLException e) {
            log.error("Failed to search customers: {}", e.getMessage(), e);
            throw new DatabaseException("Failed to retrieve customer records", e);
        }
    }

    @Override
    public Optional<Customer> getCustomerById(long customerId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Customer> opt = customerDao.findById(conn, customerId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: customer belongs to another business.");
            }
            return opt;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load customer", e);
        }
    }

    @Override
    public List<CustomerLedgerEntry> getCustomerLedger(long customerId) {
        SessionContext.requireAuthenticated();
        try (Connection conn = DatabaseManager.getConnection()) {
            Optional<Customer> opt = customerDao.findById(conn, customerId);
            if (opt.isPresent() && opt.get().businessId() != SessionContext.getRequiredBusinessId()) {
                throw new SecurityException("Cross-business access denied: customer belongs to another business.");
            }
            return ledgerDao.findByCustomerId(conn, customerId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load customer ledger", e);
        }
    }

    @Override
    public Money getTotalOutstandingDues(long businessId) {
        SessionContext.requireAuthenticated();
        if (businessId != SessionContext.getRequiredBusinessId()) {
            throw new SecurityException("Cross-business access denied: session business does not match queried business.");
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            return customerDao.getTotalOutstanding(conn, businessId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to calculate total customer dues", e);
        }
    }
}

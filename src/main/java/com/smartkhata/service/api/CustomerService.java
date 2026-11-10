package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;
import com.smartkhata.model.entity.Customer;
import com.smartkhata.model.entity.CustomerLedgerEntry;
import com.smartkhata.model.vo.Money;

import java.util.List;
import java.util.Optional;

public interface CustomerService {

    Customer createCustomer(CustomerCreateDto dto);

    Customer updateCustomer(CustomerUpdateDto dto);

    void archiveCustomer(long customerId);

    void restoreCustomer(long customerId);

    void deleteCustomer(long customerId);

    List<CustomerListItemDto> searchCustomers(long businessId, String query, boolean includeArchived);

    Optional<Customer> getCustomerById(long customerId);

    List<CustomerLedgerEntry> getCustomerLedger(long customerId);

    Money getTotalOutstandingDues(long businessId);
}


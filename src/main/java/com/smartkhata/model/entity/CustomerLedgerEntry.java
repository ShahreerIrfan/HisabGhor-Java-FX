package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record CustomerLedgerEntry(
        Long id,
        long businessId,
        long customerId,
        LocalDateTime entryDate,
        String entryType, // OPENING_BALANCE, SALE_INVOICE, PAYMENT_COLLECTION, INVOICE_REVERSAL, ADJUSTMENT
        Long referenceId,
        Money debit,  // increases customer due
        Money credit, // decreases customer due
        Money runningBalance,
        String notes,
        LocalDateTime createdAt
) {
    public CustomerLedgerEntry withId(Long newId) {
        return new CustomerLedgerEntry(newId, businessId, customerId, entryDate, entryType,
                referenceId, debit, credit, runningBalance, notes, createdAt);
    }
}


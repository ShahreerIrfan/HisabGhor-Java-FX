package com.smartkhata.model.entity;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record SupplierLedgerEntry(
        Long id,
        long businessId,
        long supplierId,
        LocalDateTime entryDate,
        String entryType, // OPENING_BALANCE, PURCHASE_BILL, PAYMENT_DISBURSEMENT, BILL_REVERSAL, ADJUSTMENT
        Long referenceId,
        Money credit, // increases supplier payable (business owes supplier more)
        Money debit,  // decreases supplier payable (business disbursed payment to supplier)
        Money runningBalance,
        String notes,
        LocalDateTime createdAt
) {
    public SupplierLedgerEntry withId(Long newId) {
        return new SupplierLedgerEntry(newId, businessId, supplierId, entryDate, entryType,
                referenceId, credit, debit, runningBalance, notes, createdAt);
    }
}


package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

/**
 * Operational cash flow summary for expenses and auxiliary other income.
 */
public record CashFlowSummaryDto(
        Money totalExpenses,
        Money totalOtherIncome,
        Money netAuxiliaryFlow,
        int activeExpenseCount,
        int activeIncomeCount
) {
}


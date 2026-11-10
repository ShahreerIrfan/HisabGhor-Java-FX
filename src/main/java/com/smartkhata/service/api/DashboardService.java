package com.smartkhata.service.api;

import com.smartkhata.model.dto.*;

import java.time.LocalDate;
import java.util.List;

public interface DashboardService {

    enum TrendMetric {
        SALES, COLLECTION, EXPENSES
    }

    DashboardKPIs getKPIs(long businessId, LocalDate startDate, LocalDate endDate);

    List<SalesTrendPoint> getTrend(long businessId, LocalDate startDate, LocalDate endDate, TrendMetric metric);

    TrendSummaryMetrics getTrendSummaryMetrics(long businessId, LocalDate startDate, LocalDate endDate, TrendMetric metric);

    List<RecentTransactionDto> getRecentTransactions(long businessId, int limit);

    List<LowStockItemDto> getLowStockAlerts(long businessId, int limit);

    OutstandingDuesSummary getOutstandingDues(long businessId);

    List<SearchResultItemDto> globalSearch(long businessId, String query, int limit);
}

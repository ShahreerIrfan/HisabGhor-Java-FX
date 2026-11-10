package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

/**
 * Summary metrics shown alongside the trend chart for the selected period
 * (Total volume, daily average, and voucher/transaction count).
 */
public record TrendSummaryMetrics(
        Money totalAmount,
        Money avgDailyAmount,
        int count,
        String countLabel
) {
    public static TrendSummaryMetrics empty(String countLabel) {
        return new TrendSummaryMetrics(Money.ZERO, Money.ZERO, 0, countLabel);
    }
}


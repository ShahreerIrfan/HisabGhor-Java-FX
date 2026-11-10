package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;
import java.time.LocalDateTime;

public record RecentTransactionDto(
        LocalDateTime timestamp,
        String formattedDate,
        String partyName,
        String type,       // Sale, Payment (Collection), Expense
        Money amount,
        String status      // Paid, Received, Baki
) {}


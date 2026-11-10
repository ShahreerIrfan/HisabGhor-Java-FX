package com.smartkhata.model.dto;

import com.smartkhata.model.vo.Money;

public record SalesTrendPoint(
        String dateLabel,
        Money amount
) {}


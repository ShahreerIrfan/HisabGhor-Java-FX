package com.smartkhata.model.dto;

public record BusinessProfileUpdateDto(
        String name,
        String legalName,
        String phone,
        String address,
        String tradeLicense
) {}


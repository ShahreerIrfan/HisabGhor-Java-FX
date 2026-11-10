package com.smartkhata.model.dto;

public record OnboardingRequestDto(
        String businessName,
        String ownerFullName,
        String username,
        String phone,
        String address,
        String currencyCode,
        String language,
        char[] passwordOrPin
) {}


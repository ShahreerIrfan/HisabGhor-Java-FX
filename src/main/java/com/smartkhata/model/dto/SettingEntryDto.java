package com.smartkhata.model.dto;

import java.time.LocalDateTime;

public record SettingEntryDto(
        String key,
        String value,
        LocalDateTime updatedAt
) {}

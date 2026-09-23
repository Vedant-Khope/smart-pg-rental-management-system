package com.smartpg.module.property.dto.request;

import jakarta.validation.constraints.NotNull;

public record ReviewPropertyRequest(
    @NotNull(message = "Approval decision is required") Boolean isApproved,
    String reason
) {}

package com.smartpg.module.booking.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record MoveInRequest(
        @NotNull(message = "Booking ID is required")
        UUID bookingId,

        @NotNull(message = "Monthly rent is required")
        @Positive(message = "Monthly rent must be strictly positive")
        BigDecimal monthlyRent,

        @NotNull(message = "Security deposit is required")
        @Positive(message = "Security deposit must be strictly positive")
        BigDecimal securityDeposit
) {}

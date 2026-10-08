package com.smartpg.module.booking.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTO for a Tenant applying for a bed.
 */
public record CreateBookingRequest(
        @NotNull(message = "Bed ID is required")
        UUID bedId,

        @NotNull(message = "Expected move-in date is required")
        @FutureOrPresent(message = "Move-in date cannot be in the past")
        LocalDate expectedMoveInDate
) {}

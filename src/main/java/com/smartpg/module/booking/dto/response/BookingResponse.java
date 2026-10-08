package com.smartpg.module.booking.dto.response;

import com.smartpg.module.booking.enums.BookingStatus;
import com.smartpg.module.booking.model.Booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for Booking.
 */
public record BookingResponse(
        UUID id,
        UUID tenantId,
        UUID bedId,
        BookingStatus status,
        LocalDate expectedMoveInDate,
        Instant createdAt
) {
    public static BookingResponse fromEntity(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getTenant().getId(),
                booking.getBed().getId(),
                booking.getStatus(),
                booking.getExpectedMoveInDate(),
                booking.getCreatedAt()
        );
    }
}

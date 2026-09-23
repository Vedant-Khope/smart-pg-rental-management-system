package com.smartpg.module.room.dto.request;

import jakarta.validation.constraints.*;

/**
 * Request DTO for adding a new Bed inside a Room.
 *
 * <p><b>Intentionally simple</b> — a Bed only has a label and optional notes.
 * All other fields (status, timestamps) are system-managed, not owner-provided.
 * This keeps the API surface minimal and prevents owners from directly setting
 * things like status=OCCUPIED (which only happens through the booking flow).
 */
public record CreateBedRequest(

        /**
         * Human-readable label for this bed within the room.
         * Examples: "Bed A", "Bed B", "Lower Bunk", "Window Side".
         * Must be unique within the room (validated in service layer, not here).
         * Max 10 chars — "Bed A" is 5 chars, "Window Side" is 11 (just outside, validated here).
         */
        @NotBlank(message = "Bed label is required")
        @Size(max = 10, message = "Bed label must not exceed 10 characters")
        String bedLabel,

        /**
         * Optional owner notes about this specific bed.
         * E.g., "Upper bunk. Ladder on left side.", "Near window. Gets morning sunlight."
         */
        @Size(max = 500, message = "Notes must not exceed 500 characters")
        String notes

) {}

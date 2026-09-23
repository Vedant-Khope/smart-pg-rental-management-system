package com.smartpg.module.room.dto.request;

import com.smartpg.module.room.enums.RoomType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Request DTO for creating a new Room inside a Property.
 *
 * <p><b>Why a record instead of a class?</b>
 * Java 16+ Records are immutable by design — perfect for DTOs because:
 * <ul>
 *   <li>No accidental mutation (no setters)</li>
 *   <li>Auto-generated constructor, getters, equals, hashCode, toString</li>
 *   <li>Much less boilerplate than a class with Lombok</li>
 * </ul>
 *
 * <p><b>Why separate DTOs from Entities?</b>
 * Never expose your JPA Entity directly in an API. If you return a Room entity from
 * a controller, Jackson might serialize the entire object graph — including lazy-loaded
 * beds, the parent property, the owner's password hash... security nightmare!
 * DTOs give you full control over what goes in and what comes out of your API.
 *
 * <p>This record is validated by Bean Validation (@Valid in the controller).
 * If any constraint fails, Spring returns a 400 Bad Request automatically.
 */
public record CreateRoomRequest(

        /**
         * Human-readable room identifier within the property.
         * Owner sets this: "101", "A2", "Terrace Room".
         * Must be 1–20 chars. Must not be blank.
         */
        @NotBlank(message = "Room number is required")
        @Size(max = 20, message = "Room number must not exceed 20 characters")
        String roomNumber,

        /**
         * Floor number. 0 = Ground floor. Negative = basement.
         * Cannot be null — a room must have a floor.
         */
        @NotNull(message = "Floor number is required")
        @Min(value = -5, message = "Floor number cannot be less than -5")
        @Max(value = 100, message = "Floor number cannot exceed 100")
        Integer floorNumber,

        /**
         * Sharing type: SINGLE, DOUBLE, TRIPLE, or QUAD.
         * Cannot be null — drives capacity validation in the service layer.
         */
        @NotNull(message = "Room type is required")
        RoomType roomType,

        /**
         * Maximum beds allowed in this room.
         * Must align with roomType (validated in service, not here — contextual validation).
         * Min 1, max 20 (extreme case: large dormitory).
         */
        @NotNull(message = "Capacity is required")
        @Min(value = 1, message = "Capacity must be at least 1")
        @Max(value = 20, message = "Capacity cannot exceed 20 beds")
        Integer capacity,

        /**
         * Monthly rent per bed in INR.
         * BigDecimal for precise monetary calculations.
         * Min ₹100 (sanity check). Max ₹9,99,999 (realistic upper bound).
         */
        @NotNull(message = "Monthly rent is required")
        @DecimalMin(value = "100.00", message = "Monthly rent must be at least ₹100")
        @DecimalMax(value = "999999.99", message = "Monthly rent cannot exceed ₹9,99,999.99")
        BigDecimal monthlyRent,

        /**
         * Optional description of this room.
         * Max 2000 chars — prevents storing novels in the database.
         */
        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        /** Whether this room has an attached bathroom. Defaults to false in entity. */
        Boolean hasAttachedBathroom,

        /** Whether this room has a balcony. Defaults to false in entity. */
        Boolean hasBalcony,

        /** Whether this room has AC. Defaults to false in entity. */
        Boolean hasAc

) {}

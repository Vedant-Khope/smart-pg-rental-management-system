package com.smartpg.module.room.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Lifecycle state of a single Bed within a Room.
 *
 * <p><b>Bed vs Room status — why two levels?</b>
 * A Room can have 3 beds. Bed A is OCCUPIED, Bed B is AVAILABLE, Bed C is RESERVED.
 * The Room status is AVAILABLE (because at least one bed is free), but individual beds
 * have their own states. This two-level status system lets:
 * <ul>
 *   <li>Tenants see exactly which beds are free in a room</li>
 *   <li>The booking system lock a specific bed during a booking flow (RESERVED state)</li>
 *   <li>Owners flag specific beds for maintenance without taking the whole room down</li>
 * </ul>
 *
 * <p><b>State Machine:</b>
 * <pre>
 *   [Owner creates bed]
 *       AVAILABLE
 *           |
 *   [Tenant starts booking] → RESERVED (temporary hold, e.g., 15 min)
 *           |                      |
 *           |              [Booking confirmed] OR [Booking cancelled/expired]
 *           |                      ↓                         ↓
 *           |                 OCCUPIED                   AVAILABLE
 *           |                      |
 *           |              [Tenant vacates]
 *           |                      |
 *           |←←←←←←←←←←←←← AVAILABLE
 *           |
 *   [Owner takes offline]
 *           |
 *       UNDER_MAINTENANCE
 * </pre>
 *
 * <p><b>DB Storage</b>: {@code EnumType.STRING}
 */
@Getter
@RequiredArgsConstructor
public enum BedStatus {

    /**
     * Bed is vacant and open for booking.
     * Tenants CAN select this bed and initiate a booking.
     * Default status when a bed is first added by the owner.
     */
    AVAILABLE("Available"),

    /**
     * Bed is temporarily held for a tenant during the booking process.
     * Duration: configurable (default 15 minutes). After timeout, auto-reverts to AVAILABLE.
     * This prevents race conditions: two tenants can't simultaneously book the same bed.
     * Example: Tenant clicks "Book This Bed" → system marks RESERVED, starts payment flow.
     *          If payment completes → OCCUPIED. If abandoned/timeout → AVAILABLE.
     */
    RESERVED("Reserved"),

    /**
     * Bed is currently occupied by a tenant under an active tenancy agreement.
     * Cannot be booked by anyone else.
     * Transitions to AVAILABLE when the tenancy ends (tenant moves out, lease expires).
     */
    OCCUPIED("Occupied"),

    /**
     * Bed is taken offline for maintenance/repair by the owner.
     * Not bookable. Not displayed in search results.
     * Example: Mattress replacement, broken furniture, bedbug treatment.
     * Owner must manually set back to AVAILABLE when maintenance is complete.
     */
    UNDER_MAINTENANCE("Under Maintenance");

    /** Human-readable label for UI display. */
    private final String displayName;
}

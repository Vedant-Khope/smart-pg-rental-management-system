package com.smartpg.module.room.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Lifecycle state of a Room.
 *
 * <p><b>State Machine:</b>
 * <pre>
 *   [Owner creates room]
 *       AVAILABLE
 *           |
 *   [All beds get occupied]
 *           |
 *       FULLY_OCCUPIED   ←→  (a tenant vacates → back to AVAILABLE)
 *           |
 *   [Owner takes room offline]
 *           |
 *       UNDER_MAINTENANCE  (cleaning, renovation — beds not bookable)
 *           |
 *   [Owner soft-deletes]
 *           |
 *       INACTIVE (soft-delete — never hard delete)
 * </pre>
 *
 * <p><b>Why not just count empty beds?</b>
 * UNDER_MAINTENANCE is a business state, not just a capacity state. Even if there
 * are 2 empty beds in a room, if the room is under maintenance, tenants shouldn't
 * be able to book it. We need an explicit status column for this.
 *
 * <p><b>DB Storage</b>: {@code EnumType.STRING}
 */
@Getter
@RequiredArgsConstructor
public enum RoomStatus {

    /**
     * Room is active and has at least one vacant bed.
     * Tenants CAN view and request booking for beds in this room.
     * This is the default status when a room is first created.
     */
    AVAILABLE("Available"),

    /**
     * All beds in this room are currently occupied.
     * System transitions to this state automatically when the last vacant bed is booked.
     * Tenants CANNOT book in this room — no available beds.
     * System transitions back to AVAILABLE when any tenant vacates.
     */
    FULLY_OCCUPIED("Fully Occupied"),

    /**
     * Room is temporarily offline for maintenance (cleaning, repair, renovation).
     * Owner sets this manually. Beds cannot be booked regardless of vacancy.
     * Existing tenants are not affected — this is for NEW bookings only.
     * Example: Deep cleaning after a tenant leaves, painting walls, fixing AC.
     */
    UNDER_MAINTENANCE("Under Maintenance"),

    /**
     * Room has been soft-deleted by the owner.
     * Invisible to tenants. Existing tenancies (if any) must be resolved first.
     * Never hard-deleted — preserves historical booking and payment records.
     */
    INACTIVE("Inactive");

    /** Human-readable label for UI display. */
    private final String displayName;
}

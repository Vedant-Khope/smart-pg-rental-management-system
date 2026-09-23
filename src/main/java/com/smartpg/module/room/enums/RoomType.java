package com.smartpg.module.room.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Classifies the physical layout and sharing arrangement of a room.
 *
 * <p><b>Why does this matter?</b>
 * A tenant searching for a PG on our platform has VERY different budgets and
 * needs depending on whether they want to share a room with 5 strangers or
 * have it all to themselves. This enum drives:
 * <ul>
 *   <li>Tenant search filters ("Show me only SINGLE rooms")</li>
 *   <li>Capacity validation (a SINGLE room can only have 1 bed)</li>
 *   <li>Pricing tiers in reporting (average rent by room type)</li>
 * </ul>
 *
 * <p><b>DB Storage</b>: {@code EnumType.STRING} — stored as "SINGLE", "DOUBLE", etc.
 * This makes the DB human-readable and safe for future enum additions.
 * If we used ORDINAL (int), adding a new type in the middle would corrupt all existing rows.
 */
@Getter
@RequiredArgsConstructor
public enum RoomType {

    /**
     * A room occupied by exactly ONE tenant.
     * Max capacity: 1 bed.
     * Highest rent tier. Common for executives, working professionals who value privacy.
     * Business rule: A SINGLE room can only ever have 1 bed.
     */
    SINGLE("Single Occupancy"),

    /**
     * A room shared by TWO tenants.
     * Max capacity: 2 beds.
     * Mid-range rent. Popular among students who split costs but still want space.
     */
    DOUBLE("Double Sharing"),

    /**
     * A room shared by THREE tenants.
     * Max capacity: 3 beds.
     * Budget-friendly. Common in student PGs near colleges.
     */
    TRIPLE("Triple Sharing"),

    /**
     * A room shared by FOUR or more tenants.
     * Max capacity: defined by owner (typically 4–8 beds).
     * Most economical. Common in large hostels and dormitory-style PGs.
     */
    QUAD("Quad Sharing");

    /** Human-readable label for UI display and API responses. */
    private final String displayName;
}

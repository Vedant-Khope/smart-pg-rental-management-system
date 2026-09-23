package com.smartpg.module.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents a specific amenity or facility offered by a property.
 *
 * <p><b>Design Decision - Enum vs. Free Text:</b>
 * We use a controlled enum rather than free-text because:
 * <ul>
 *   <li><b>Filterable</b>: Tenants can search "show me PGs with WIFI and AC".
 *       Free text ("Wi-Fi", "wifi", "Wifi", "WiFi 100mbps") breaks filtering.</li>
 *   <li><b>Displayable</b>: Each amenity has a UI-friendly label and an icon hint
 *       for the frontend (icon field).</li>
 *   <li><b>Internationalizable</b>: displayName can be localized later without
 *       changing stored values in DB.</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code property_amenities.amenity_type} column
 */
@Getter
@RequiredArgsConstructor
public enum AmenityType {

    // -----------------------------------------------------------------------
    // Connectivity
    // -----------------------------------------------------------------------

    /** High-speed internet. Most searched amenity by working professionals. */
    WIFI("Wi-Fi", "wifi"),

    // -----------------------------------------------------------------------
    // Food & Kitchen
    // -----------------------------------------------------------------------

    /** Owner-provided meals (breakfast, lunch, dinner) included in rent. */
    MEALS_PROVIDED("Meals Provided", "utensils"),

    /** Shared kitchen available for tenants to cook. */
    KITCHEN("Kitchen Access", "cooking-pot"),

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Air conditioning in rooms or common areas. */
    AC("Air Conditioning", "snowflake"),

    /** 24-hour power backup / inverter / generator. */
    POWER_BACKUP("Power Backup", "zap"),

    /** Hot water supply (geyser / solar heater). */
    HOT_WATER("Hot Water", "droplets"),

    // -----------------------------------------------------------------------
    // Convenience
    // -----------------------------------------------------------------------

    /** In-house laundry service or washing machine available. */
    LAUNDRY("Laundry", "shirt"),

    /** Covered or open parking for 2-wheelers or 4-wheelers. */
    PARKING("Parking", "car"),

    /** 24/7 security guard or CCTV cameras on premises. */
    SECURITY("Security", "shield"),

    // -----------------------------------------------------------------------
    // Recreation & Fitness
    // -----------------------------------------------------------------------

    /** Gym or fitness center available on premises or nearby. */
    GYM("Gym", "dumbbell"),

    // -----------------------------------------------------------------------
    // Pet Policy
    // -----------------------------------------------------------------------

    /**
     * Pets are allowed on the premises.
     * Important differentiator - many PGs are strictly no-pets.
     */
    PET_FRIENDLY("Pet Friendly", "paw-print"),

    // -----------------------------------------------------------------------
    // Miscellaneous
    // -----------------------------------------------------------------------

    /** Housekeeping or cleaning service included. */
    HOUSEKEEPING("Housekeeping", "broom"),

    /** Nearby to a public bus stop, metro, or auto stand. */
    NEAR_PUBLIC_TRANSPORT("Near Public Transport", "bus"),

    /** Furnished rooms - beds, tables, chairs, cupboards provided. */
    FURNISHED("Furnished", "sofa");

    /** Human-readable label for UI chips and tooltips. */
    private final String displayName;

    /**
     * Icon identifier for the frontend icon library (e.g., Lucide React).
     * The React frontend uses this to auto-render the correct icon per amenity.
     */
    private final String iconKey;
}

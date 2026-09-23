package com.smartpg.module.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Classifies the physical type of a property listing on the Smart PG platform.
 *
 * <p><b>Why an enum instead of a free-text String?</b>
 * <ul>
 *   <li>Prevents garbage data ("pg", "PG ", "PG room") from all meaning the same thing.</li>
 *   <li>Enables type-safe filtering: {@code findAllByType(PropertyType.PG)} is compile-safe.</li>
 *   <li>Stored as STRING in DB - adding new type doesn't shift ordinal indices.</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code properties.type} column
 */
@Getter
@RequiredArgsConstructor
public enum PropertyType {

    /**
     * Paying Guest accommodation - the core product.
     * Typically: shared rooms, common facilities (mess, laundry), monthly rent.
     * Owner rents out individual beds or rooms within the same building.
     * Example: "Sharma Ji's PG for Boys" in Koramangala, Bengaluru.
     */
    PG("Paying Guest"),

    /**
     * A complete flat or apartment unit.
     * Rented out as a whole - not bed-by-bed.
     * Example: 2BHK flat in Andheri rented to a group of working professionals.
     */
    FLAT("Flat / Apartment"),

    /**
     * Hostel-style accommodation - usually larger building, dormitory-style beds.
     * Common in college towns. May or may not include meals.
     * Example: Private hostel near Pune University.
     */
    HOSTEL("Hostel"),

    /**
     * Studio apartment - a single-room unit with a combined living + sleeping space.
     * Rented to a single person; no shared spaces with others.
     * Example: Studio flat near an IT park in Hyderabad.
     */
    STUDIO("Studio Apartment");

    /** Human-readable label for UI display and API responses. */
    private final String displayName;
}

package com.smartpg.module.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Specifies the gender restriction policy of a property.
 *
 * <p>This is a business-critical field for PG accommodations in India.
 * Many PGs are gender-restricted for safety, cultural, or regulatory reasons.
 * Tenants MUST be able to filter by this to avoid wasted applications.
 *
 * <p><b>Stored as EnumType.STRING</b>. Plain "ANY", "MALE_ONLY", "FEMALE_ONLY"
 * in the DB - readable without a lookup table.
 *
 * <p><b>Table</b>: {@code properties.gender_preference} column
 */
@Getter
@RequiredArgsConstructor
public enum GenderPreference {

    /**
     * No gender restriction. Both male and female tenants are welcome.
     * Typical for: co-living spaces, studio flats, family-style PGs.
     */
    ANY("Open to All"),

    /**
     * Males only. Tenants with female gender will see this listing as
     * unavailable and should be blocked from applying at the service layer.
     * Typical for: boys' hostels, bachelor PGs near engineering colleges.
     */
    MALE_ONLY("Male Only"),

    /**
     * Females only. Critical for safety and privacy.
     * Typical for: girls' PGs near women's colleges, working women's hostels.
     * Often comes with additional security features (guarded entry, curfew times).
     */
    FEMALE_ONLY("Female Only");

    /** Human-readable label for UI badges and filters. */
    private final String displayName;
}

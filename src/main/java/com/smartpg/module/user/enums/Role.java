package com.smartpg.module.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines every possible role a user can hold in the Smart PG system.
 *
 * <p>WHY ENUM? Prevents invalid role values at compile time.
 * "OWNERR", "owner", "Owner" are impossible — only these 5 constants exist.
 *
 * <p>SPRING SECURITY NOTE:
 * When building GrantedAuthority for Spring Security, we prefix with "ROLE_":
 *   new SimpleGrantedAuthority("ROLE_" + role.name())
 * This allows @PreAuthorize("hasRole('OWNER')") to work correctly.
 *
 * <p>DATABASE NOTE:
 * When stored in DB via @Enumerated(EnumType.STRING), it stores as:
 *   "SUPER_ADMIN", "ADMIN", "OWNER", "CARETAKER", "TENANT"
 * Never use EnumType.ORDINAL — adding a new role would shift all indices.
 */
@Getter
@RequiredArgsConstructor
public enum Role {

    /**
     * Platform operator / business owner.
     * Has unrestricted access to everything including system config,
     * billing, feature flags, and admin account management.
     * There should be very few (1–2) SUPER_ADMINs in the system.
     */
    SUPER_ADMIN("Super Administrator"),

    /**
     * Internal operations team member.
     * Can approve/reject property listings, resolve disputes,
     * suspend accounts. Cannot change roles or touch system config.
     */
    ADMIN("Administrator"),

    /**
     * Property owner / PG landlord.
     * The primary paying customer of the platform.
     * Has full control over their own properties, rooms, bookings,
     * and rent ledger. Completely isolated from other Owners' data.
     */
    OWNER("Property Owner"),

    /**
     * On-site staff assigned by an Owner to manage one specific property.
     * Can update complaint statuses and view limited tenant info.
     * Zero access to financial data — by design.
     */
    CARETAKER("Caretaker"),

    /**
     * Person renting a room / bed in a PG.
     * Can view their own booking, pay rent, raise complaints.
     * Cannot see any other tenant's data.
     */
    TENANT("Tenant");

    // Human-readable label — useful in emails, logs, and API responses
    private final String description;
}

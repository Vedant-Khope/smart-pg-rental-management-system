package com.smartpg.module.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents the full lifecycle state of a property listing.
 *
 * <p><b>State Machine:</b>
 * <pre>
 *   [Owner submits]
 *       PENDING_APPROVAL
 *           |
 *      Admin reviews
 *         /      \
 *    ACTIVE     REJECTED  (owner can resubmit -> back to PENDING_APPROVAL)
 *       |
 *   Owner deactivates
 *       |
 *    INACTIVE  (owner can reactivate -> ACTIVE)
 *       |
 *   Owner deletes
 *       |
 *    DELETED (soft delete - never hard delete)
 * </pre>
 *
 * <p><b>DB Storage</b>: EnumType.STRING. Adding SUSPENDED state later won't break existing rows.
 * <p><b>Table</b>: {@code properties.status} column
 */
@Getter
@RequiredArgsConstructor
public enum PropertyStatus {

    /**
     * Property submitted by owner, awaiting admin review.
     * Not visible to tenants in search results yet.
     * This is the ONLY status a property can have when first created.
     */
    PENDING_APPROVAL("Pending Approval"),

    /**
     * Admin approved the listing. Property is live and searchable by tenants.
     * This is the only status from which bookings can be made.
     */
    ACTIVE("Active"),

    /**
     * Owner has temporarily hidden the property from search results.
     * All existing tenancies remain active - this doesn't evict anyone.
     * Owner can reactivate to ACTIVE at any time.
     * Example: Owner is renovating for 2 months, doesn't want new inquiries.
     */
    INACTIVE("Inactive"),

    /**
     * Admin rejected the listing with a written reason.
     * Property is not visible to tenants.
     * Owner can edit and resubmit -> goes back to PENDING_APPROVAL.
     * Example: Admin found the address was fake or photos were misrepresented.
     */
    REJECTED("Rejected"),

    /**
     * Soft-deleted by Owner or Admin. Never hard-deleted.
     * Preserves tenancy history, payment records, and complaint trails.
     * A deleted property CANNOT be reactivated - owner must create a new listing.
     */
    DELETED("Deleted");

    /** Human-readable label for UI display. */
    private final String displayName;
}

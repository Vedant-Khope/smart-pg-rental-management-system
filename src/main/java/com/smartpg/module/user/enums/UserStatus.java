package com.smartpg.module.user.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents every possible state a user account can be in.
 *
 * <p>WHY NOT A BOOLEAN (isActive)?
 * A boolean can only tell you ACTIVE or NOT ACTIVE.
 * It cannot distinguish between SUSPENDED vs DELETED vs PENDING_VERIFICATION.
 * These states require different UI messages, different allowed actions,
 * and different admin workflows — a boolean is not enough.
 *
 * <p>SOFT DELETE STRATEGY:
 * Accounts are NEVER hard deleted from the database.
 * Setting status to DELETED preserves audit trail, payment history,
 * complaint records, and referential integrity across all related tables.
 *
 * <p>DATABASE NOTE:
 * Stored as a String via @Enumerated(EnumType.STRING) on the User entity.
 * Example DB value: "PENDING_VERIFICATION", "ACTIVE", "SUSPENDED"
 */
@Getter
@RequiredArgsConstructor
public enum UserStatus {

    /**
     * Account has been created but email/phone is not yet verified.
     * User CANNOT log in at this stage.
     * System auto-sets this on registration.
     * Transitions to ACTIVE after successful OTP / email link verification.
     */
    PENDING_VERIFICATION("Pending Verification"),

    /**
     * Account is fully verified and operational.
     * User can log in and access all features permitted by their Role.
     * This is the normal healthy state of an account.
     */
    ACTIVE("Active"),

    /**
     * User has voluntarily deactivated their own account.
     * User cannot log in, but their data is fully preserved.
     * Can be reactivated by the user themselves or by an Admin.
     */
    INACTIVE("Inactive"),

    /**
     * Account has been suspended by an Admin due to a policy violation,
     * fraud report, or pending investigation.
     * User cannot log in. Only an Admin can lift this suspension.
     * Different from INACTIVE — this is platform-initiated, not user-initiated.
     */
    SUSPENDED("Suspended"),

    /**
     * Account has been soft-deleted.
     * The database row is NEVER removed — data is preserved for:
     *   - Audit trail compliance
     *   - Referential integrity (bookings, payments still reference this user)
     *   - Legal / financial record-keeping requirements
     *
     * A deleted user cannot log in or be searched publicly.
     */
    DELETED("Deleted");

    // Human-readable label for UI messages and email templates
    private final String description;
}

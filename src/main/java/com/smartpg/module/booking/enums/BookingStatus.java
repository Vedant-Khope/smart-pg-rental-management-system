package com.smartpg.module.booking.enums;

public enum BookingStatus {
    /**
     * Tenant applied for the bed, waiting for owner approval.
     */
    PENDING,

    /**
     * Owner approved the booking. Tenant is expected to move in.
     */
    APPROVED,

    /**
     * Owner rejected the booking request.
     */
    REJECTED,

    /**
     * Tenant cancelled the booking before move-in.
     */
    CANCELLED,

    /**
     * Soft delete status. Kept in DB for audit but excluded from active queries.
     */
    DELETED
}

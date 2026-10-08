package com.smartpg.module.booking.enums;

public enum TenancyStatus {
    /**
     * Tenant is currently staying in the bed/room.
     */
    ACTIVE,

    /**
     * Tenant has submitted a move-out notice, but is still staying.
     */
    NOTICE_PERIOD,

    /**
     * Tenant has successfully moved out and the account is settled.
     */
    VACATED,

    /**
     * Tenant was forced to leave (rules violation, non-payment).
     */
    EVICTED,

    /**
     * Soft delete status. Kept in DB for audit but excluded from active queries.
     */
    DELETED
}

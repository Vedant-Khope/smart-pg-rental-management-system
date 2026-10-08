package com.smartpg.module.payment.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines the possible states of a monthly rent entry.
 */
@Getter
@RequiredArgsConstructor
public enum RentStatus {
    PENDING("Pending"),
    PAID("Paid"),
    OVERDUE("Overdue"),
    CANCELLED("Cancelled");

    private final String description;
}

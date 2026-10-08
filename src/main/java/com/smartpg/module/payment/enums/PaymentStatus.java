package com.smartpg.module.payment.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines the status of a specific payment transaction.
 */
@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    PENDING("Pending"),
    SUCCESS("Success"),
    FAILED("Failed"),
    REFUNDED("Refunded");

    private final String description;
}

package com.smartpg.module.payment.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines the allowed payment methods.
 */
@Getter
@RequiredArgsConstructor
public enum PaymentMethod {
    UPI("UPI"),
    CARD("Credit/Debit Card"),
    NETBANKING("Net Banking"),
    CASH("Cash");

    private final String description;
}

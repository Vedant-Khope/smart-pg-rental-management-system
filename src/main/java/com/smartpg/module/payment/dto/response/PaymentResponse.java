package com.smartpg.module.payment.dto.response;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Defines exactly what information is sent back to the frontend after a payment.
 * 
 * <p>Why not return the Payment entity?
 * If we returned the entity, Jackson (JSON parser) would try to serialize the
 * nested `rentLedger` and `paidBy` (User) objects. This could lead to infinite
 * recursion (StackOverflow) or expose sensitive data like the user's password hash.
 * DTOs keep the response clean and flat.
 */
@Data
public class PaymentResponse {
    private UUID id;
    private UUID rentLedgerId;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private String transactionReference;
    private Instant paymentDate;
}

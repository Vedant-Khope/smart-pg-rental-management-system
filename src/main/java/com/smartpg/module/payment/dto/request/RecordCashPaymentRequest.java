package com.smartpg.module.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO for recording a manual cash payment by an Owner.
 * 
 * <p>Why a DTO and not passing the Entity directly?
 * 1. Validation: We can use @NotNull and @Positive specifically for this API request.
 * 2. Security: We prevent Mass Assignment attacks. A malicious user can't send
 *    `"status": "SUCCESS"` in the JSON to hack the payment status, because this DTO
 *    doesn't even have a status field.
 */
@Data
public class RecordCashPaymentRequest {

    @NotNull(message = "Rent ledger ID is required")
    private UUID rentLedgerId;

    @NotNull(message = "Paid by user ID is required")
    private UUID paidByUserId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be strictly greater than zero")
    private BigDecimal amount;
}

package com.ledgercore.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request payload for depositing funds into a customer account.
 *
 * <p>amountMinor is capped at 9007199254740991 (2^53 - 1, JavaScript Number.MAX_SAFE_INTEGER).
 * Beyond this limit, IEEE-754 double precision floats used by JavaScript frontends lose
 * exact integer precision, leading to rounding and balance corruption in web/mobile clients.
 */
public record DepositRequest(
        @NotNull(message = "amountMinor must not be null")
        @Positive(message = "amountMinor must be positive")
        @Max(value = 9007199254740991L, message = "amountMinor exceeds JavaScript safe integer limit (9007199254740991)")
        Long amountMinor
) {}

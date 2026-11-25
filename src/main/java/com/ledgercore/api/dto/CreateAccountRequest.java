package com.ledgercore.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request payload for opening a new customer account.
 *
 * <p>amountMinor is capped at 9007199254740991 (2^53 - 1, JavaScript Number.MAX_SAFE_INTEGER).
 * Beyond this limit, IEEE-754 double precision floats used by JavaScript frontends lose
 * exact integer precision, leading to rounding and balance corruption in web/mobile clients.
 */
public record CreateAccountRequest(
        @Size(max = 255, message = "ownerRef must not exceed 255 characters")
        String ownerRef,

        @NotNull(message = "currency must not be null")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must match ^[A-Z]{3}$")
        String currency,

        @PositiveOrZero(message = "initialDepositMinor must be zero or positive")
        @Max(value = 9007199254740991L, message = "initialDepositMinor exceeds JavaScript safe integer limit (9007199254740991)")
        Long initialDepositMinor
) {
    public CreateAccountRequest {
        if (initialDepositMinor == null) {
            initialDepositMinor = 0L;
        }
    }
}

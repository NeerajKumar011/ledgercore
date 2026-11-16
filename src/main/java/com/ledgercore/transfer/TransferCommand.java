package com.ledgercore.transfer;

import java.util.UUID;

/**
 * Input to {@link TransferService#execute(TransferCommand)}.
 * All fields are validated by the service before any database interaction.
 *
 * @param idempotencyKey Caller-supplied deduplication key; max 128 characters.
 * @param fromAccountId  UUID of the account to debit.
 * @param toAccountId    UUID of the account to credit.
 * @param amountMinor    Transfer amount in minor currency units; must be > 0.
 * @param currency       ISO-4217 three-letter currency code, e.g. "USD".
 */
public record TransferCommand(
        String idempotencyKey,
        UUID   fromAccountId,
        UUID   toAccountId,
        long   amountMinor,
        String currency
) {}

package com.ledgercore.transfer;

import java.util.UUID;

/**
 * Result returned by {@link TransferService#execute(TransferCommand)}.
 *
 * @param transferId    The UUID of the transfer row (new or existing).
 * @param status        {@link TransferStatus#COMPLETED} or {@link TransferStatus#FAILED}.
 * @param failureReason Non-null only when {@code status == FAILED}.
 * @param replayed      {@code true} if this call returned the result of a
 *                      previously committed transfer (idempotent replay).
 */
public record TransferResult(
        UUID           transferId,
        TransferStatus status,
        String         failureReason,
        boolean        replayed
) {}

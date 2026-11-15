package com.ledgercore.transfer;

/**
 * Transfer lifecycle states matching the DB CHECK constraint.
 */
public enum TransferStatus {
    PENDING,
    COMPLETED,
    FAILED
}

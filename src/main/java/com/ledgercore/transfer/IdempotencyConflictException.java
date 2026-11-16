package com.ledgercore.transfer;

/**
 * Thrown when the same idempotency key is submitted with a different request
 * payload hash than the original submission.  This indicates a caller bug
 * (key reuse across unrelated transfers).
 * No database state is changed when this exception is thrown.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey
                + "' was already used with a different request payload");
    }
}

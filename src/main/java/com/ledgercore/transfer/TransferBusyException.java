package com.ledgercore.transfer;

/**
 * Thrown when a transfer cannot acquire pessimistic locks on participating accounts
 * within the configured {@code lock_timeout} window.
 * <p>This is a retryable transient exception.
 */
public class TransferBusyException extends RuntimeException {

    public TransferBusyException(String message) {
        super(message);
    }

    public TransferBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}

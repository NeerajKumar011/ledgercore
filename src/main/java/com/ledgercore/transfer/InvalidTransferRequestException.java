package com.ledgercore.transfer;

/**
 * Thrown when the {@link TransferCommand} contains invalid input.
 * No database state is changed when this exception is thrown.
 */
public class InvalidTransferRequestException extends RuntimeException {
    public InvalidTransferRequestException(String message) {
        super(message);
    }
}

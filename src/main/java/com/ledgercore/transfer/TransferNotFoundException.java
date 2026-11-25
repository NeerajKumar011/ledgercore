package com.ledgercore.transfer;

import java.util.UUID;

/**
 * Thrown when a requested transfer does not exist in the database.
 */
public class TransferNotFoundException extends RuntimeException {
    private final UUID transferId;

    public TransferNotFoundException(UUID transferId) {
        super("Transfer not found: " + transferId);
        this.transferId = transferId;
    }

    public UUID getTransferId() {
        return transferId;
    }
}

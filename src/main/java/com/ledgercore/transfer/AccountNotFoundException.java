package com.ledgercore.transfer;

import java.util.UUID;

/**
 * Thrown when a required account does not exist in the database.
 * No database state is changed when this exception is thrown.
 */
public class AccountNotFoundException extends RuntimeException {
    private final UUID accountId;

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}

package com.ledgercore.account;

import java.util.UUID;

/**
 * Thrown when a debit is attempted against a CUSTOMER account with insufficient balance.
 */
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final long currentBalanceMinor;
    private final long requestedDebitMinor;

    public InsufficientFundsException(UUID accountId, long currentBalanceMinor, long requestedDebitMinor) {
        super(String.format("Account %s has insufficient funds: balance=%d, requested debit=%d",
                accountId, currentBalanceMinor, requestedDebitMinor));
        this.accountId = accountId;
        this.currentBalanceMinor = currentBalanceMinor;
        this.requestedDebitMinor = requestedDebitMinor;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getCurrentBalanceMinor() {
        return currentBalanceMinor;
    }

    public long getRequestedDebitMinor() {
        return requestedDebitMinor;
    }
}

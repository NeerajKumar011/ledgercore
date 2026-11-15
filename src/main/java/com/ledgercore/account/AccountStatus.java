package com.ledgercore.account;

/**
 * Account lifecycle states matching the DB CHECK constraint.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}

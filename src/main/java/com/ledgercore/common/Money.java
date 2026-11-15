package com.ledgercore.common;

import java.util.Currency;
import java.util.Objects;

/**
 * Immutable value object representing a monetary amount in minor currency units (e.g. cents).
 * <p>All operations use exact integer arithmetic; floating-point types are strictly forbidden.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    public static Money of(long amountMinor, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: cannot add " + other.currency.getCurrencyCode()
                            + " to " + this.currency.getCurrencyCode()
            );
        }
        return new Money(Math.addExact(this.amountMinor, other.amountMinor), this.currency);
    }

    public Money minus(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: cannot subtract " + other.currency.getCurrencyCode()
                            + " from " + this.currency.getCurrencyCode()
            );
        }
        return new Money(Math.subtractExact(this.amountMinor, other.amountMinor), this.currency);
    }
}

package com.ledgercore.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 unit tests for {@link Account} domain logic.
 * Tests debit/credit rules, arithmetic overflow, lifecycle states, insufficient funds,
 * and system funding account negative balances.
 */
class AccountUnitTest {

    @Test
    @DisplayName("Newly created CUSTOMER account starts with balance 0 and CUSTOMER kind")
    void customerAccountDefaults() {
        Account acc = Account.create("ACC-U-1", "OWNER-1", "USD");
        assertThat(acc.getBalanceMinor()).isEqualTo(0L);
        assertThat(acc.getKind()).isEqualTo(AccountKind.CUSTOMER);
        assertThat(acc.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(acc.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Newly created SYSTEM account has standardized name, SYSTEM kind, and balance 0")
    void systemAccountDefaults() {
        Account sys = Account.createSystem("EUR");
        assertThat(sys.getAccountNumber()).isEqualTo("SYSTEM-FUNDING-EUR");
        assertThat(sys.getOwnerRef()).isEqualTo("SYSTEM");
        assertThat(sys.getKind()).isEqualTo(AccountKind.SYSTEM);
        assertThat(sys.getBalanceMinor()).isEqualTo(0L);
        assertThat(sys.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(sys.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("Credit increases balance and records updatedAt")
    void creditIncreasesBalance() {
        Account acc = Account.create("ACC-U-2", "OWNER-2", "USD");
        acc.credit(5000L);
        assertThat(acc.getBalanceMinor()).isEqualTo(5000L);

        acc.credit(2500L);
        assertThat(acc.getBalanceMinor()).isEqualTo(7500L);
    }

    @Test
    @DisplayName("Credit rejects zero or negative amounts")
    void creditRejectsNonPositive() {
        Account acc = Account.create("ACC-U-3", "OWNER-3", "USD");
        assertThatThrownBy(() -> acc.credit(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> acc.credit(-100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Credit throws ArithmeticException on numeric overflow")
    void creditThrowsOnOverflow() {
        Account acc = Account.create("ACC-U-4", "OWNER-4", "USD");
        acc.credit(Long.MAX_VALUE);

        assertThatThrownBy(() -> acc.credit(1L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Debit decreases balance on CUSTOMER account with sufficient funds")
    void debitDecreasesBalance() {
        Account acc = Account.create("ACC-U-5", "OWNER-5", "USD");
        acc.credit(10_000L);

        acc.debit(4_000L);
        assertThat(acc.getBalanceMinor()).isEqualTo(6_000L);
    }

    @Test
    @DisplayName("Debit rejects zero or negative amounts")
    void debitRejectsNonPositive() {
        Account acc = Account.create("ACC-U-6", "OWNER-6", "USD");
        acc.credit(1000L);

        assertThatThrownBy(() -> acc.debit(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> acc.debit(-50L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Debit on CUSTOMER account with insufficient funds throws InsufficientFundsException")
    void debitThrowsInsufficientFundsOnCustomer() {
        Account acc = Account.create("ACC-U-7", "OWNER-7", "USD");
        acc.credit(500L);

        assertThatThrownBy(() -> acc.debit(501L))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");
    }

    @Test
    @DisplayName("SYSTEM account may go negative on debit")
    void systemAccountMayGoNegative() {
        Account sys = Account.createSystem("USD");
        assertThat(sys.getBalanceMinor()).isEqualTo(0L);

        sys.debit(50_000L);
        assertThat(sys.getBalanceMinor()).isEqualTo(-50_000L);

        sys.debit(25_000L);
        assertThat(sys.getBalanceMinor()).isEqualTo(-75_000L);

        sys.credit(10_000L);
        assertThat(sys.getBalanceMinor()).isEqualTo(-65_000L);
    }

    @Test
    @DisplayName("Debit throws ArithmeticException on numeric underflow")
    void debitThrowsOnUnderflow() {
        Account sys = Account.createSystem("USD");
        sys.debit(Long.MAX_VALUE);

        assertThatThrownBy(() -> sys.debit(2L))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Operations on FROZEN or CLOSED accounts throw IllegalStateException")
    void inactiveAccountRejectsOperations() {
        Account frozen = Account.create("ACC-U-8", "O8", "USD");
        frozen.credit(1000L);
        frozen.freeze();

        assertThatThrownBy(() -> frozen.debit(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");
        assertThatThrownBy(() -> frozen.credit(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FROZEN");

        Account closed = Account.create("ACC-U-9", "O9", "USD");
        closed.close();

        assertThatThrownBy(() -> closed.debit(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
        assertThatThrownBy(() -> closed.credit(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("State machine transitions: freeze, close, reactivate rules")
    void lifecycleStateMachine() {
        Account acc = Account.create("ACC-U-10", "O10", "USD");
        assertThat(acc.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        acc.freeze();
        assertThat(acc.getStatus()).isEqualTo(AccountStatus.FROZEN);

        acc.reactivate();
        assertThat(acc.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        acc.close();
        assertThat(acc.getStatus()).isEqualTo(AccountStatus.CLOSED);

        // CLOSED is terminal
        assertThatThrownBy(acc::reactivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
        assertThatThrownBy(acc::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already CLOSED");
    }

    @Test
    @DisplayName("Currency format validation enforces ^[A-Z]{3}$")
    void currencyValidation() {
        assertThatThrownBy(() -> Account.create("ACC-1", "O", "usd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.create("ACC-1", "O", "US"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.create("ACC-1", "O", "US1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.createSystem("us"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

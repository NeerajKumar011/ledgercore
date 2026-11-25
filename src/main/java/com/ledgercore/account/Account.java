package com.ledgercore.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Account holding a balance in minor currency units.
 * <p>Supports regular {@link AccountKind#CUSTOMER} accounts and internal
 * {@link AccountKind#SYSTEM} funding accounts.
 * <p>Optimistic locking is enforced via the {@code version} field.
 * Public setters are omitted by design; instances are created via factory methods.
 * Status transitions are expressed through domain methods: {@link #freeze()},
 * {@link #close()}, and {@link #reactivate()}. {@code CLOSED} is terminal.
 * <p>Balance modifications must strictly be made via {@link #debit(long)} and
 * {@link #credit(long)}.
 */
@Entity
@Table(name = "accounts")
public class Account {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(name = "owner_ref", nullable = false, length = 255)
    private String ownerRef;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private AccountKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AccountStatus status;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // Required by JPA specification
    }

    /**
     * Factory method to create a new CUSTOMER Account with zero initial balance and optional ownerUserId.
     */
    public static Account create(String accountNumber, String ownerRef, String currency) {
        return create(UUID.randomUUID(), accountNumber, ownerRef, currency, null);
    }

    public static Account create(String accountNumber, String ownerRef, String currency, UUID ownerUserId) {
        return create(UUID.randomUUID(), accountNumber, ownerRef, currency, ownerUserId);
    }

    /**
     * Factory method to create a CUSTOMER Account with a pre-determined UUID and zero initial balance.
     */
    public static Account create(UUID id, String accountNumber, String ownerRef, String currency) {
        return create(id, accountNumber, ownerRef, currency, null);
    }

    public static Account create(UUID id, String accountNumber, String ownerRef, String currency, UUID ownerUserId) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must match ^[A-Z]{3}$: " + currency);
        }

        Account account = new Account();
        account.id = id;
        account.accountNumber = accountNumber;
        account.ownerRef = ownerRef;
        account.ownerUserId = ownerUserId;
        account.currency = currency;
        account.kind = AccountKind.CUSTOMER;
        account.status = AccountStatus.ACTIVE;
        account.balanceMinor = 0L;
        account.version = 0L;
        Instant now = Instant.now();
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    /**
     * Factory method to create a SYSTEM funding account for a given currency.
     * System funding accounts have the standardized account number "SYSTEM-FUNDING-&lt;CCY&gt;".
     */
    public static Account createSystem(String currency) {
        return createSystem(UUID.randomUUID(), currency);
    }

    /**
     * Factory method to create a SYSTEM funding account with a pre-determined UUID.
     */
    public static Account createSystem(UUID id, String currency) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must match ^[A-Z]{3}$: " + currency);
        }

        Account account = new Account();
        account.id = id;
        account.accountNumber = "SYSTEM-FUNDING-" + currency;
        account.ownerRef = "SYSTEM";
        account.currency = currency;
        account.kind = AccountKind.SYSTEM;
        account.status = AccountStatus.ACTIVE;
        account.balanceMinor = 0L;
        account.version = 0L;
        Instant now = Instant.now();
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    @PrePersist
    protected void onPrePersist() {
        Instant now = Instant.now();
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.kind == null) {
            this.kind = AccountKind.CUSTOMER;
        }
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain debit / credit methods — no public setters, no updateBalance
    // -------------------------------------------------------------------------

    /**
     * Debits the account by {@code amountMinor}.
     * <p><b>Double-entry requirement:</b> Every call to {@code debit} must be
     * matched by a corresponding ledger entry in the same transaction.
     *
     * @param amountMinor amount to debit in minor units; must be strictly positive
     * @throws IllegalArgumentException if {@code amountMinor <= 0}
     * @throws IllegalStateException if the account is not {@link AccountStatus#ACTIVE}
     * @throws InsufficientFundsException if this is a {@link AccountKind#CUSTOMER}
     *         account and {@code balanceMinor < amountMinor}
     * @throws ArithmeticException if numeric underflow occurs
     */
    public void debit(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be strictly positive (> 0), got: " + amountMinor);
        }
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Cannot debit account " + id + ": status is " + status);
        }
        if (this.kind == AccountKind.CUSTOMER && this.balanceMinor < amountMinor) {
            throw new InsufficientFundsException(this.id, this.balanceMinor, amountMinor);
        }
        this.balanceMinor = Math.subtractExact(this.balanceMinor, amountMinor);
        this.updatedAt = Instant.now();
    }

    /**
     * Credits the account by {@code amountMinor}.
     * <p><b>Double-entry requirement:</b> Every call to {@code credit} must be
     * matched by a corresponding ledger entry in the same transaction.
     *
     * @param amountMinor amount to credit in minor units; must be strictly positive
     * @throws IllegalArgumentException if {@code amountMinor <= 0}
     * @throws IllegalStateException if the account is not {@link AccountStatus#ACTIVE}
     * @throws ArithmeticException if numeric overflow occurs
     */
    public void credit(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be strictly positive (> 0), got: " + amountMinor);
        }
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Cannot credit account " + id + ": status is " + status);
        }
        this.balanceMinor = Math.addExact(this.balanceMinor, amountMinor);
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions an ACTIVE account to FROZEN.
     * @throws IllegalStateException if the account is not ACTIVE
     */
    public void freeze() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot freeze account " + id + ": current status is " + status);
        }
        this.status = AccountStatus.FROZEN;
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions an ACTIVE or FROZEN account to CLOSED.
     * CLOSED is terminal; it cannot be undone.
     * @throws IllegalStateException if the account is already CLOSED
     */
    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException(
                    "Cannot close account " + id + ": already CLOSED");
        }
        this.status = AccountStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    /**
     * Transitions a FROZEN account back to ACTIVE.
     * CLOSED accounts are terminal and cannot be reactivated.
     * @throws IllegalStateException if the account is ACTIVE or CLOSED
     */
    public void reactivate() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException(
                    "Cannot reactivate account " + id + ": CLOSED is a terminal state");
        }
        if (this.status == AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot reactivate account " + id + ": already ACTIVE");
        }
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerRef() {
        return ownerRef;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountKind getKind() {
        return kind;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account account)) return false;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

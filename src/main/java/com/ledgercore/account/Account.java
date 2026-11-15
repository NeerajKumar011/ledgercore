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

/**
 * Customer account holding a balance in minor currency units.
 * <p>Optimistic locking is enforced via the {@code version} field.
 * Public setters are omitted by design; instances are created via factory methods.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(name = "owner_ref", nullable = false, length = 255)
    private String ownerRef;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

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
     * Factory method to create a new Account with an initial balance.
     */
    public static Account create(String accountNumber, String ownerRef, String currency, long initialBalanceMinor) {
        return create(UUID.randomUUID(), accountNumber, ownerRef, currency, initialBalanceMinor);
    }

    /**
     * Factory method to create an Account with a pre-determined UUID (useful for testing or migrations).
     */
    public static Account create(UUID id, String accountNumber, String ownerRef, String currency, long initialBalanceMinor) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code: " + currency);
        }

        Account account = new Account();
        account.id = id;
        account.accountNumber = accountNumber;
        account.ownerRef = ownerRef;
        account.currency = currency;
        account.status = AccountStatus.ACTIVE;
        account.balanceMinor = initialBalanceMinor;
        account.version = 0L;
        Instant now = Instant.now();
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    /**
     * Factory method to create an Account with zero initial balance.
     */
    public static Account create(String accountNumber, String ownerRef, String currency) {
        return create(accountNumber, ownerRef, currency, 0L);
    }

    @PrePersist
    protected void onPrePersist() {
        Instant now = Instant.now();
        if (this.id == null) {
            this.id = UUID.randomUUID();
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

    // Domain update methods (no public setters)

    public void updateBalance(long newBalanceMinor) {
        this.balanceMinor = newBalanceMinor;
        this.updatedAt = Instant.now();
    }

    public void changeStatus(AccountStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status must not be null");
        this.updatedAt = Instant.now();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerRef() {
        return ownerRef;
    }

    public String getCurrency() {
        return currency;
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

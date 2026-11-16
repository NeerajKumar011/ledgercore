package com.ledgercore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable entry in the double-entry bookkeeping ledger.
 * <p>Annotated with Hibernate {@link Immutable} to prevent in-memory dirty checking updates.
 * Rows are insert-only at both the application and database (trigger) levels.
 *
 * <p><b>Persistable implementation:</b> {@link LedgerEntry} implements
 * {@link Persistable}{@code <UUID>} and carries a transient {@code isNew} flag.
 * For newly instantiated entities created via factory methods, {@code isNew} is initialized
 * to {@code true}. This signals Spring Data JPA to invoke {@code EntityManager.persist()}
 * directly, avoiding the unnecessary {@code SELECT}-before-{@code INSERT} query that
 * {@code EntityManager.merge()} would execute for non-null identifiers.
 * Once persisted or loaded from the database, the {@link #markNotNew()} callback sets
 * {@code isNew} to {@code false}.
 */
@Entity
@Table(name = "ledger_entries")
@Immutable
public class LedgerEntry implements Persistable<UUID> {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 6)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Transient flag: true only on new instances created by the factory method.
     * Spring Data JPA reads this via {@link #isNew()} to choose persist() over merge().
     * Set to false via @PostPersist and @PostLoad.
     */
    @Transient
    private boolean isNew;

    protected LedgerEntry() {
        // Required by JPA
    }

    public static LedgerEntry create(UUID transferId, UUID accountId,
                                     EntryDirection direction, long amountMinor,
                                     String currency) {
        return create(UUID.randomUUID(), transferId, accountId, direction, amountMinor, currency);
    }

    public static LedgerEntry create(UUID id, UUID transferId, UUID accountId,
                                     EntryDirection direction, long amountMinor,
                                     String currency) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must match ^[A-Z]{3}$: " + currency);
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be strictly positive (> 0)");
        }

        LedgerEntry entry = new LedgerEntry();
        entry.id = id;
        entry.transferId = transferId;
        entry.accountId = accountId;
        entry.direction = direction;
        entry.amountMinor = amountMinor;
        entry.currency = currency;
        entry.createdAt = Instant.now();
        entry.isNew = true;  // Signals Spring Data to use persist() not merge()
        return entry;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false;
    }

    // -------------------------------------------------------------------------
    // Persistable<UUID>
    // -------------------------------------------------------------------------

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    // -------------------------------------------------------------------------
    // Getters only — NO public or private setters (entity is strictly immutable)
    // -------------------------------------------------------------------------

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerEntry that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

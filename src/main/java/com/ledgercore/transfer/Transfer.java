package com.ledgercore.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transfer record representing a movement of funds between two accounts.
 * <p>From and to accounts are stored as plain UUID identifiers (no JPA entity navigation)
 * to avoid lazy-loading traps, N+1 query surprises, and unintentional cascading updates.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 255)
    private String requestHash;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private TransferStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
        // Required by JPA
    }

    public static Transfer create(String idempotencyKey, String requestHash,
                                  UUID fromAccountId, UUID toAccountId,
                                  long amountMinor, String currency) {
        return create(UUID.randomUUID(), idempotencyKey, requestHash, fromAccountId, toAccountId, amountMinor, currency);
    }

    public static Transfer create(UUID id, String idempotencyKey, String requestHash,
                                  UUID fromAccountId, UUID toAccountId,
                                  long amountMinor, String currency) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        Objects.requireNonNull(fromAccountId, "fromAccountId must not be null");
        Objects.requireNonNull(toAccountId, "toAccountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code: " + currency);
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must be distinct");
        }

        Transfer transfer = new Transfer();
        transfer.id = id;
        transfer.idempotencyKey = idempotencyKey;
        transfer.requestHash = requestHash;
        transfer.fromAccountId = fromAccountId;
        transfer.toAccountId = toAccountId;
        transfer.amountMinor = amountMinor;
        transfer.currency = currency;
        transfer.status = TransferStatus.PENDING;
        Instant now = Instant.now();
        transfer.createdAt = now;
        transfer.updatedAt = now;
        return transfer;
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

    public void markCompleted() {
        this.status = TransferStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
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
        if (!(o instanceof Transfer transfer)) return false;
        return Objects.equals(id, transfer.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

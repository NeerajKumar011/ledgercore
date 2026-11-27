package com.ledgercore.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing a tamper-evident, hash-chained audit event.
 * Mapped to table `audit_events`. DB triggers reject any UPDATE, DELETE, or TRUNCATE.
 */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(name = "seq", nullable = false, updatable = false)
    private Long seq;

    @Column(name = "id", nullable = false, unique = true, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_role", length = 10, updatable = false)
    private String actorRole;

    @Column(name = "action", length = 40, nullable = false, updatable = false)
    private String action;

    @Column(name = "outcome", length = 10, nullable = false, updatable = false)
    private String outcome;

    @Column(name = "target_type", length = 20, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 64, updatable = false)
    private String targetId;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "client_ip", length = 45, updatable = false)
    private String clientIp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", updatable = false)
    private String details;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", length = 64, nullable = false, updatable = false, columnDefinition = "char(64)")
    private String prevHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "row_hash", length = 64, nullable = false, updatable = false, columnDefinition = "char(64)")
    private String rowHash;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(
            Long seq,
            UUID id,
            Instant occurredAt,
            UUID actorUserId,
            String actorRole,
            String action,
            String outcome,
            String targetType,
            String targetId,
            String requestId,
            String clientIp,
            String details,
            String prevHash,
            String rowHash) {
        this.seq = Objects.requireNonNull(seq, "seq must not be null");
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.targetType = targetType;
        this.targetId = targetId;
        this.requestId = requestId;
        this.clientIp = clientIp;
        this.details = details;
        this.prevHash = Objects.requireNonNull(prevHash, "prevHash must not be null");
        this.rowHash = Objects.requireNonNull(rowHash, "rowHash must not be null");
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getDetails() {
        return details;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getRowHash() {
        return rowHash;
    }
}

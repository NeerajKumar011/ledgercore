package com.ledgercore.audit;

import com.ledgercore.api.filter.RequestIdFilter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the append-only, hash-chained tamper-evident audit log.
 * Serializes writes using PostgreSQL transaction-level advisory locks to ensure strictly contiguous seq order.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final long AUDIT_ADVISORY_LOCK_ID = 8492049102941029384L;
    private static final int VERIFY_BATCH_SIZE = 1000;

    private final AuditRepository auditRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public AuditService(AuditRepository auditRepository, EntityManager entityManager, Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records an administrative or mutation event within the caller's active database transaction.
     * Fails CLOSED: any failure to record the audit event causes the enclosing business transaction to roll back.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordAdmin(
            String action,
            String outcome,
            UUID actorUserId,
            String actorRole,
            String targetType,
            String targetId,
            String detailsJson) {
        return recordInternal(action, outcome, actorUserId, actorRole, targetType, targetId, detailsJson);
    }

    /**
     * Records an authentication event in an independent transaction (REQUIRES_NEW).
     * Fails OPEN: failures are logged at ERROR without leaking secrets, but do not block authentication flows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordAuth(
            String action,
            String outcome,
            UUID actorUserId,
            String actorRole,
            String targetType,
            String targetId,
            String detailsJson) {
        try {
            return recordInternal(action, outcome, actorUserId, actorRole, targetType, targetId, detailsJson);
        } catch (Exception e) {
            log.error("Failed to record auth audit event for action {}: {}", action, e.getMessage());
            return null;
        }
    }

    private AuditEvent recordInternal(
            String action,
            String outcome,
            UUID actorUserId,
            String actorRole,
            String targetType,
            String targetId,
            String detailsJson) {

        // 1. Acquire transaction-level advisory lock to serialize seq assignment and prev_hash binding
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, AUDIT_ADVISORY_LOCK_ID)
                .getSingleResult();

        // 2. Read current chain head
        Optional<AuditEvent> topOpt = auditRepository.findTopByOrderBySeqDesc();
        long nextSeq = topOpt.map(t -> t.getSeq() + 1).orElse(1L);
        String prevHash = topOpt.map(AuditEvent::getRowHash).orElse(AuditCanonicalizer.GENESIS_PREV_HASH);

        // 3. Current timestamp truncated to microseconds
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID eventId = UUID.randomUUID();

        // 4. Capture request context if running in HTTP request thread
        String clientIp = null;
        String requestId = null;
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            clientIp = request.getRemoteAddr();
            requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        }

        // 5. Compute SHA-256 row hash over canonical representation
        String canonicalDetails = AuditCanonicalizer.canonicalizeJson(detailsJson);
        String rowHash = AuditCanonicalizer.computeRowHash(
                nextSeq, eventId, occurredAt, actorUserId, actorRole, action, outcome,
                targetType, targetId, requestId, clientIp, canonicalDetails, prevHash
        );

        AuditEvent event = new AuditEvent(
                nextSeq, eventId, occurredAt, actorUserId, actorRole, action, outcome,
                targetType, targetId, requestId, clientIp,
                canonicalDetails.isEmpty() ? null : canonicalDetails,
                prevHash, rowHash
        );

        return auditRepository.saveAndFlush(event);
    }

    /**
     * Verifies the entire audit chain in streaming batches under REPEATABLE_READ isolation.
     * Computes expected hashes and checks prev_hash links row by row.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditVerificationResponse verifyChain() {
        long lastSeq = 0L;
        long eventsChecked = 0L;
        String expectedPrevHash = AuditCanonicalizer.GENESIS_PREV_HASH;
        Long lastValidSeq = null;
        String lastValidHash = null;

        while (true) {
            List<AuditEvent> batch = auditRepository.findNextBatch(lastSeq, VERIFY_BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }

            for (AuditEvent event : batch) {
                // Check prev_hash link
                if (!expectedPrevHash.equals(event.getPrevHash())) {
                    log.error("VERIFY PREV_HASH MISMATCH at seq {}: expected '{}', actual in row '{}'",
                            event.getSeq(), expectedPrevHash, event.getPrevHash());
                    return new AuditVerificationResponse(false, eventsChecked, event.getSeq(), lastValidSeq, lastValidHash);
                }

                // Recompute row_hash over canonical representation
                String expectedRowHash = AuditCanonicalizer.computeRowHash(
                        event.getSeq(),
                        event.getId(),
                        event.getOccurredAt(),
                        event.getActorUserId(),
                        event.getActorRole(),
                        event.getAction(),
                        event.getOutcome(),
                        event.getTargetType(),
                        event.getTargetId(),
                        event.getRequestId(),
                        event.getClientIp(),
                        event.getDetails(),
                        event.getPrevHash()
                );

                if (!expectedRowHash.equals(event.getRowHash())) {
                    log.error("VERIFY ROW_HASH MISMATCH at seq {}: expected '{}', actual in row '{}'. Canonical: '{}'",
                            event.getSeq(), expectedRowHash, event.getRowHash(),
                            AuditCanonicalizer.buildCanonicalString(
                                    event.getSeq(), event.getId(), event.getOccurredAt(),
                                    event.getActorUserId(), event.getActorRole(), event.getAction(), event.getOutcome(),
                                    event.getTargetType(), event.getTargetId(), event.getRequestId(), event.getClientIp(),
                                    event.getDetails(), event.getPrevHash()
                            ));
                    return new AuditVerificationResponse(false, eventsChecked, event.getSeq(), lastValidSeq, lastValidHash);
                }

                expectedPrevHash = event.getRowHash();
                lastValidSeq = event.getSeq();
                lastValidHash = event.getRowHash();
                lastSeq = event.getSeq();
                eventsChecked++;
            }
        }

        return new AuditVerificationResponse(true, eventsChecked, null, lastValidSeq, lastValidHash);
    }

    /**
     * Returns the current head of the audit chain.
     */
    @Transactional(readOnly = true)
    public AuditHeadResponse getHead() {
        return auditRepository.findTopByOrderBySeqDesc()
                .map(e -> new AuditHeadResponse(e.getSeq(), e.getRowHash(), e.getOccurredAt()))
                .orElse(new AuditHeadResponse(null, null, null));
    }

    /**
     * Queries audit events with optional filtering, newest first.
     */
    @Transactional(readOnly = true)
    public Slice<AuditEventResponse> findEvents(
            String action,
            UUID actorUserId,
            Instant from,
            Instant to,
            Pageable pageable) {
        return auditRepository.findFiltered(action, actorUserId, from, to, pageable)
                .map(AuditEventResponse::fromEntity);
    }
}

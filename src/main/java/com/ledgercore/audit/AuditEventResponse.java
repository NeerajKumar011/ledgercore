package com.ledgercore.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        long seq,
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
        String rowHash
) {
    public static AuditEventResponse fromEntity(AuditEvent event) {
        return new AuditEventResponse(
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
                event.getPrevHash(),
                event.getRowHash()
        );
    }
}

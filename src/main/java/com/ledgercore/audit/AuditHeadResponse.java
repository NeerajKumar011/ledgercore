package com.ledgercore.audit;

import java.time.Instant;

public record AuditHeadResponse(
        Long seq,
        String rowHash,
        Instant occurredAt
) {}

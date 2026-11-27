package com.ledgercore.audit;

public record AuditVerificationResponse(
        boolean valid,
        long eventsChecked,
        Long firstBrokenSeq,
        Long headSeq,
        String headHash
) {}

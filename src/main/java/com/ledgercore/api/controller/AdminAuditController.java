package com.ledgercore.api.controller;

import com.ledgercore.audit.AuditEventResponse;
import com.ledgercore.audit.AuditHeadResponse;
import com.ledgercore.audit.AuditService;
import com.ledgercore.audit.AuditVerificationResponse;
import com.ledgercore.security.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Administrative endpoints for audit log inspection, head verification, and chain re-computation.
 * Requires ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminAuditController(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/verify")
    public ResponseEntity<AuditVerificationResponse> verifyChain(AuthenticatedUser caller) {
        AuditVerificationResponse result = auditService.verifyChain();

        // Record AUDIT_VERIFY_RUN audit event
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "valid", result.valid(),
                    "eventsChecked", result.eventsChecked(),
                    "headSeq", result.headSeq() != null ? result.headSeq() : 0
            ));
            auditService.recordAdmin(
                    "AUDIT_VERIFY_RUN",
                    result.valid() ? "SUCCESS" : "FAILURE",
                    caller.userId(),
                    caller.role(),
                    "AUDIT",
                    "VERIFY",
                    details
            );
        } catch (Exception e) {
            // Non-critical audit recording failure for verification read
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/head")
    public ResponseEntity<AuditHeadResponse> getHead() {
        return ResponseEntity.ok(auditService.getHead());
    }

    @GetMapping
    public ResponseEntity<Slice<AuditEventResponse>> getAuditEvents(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "actorUserId", required = false) UUID actorUserId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        int boundedSize = Math.clamp(size, 1, 100);
        int boundedPage = Math.max(page, 0);

        Slice<AuditEventResponse> events = auditService.findEvents(
                action, actorUserId, from, to, PageRequest.of(boundedPage, boundedSize)
        );

        return ResponseEntity.ok(events);
    }
}

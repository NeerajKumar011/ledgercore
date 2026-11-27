package com.ledgercore.api.controller;

import com.ledgercore.reconciliation.ReconciliationReport;
import com.ledgercore.reconciliation.ReconciliationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative controller for ledger integrity and reconciliation audits.
 *
 * <p>NOTE: In Phase 4, access to /api/v1/admin/** will be restricted to authenticated
 * users possessing the ADMIN role.
 */
@RestController
@RequestMapping(path = "/api/v1/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminController {

    private final ReconciliationService reconciliationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ledgercore.audit.AuditService auditService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * 7. Reconciles all accounts, currencies, and transfers across the double-entry ledger.
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationReport> reconcile(org.springframework.security.core.Authentication authentication) {
        ReconciliationReport report = reconciliationService.reconcile();
        if (auditService != null) {
            com.ledgercore.security.AuthenticatedUser caller = (authentication != null) ? com.ledgercore.security.AuthenticatedUser.from(authentication) : null;
            auditService.recordAdmin(
                    "RECONCILIATION_RUN",
                    "SUCCESS",
                    caller != null ? caller.userId() : null,
                    caller != null ? caller.role() : null,
                    "SYSTEM",
                    "RECONCILIATION",
                    "{\"consistent\":" + report.consistent() + "}"
            );
        }
        return ResponseEntity.ok(report);
    }
}

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

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * 7. Reconciles all accounts, currencies, and transfers across the double-entry ledger.
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationReport> reconcile() {
        ReconciliationReport report = reconciliationService.reconcile();
        return ResponseEntity.ok(report);
    }
}

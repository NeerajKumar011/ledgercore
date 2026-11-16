package com.ledgercore.reconciliation;

import java.util.List;
import java.util.Map;

/**
 * Report containing bookkeeping integrity and reconciliation results.
 *
 * @param consistent true if all reconciliation checks pass without discrepancy
 * @param problems list of discrepancies found across accounts, currencies, or transfers
 * @param balancesByCurrency map of currency code to net sum of balances across all accounts
 */
public record ReconciliationReport(
        boolean consistent,
        List<String> problems,
        Map<String, Long> balancesByCurrency
) {}

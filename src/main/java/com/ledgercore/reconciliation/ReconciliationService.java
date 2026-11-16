package com.ledgercore.reconciliation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service performing double-entry bookkeeping consistency checks and integrity reconciliation.
 * <p>Executes at {@link Isolation#REPEATABLE_READ} read-only isolation using efficient
 * SQL aggregates without loading entire entity collections into JVM memory.
 */
@Service
public class ReconciliationService {

    private final JdbcClient jdbcClient;

    public ReconciliationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Reconciles the entire ledger across all accounts, currencies, and transfers.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        List<String> problems = new ArrayList<>();
        Map<String, Long> balancesByCurrency = new LinkedHashMap<>();

        // Check A: Per account: balance_minor = SUM(CREDIT) - SUM(DEBIT)
        checkAccountBalances(problems);

        // Check B: Per currency: SUM(balance_minor) over all accounts = 0
        checkCurrencyBalanceSums(problems, balancesByCurrency);

        // Check C1: Per COMPLETED transfer: exactly two entries matching accounts, amount, and currency
        checkCompletedTransfers(problems);

        // Check C2: Per FAILED transfer: zero ledger entries
        checkFailedTransfers(problems);

        // Check D: No PENDING transfers in committed data
        checkPendingTransfers(problems);

        boolean consistent = problems.isEmpty();
        return new ReconciliationReport(
                consistent,
                Collections.unmodifiableList(problems),
                Collections.unmodifiableMap(balancesByCurrency)
        );
    }

    private void checkAccountBalances(List<String> problems) {
        String sql = """
                SELECT a.id, a.account_number, a.balance_minor,
                       COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount_minor
                                         WHEN le.direction = 'DEBIT'  THEN -le.amount_minor
                                         ELSE 0 END), 0) AS calculated_balance
                FROM accounts a
                LEFT JOIN ledger_entries le ON a.id = le.account_id
                GROUP BY a.id, a.account_number, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount_minor
                                                            WHEN le.direction = 'DEBIT'  THEN -le.amount_minor
                                                            ELSE 0 END), 0)
                """;

        jdbcClient.sql(sql)
                .query((rs, rowNum) -> String.format(
                        "Account %s (%s) balance mismatch: stored=%d, calculated=%d",
                        rs.getString("id"),
                        rs.getString("account_number"),
                        rs.getLong("balance_minor"),
                        rs.getLong("calculated_balance")
                ))
                .list()
                .forEach(problems::add);
    }

    private record CurrencyBalance(String currency, long totalBalance) {}

    private void checkCurrencyBalanceSums(List<String> problems, Map<String, Long> balancesByCurrency) {
        String sql = """
                SELECT a.currency, COALESCE(SUM(a.balance_minor), 0) AS total_balance
                FROM accounts a
                GROUP BY a.currency
                ORDER BY a.currency
                """;

        List<CurrencyBalance> balances = jdbcClient.sql(sql)
                .query((rs, rowNum) -> new CurrencyBalance(
                        rs.getString("currency").trim(),
                        rs.getLong("total_balance")
                ))
                .list();

        for (CurrencyBalance cb : balances) {
            balancesByCurrency.put(cb.currency(), cb.totalBalance());
            if (cb.totalBalance() != 0L) {
                problems.add(String.format(
                        "Currency %s net balance sum across all accounts is %d (expected 0)",
                        cb.currency(), cb.totalBalance()));
            }
        }
    }

    private void checkCompletedTransfers(List<String> problems) {
        String sql = """
                SELECT t.id,
                       COUNT(le.id) AS total_entries,
                       COUNT(CASE WHEN le.direction = 'DEBIT'  AND le.account_id = t.from_account_id
                                       AND le.amount_minor = t.amount_minor AND le.currency = t.currency THEN 1 END) AS valid_debits,
                       COUNT(CASE WHEN le.direction = 'CREDIT' AND le.account_id = t.to_account_id
                                       AND le.amount_minor = t.amount_minor AND le.currency = t.currency THEN 1 END) AS valid_credits
                FROM transfers t
                LEFT JOIN ledger_entries le ON t.id = le.transfer_id
                WHERE t.status = 'COMPLETED'
                GROUP BY t.id, t.from_account_id, t.to_account_id, t.amount_minor, t.currency
                HAVING COUNT(le.id) <> 2
                   OR COUNT(CASE WHEN le.direction = 'DEBIT'  AND le.account_id = t.from_account_id
                                      AND le.amount_minor = t.amount_minor AND le.currency = t.currency THEN 1 END) <> 1
                   OR COUNT(CASE WHEN le.direction = 'CREDIT' AND le.account_id = t.to_account_id
                                      AND le.amount_minor = t.amount_minor AND le.currency = t.currency THEN 1 END) <> 1
                """;

        jdbcClient.sql(sql)
                .query((rs, rowNum) -> String.format(
                        "Completed transfer %s has invalid ledger entries: total=%d, validDebits=%d, validCredits=%d",
                        rs.getString("id"),
                        rs.getInt("total_entries"),
                        rs.getInt("valid_debits"),
                        rs.getInt("valid_credits")
                ))
                .list()
                .forEach(problems::add);
    }

    private void checkFailedTransfers(List<String> problems) {
        String sql = """
                SELECT t.id, COUNT(le.id) AS entry_count
                FROM transfers t
                JOIN ledger_entries le ON t.id = le.transfer_id
                WHERE t.status = 'FAILED'
                GROUP BY t.id
                HAVING COUNT(le.id) > 0
                """;

        jdbcClient.sql(sql)
                .query((rs, rowNum) -> String.format(
                        "Failed transfer %s has %d unexpected ledger entries (expected 0)",
                        rs.getString("id"),
                        rs.getInt("entry_count")
                ))
                .list()
                .forEach(problems::add);
    }

    private void checkPendingTransfers(List<String> problems) {
        String sql = "SELECT t.id FROM transfers t WHERE t.status = 'PENDING'";

        jdbcClient.sql(sql)
                .query((rs, rowNum) -> String.format(
                        "Committed transfer %s is in PENDING status (expected COMPLETED or FAILED)",
                        rs.getString("id")
                ))
                .list()
                .forEach(problems::add);
    }
}

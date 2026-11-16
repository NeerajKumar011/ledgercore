package com.ledgercore.transfer;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountKind;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import com.ledgercore.ledger.EntryDirection;
import com.ledgercore.ledger.LedgerEntry;
import com.ledgercore.ledger.LedgerEntryRepository;
import com.ledgercore.reconciliation.ReconciliationReport;
import com.ledgercore.reconciliation.ReconciliationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link TransferService}, {@link AccountService},
 * and {@link ReconciliationService} against real PostgreSQL 18 via Testcontainers.
 *
 * <p><b>NO @Transactional on the class</b> — services commit their transactions
 * internally, ensuring real commit semantics.
 * <p><b>NO DELETE from ledger_entries</b> — trigger forbids deletes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    // ── Helper ────────────────────────────────────────────────────────────────

    private Account createAccount(String suffix, String currency, long balanceMinor) {
        return accountService.openAccount(
                "ACC-" + suffix + "-" + UUID.randomUUID(),
                "OWNER-" + suffix,
                currency,
                balanceMinor);
    }

    private String uniqueKey() {
        return "KEY-" + UUID.randomUUID();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Success: balances correct, money conserved, exactly two ledger entries, COMPLETED")
    void successTransfer() {
        Account from = createAccount("FROM-S", "USD", 10_000L);
        Account to   = createAccount("TO-S",   "USD",  2_000L);

        TransferResult result = transferService.execute(
                new TransferCommand(uniqueKey(), from.getId(), to.getId(), 3_000L, "USD"));

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.replayed()).isFalse();
        assertThat(result.failureReason()).isNull();

        // Re-read from DB
        Account reFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account reTo   = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(reFrom.getBalanceMinor()).isEqualTo(7_000L);  // 10000 - 3000
        assertThat(reTo.getBalanceMinor()).isEqualTo(5_000L);    // 2000  + 3000

        // Total money conserved between sender and receiver
        assertThat(reFrom.getBalanceMinor() + reTo.getBalanceMinor())
                .isEqualTo(from.getBalanceMinor() + to.getBalanceMinor());

        // Exactly two ledger entries
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(result.transferId());
        assertThat(entries).hasSize(2);

        LedgerEntry debit  = entries.stream().filter(e -> e.getDirection() == EntryDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getDirection() == EntryDirection.CREDIT).findFirst().orElseThrow();
        assertThat(debit.getAccountId()).isEqualTo(from.getId());
        assertThat(credit.getAccountId()).isEqualTo(to.getId());
        assertThat(debit.getAmountMinor()).isEqualTo(3_000L);
        assertThat(credit.getAmountMinor()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("Insufficient funds: FAILED, no entries, balances unchanged")
    void insufficientFunds() {
        Account from = createAccount("FROM-IF", "USD",   500L);
        Account to   = createAccount("TO-IF",   "USD", 1_000L);

        TransferResult result = transferService.execute(
                new TransferCommand(uniqueKey(), from.getId(), to.getId(), 1_000L, "USD"));

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).contains("Insufficient funds");

        Account reFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account reTo   = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(reFrom.getBalanceMinor()).isEqualTo(500L);
        assertThat(reTo.getBalanceMinor()).isEqualTo(1_000L);

        assertThat(ledgerEntryRepository.findByTransferId(result.transferId())).isEmpty();
    }

    @Test
    @DisplayName("Frozen account: FAILED")
    void frozenAccountFails() {
        Account from = createAccount("FROM-FRZ", "USD", 5_000L);
        Account to   = createAccount("TO-FRZ",   "USD", 1_000L);

        from.freeze();
        accountRepository.saveAndFlush(from);

        TransferResult result = transferService.execute(
                new TransferCommand(uniqueKey(), from.getId(), to.getId(), 100L, "USD"));

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).contains("ACTIVE");
        assertThat(ledgerEntryRepository.findByTransferId(result.transferId())).isEmpty();
    }

    @Test
    @DisplayName("Closed account: FAILED")
    void closedAccountFails() {
        Account from = createAccount("FROM-CLS", "USD", 5_000L);
        Account to   = createAccount("TO-CLS",   "USD", 1_000L);

        from.close();
        accountRepository.saveAndFlush(from);

        TransferResult result = transferService.execute(
                new TransferCommand(uniqueKey(), from.getId(), to.getId(), 100L, "USD"));

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).contains("ACTIVE");
        assertThat(ledgerEntryRepository.findByTransferId(result.transferId())).isEmpty();
    }

    @Test
    @DisplayName("Account currency != command currency: FAILED")
    void currencyMismatchFails() {
        Account from = createAccount("FROM-CCY", "USD", 5_000L);
        Account to   = createAccount("TO-CCY",   "USD", 1_000L);

        TransferResult result = transferService.execute(
                new TransferCommand(uniqueKey(), from.getId(), to.getId(), 100L, "EUR"));

        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).contains("currency");
        assertThat(ledgerEntryRepository.findByTransferId(result.transferId())).isEmpty();
    }

    @Test
    @DisplayName("Same key + same payload twice: returns same transferId, replayed=true, balances unchanged, still exactly two entries")
    void idempotentReplay() {
        Account from = createAccount("FROM-IDM", "USD", 10_000L);
        Account to   = createAccount("TO-IDM",   "USD",  2_000L);
        String key   = uniqueKey();

        TransferResult first  = transferService.execute(
                new TransferCommand(key, from.getId(), to.getId(), 500L, "USD"));
        TransferResult second = transferService.execute(
                new TransferCommand(key, from.getId(), to.getId(), 500L, "USD"));

        assertThat(second.transferId()).isEqualTo(first.transferId());
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(TransferStatus.COMPLETED);

        // Balances unchanged after second call
        Account reFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account reTo   = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(reFrom.getBalanceMinor()).isEqualTo(9_500L);
        assertThat(reTo.getBalanceMinor()).isEqualTo(2_500L);

        // Still exactly two ledger entries (no duplicates)
        assertThat(ledgerEntryRepository.findByTransferId(first.transferId())).hasSize(2);
    }

    @Test
    @DisplayName("Same key + different payload: IdempotencyConflictException, no change")
    void idempotencyConflict() {
        Account from = createAccount("FROM-ICF", "USD", 10_000L);
        Account to   = createAccount("TO-ICF",   "USD",  2_000L);
        Account other = createAccount("OTH-ICF", "USD",  2_000L);
        String key   = uniqueKey();

        // First call: succeeds
        transferService.execute(
                new TransferCommand(key, from.getId(), to.getId(), 500L, "USD"));

        // Second call: same key, different toAccount → conflict
        assertThatThrownBy(() ->
                transferService.execute(
                        new TransferCommand(key, from.getId(), other.getId(), 500L, "USD")))
                .isInstanceOf(IdempotencyConflictException.class);

        // Looked up by key: exactly one transfer exists with this key
        assertThat(transferRepository.findByIdempotencyKey(key)).isPresent();
    }

    @Test
    @DisplayName("FAILED result replayed after account is funded stays FAILED (never re-executes)")
    void failedResultIsNotReattempted() {
        Account from = createAccount("FROM-FRR", "USD",    0L);  // zero balance
        Account to   = createAccount("TO-FRR",   "USD", 1_000L);
        String key   = uniqueKey();

        // First call: fails due to insufficient funds
        TransferResult failed = transferService.execute(
                new TransferCommand(key, from.getId(), to.getId(), 500L, "USD"));
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);

        // Fund the source account via AccountService.deposit
        accountService.deposit(from.getId(), 10_000L, uniqueKey());

        // Second call with same key: must replay FAILED, NOT re-execute
        TransferResult replayed = transferService.execute(
                new TransferCommand(key, from.getId(), to.getId(), 500L, "USD"));
        assertThat(replayed.transferId()).isEqualTo(failed.transferId());
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.status()).isEqualTo(TransferStatus.FAILED);

        // Source balance must be unchanged from the deposit (no transfer debit happened)
        Account reFunded = accountRepository.findById(from.getId()).orElseThrow();
        assertThat(reFunded.getBalanceMinor()).isEqualTo(10_000L);

        // No ledger entries for the failed transfer
        assertThat(ledgerEntryRepository.findByTransferId(failed.transferId())).isEmpty();
    }

    @Test
    @DisplayName("Missing account: AccountNotFoundException and no transfer row stored")
    void missingAccountThrows() {
        Account from   = createAccount("FROM-MIS", "USD", 5_000L);
        UUID ghostId   = UUID.randomUUID();  // does not exist in DB
        String key     = uniqueKey();

        assertThatThrownBy(() ->
                transferService.execute(
                        new TransferCommand(key, from.getId(), ghostId, 100L, "USD")))
                .isInstanceOf(AccountNotFoundException.class);

        // No transfer row must have been committed
        assertThat(transferRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    @DisplayName("from == to: InvalidTransferRequestException (no DB touch)")
    void fromEqualsToIsRejected() {
        Account acc = createAccount("SLF", "USD", 5_000L);
        assertThatThrownBy(() ->
                transferService.execute(
                        new TransferCommand(uniqueKey(), acc.getId(), acc.getId(), 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class);
    }

    @Test
    @DisplayName("V2: TRUNCATE ledger_entries fails (run inside a transaction that rolls back)")
    @Transactional
    void truncateLedgerEntriesFails() {
        assertThatThrownBy(() ->
                entityManager.createNativeQuery("TRUNCATE ledger_entries")
                        .executeUpdate())
                .isInstanceOf(Exception.class)
                .hasMessageContaining("ledger_entries is INSERT-only: TRUNCATE is forbidden");
    }

    // ── Phase 2 Step B New Tests ──────────────────────────────────────────────

    @Test
    @DisplayName("openAccount with deposit: customer balance = deposit, funding account = -deposit, 2 ledger entries, reconciliation consistent")
    void openAccountWithDeposit() {
        long depositAmount = 15_000L;
        Account customer = accountService.openAccount("OWNER-DEP", "USD", depositAmount);

        Account reloadedCustomer = accountRepository.findById(customer.getId()).orElseThrow();
        assertThat(reloadedCustomer.getBalanceMinor()).isEqualTo(depositAmount);
        assertThat(reloadedCustomer.getKind()).isEqualTo(AccountKind.CUSTOMER);

        Account fundingAccount = accountService.getOrCreateFundingAccount("USD");
        // Funding account balance should reflect negative total of all funding deposits
        assertThat(fundingAccount.getBalanceMinor()).isNegative();

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.balancesByCurrency().get("USD")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Customer-to-customer transfers keep reconciliation consistent")
    void customerTransfersKeepReconciliationConsistent() {
        Account a1 = accountService.openAccount("OWNER-REC-1", "EUR", 20_000L);
        Account a2 = accountService.openAccount("OWNER-REC-2", "EUR", 5_000L);

        transferService.execute(new TransferCommand(uniqueKey(), a1.getId(), a2.getId(), 4_000L, "EUR"));
        transferService.execute(new TransferCommand(uniqueKey(), a2.getId(), a1.getId(), 1_500L, "EUR"));

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
        assertThat(report.balancesByCurrency().get("EUR")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Public execute rejects SYSTEM account with FAILED status")
    void publicExecuteRejectsSystemAccount() {
        Account systemAcc = accountService.getOrCreateFundingAccount("USD");
        Account customerAcc = accountService.openAccount("OWNER-PUB-SYS", "USD", 10_000L);

        // SYSTEM as source in public execute
        TransferResult result1 = transferService.execute(
                new TransferCommand(uniqueKey(), systemAcc.getId(), customerAcc.getId(), 100L, "USD"));
        assertThat(result1.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result1.failureReason()).contains("CUSTOMER accounts only");

        // SYSTEM as destination in public execute
        TransferResult result2 = transferService.execute(
                new TransferCommand(uniqueKey(), customerAcc.getId(), systemAcc.getId(), 100L, "USD"));
        assertThat(result2.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result2.failureReason()).contains("CUSTOMER accounts only");
    }

    @Test
    @DisplayName("V3: DB refuses an unbalanced journal at commit time via deferred constraint trigger")
    void dbRefusesUnbalancedJournalAtCommit() {
        Account a1 = accountService.openAccount("OWNER-UNB-1", "USD", 1000L);
        Account a2 = accountService.openAccount("OWNER-UNB-2", "USD", 1000L);

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> {
            template.execute(status -> {
                UUID txId = UUID.randomUUID();
                entityManager.createNativeQuery("""
                        INSERT INTO transfers
                            (id, idempotency_key, request_hash, from_account_id, to_account_id,
                             amount_minor, currency, status, created_at, updated_at)
                        VALUES
                            (:id, :key, 'HASH', :fromId, :toId, 500, 'USD', 'PENDING', now(), now())
                        """)
                        .setParameter("id", txId)
                        .setParameter("key", uniqueKey())
                        .setParameter("fromId", a1.getId())
                        .setParameter("toId", a2.getId())
                        .executeUpdate();

                // Insert only ONE lone ledger entry (unbalanced journal)
                entityManager.createNativeQuery("""
                        INSERT INTO ledger_entries
                            (id, transfer_id, account_id, direction, amount_minor, currency, created_at)
                        VALUES
                            (:id, :txId, :accId, 'DEBIT', 500, 'USD', now())
                        """)
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("txId", txId)
                        .setParameter("accId", a1.getId())
                        .executeUpdate();

                return null; // commit attempt fires deferred trigger
            });
        })
                .rootCause()
                .hasMessageContaining("Unbalanced journal for transfer");
    }

    @Test
    @DisplayName("Reconciliation detects balance corruption and restores consistency")
    void reconciliationDetectsCorruption() {
        Account acc = accountService.openAccount("CORRUPT-" + UUID.randomUUID(), "USD", 5_000L);
        long originalBalance = acc.getBalanceMinor();

        try {
            // Tamper with account balance via native SQL (bypassing domain validation)
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.execute(status -> {
                entityManager.createNativeQuery("UPDATE accounts SET balance_minor = balance_minor + 777 WHERE id = :id")
                        .setParameter("id", acc.getId())
                        .executeUpdate();
                return null;
            });

            ReconciliationReport report = reconciliationService.reconcile();
            assertThat(report.consistent()).isFalse();
            assertThat(report.problems())
                    .anyMatch(p -> p.contains(acc.getId().toString()) && p.contains("balance mismatch"));
        } finally {
            // Restore original balance
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.execute(status -> {
                entityManager.createNativeQuery("UPDATE accounts SET balance_minor = :orig WHERE id = :id")
                        .setParameter("orig", originalBalance)
                        .setParameter("id", acc.getId())
                        .executeUpdate();
                return null;
            });
        }

        // Verify ledger is consistent again after restoration
        ReconciliationReport restoredReport = reconciliationService.reconcile();
        assertThat(restoredReport.consistent()).isTrue();
    }

    @Test
    @DisplayName("Concurrent getOrCreateFundingAccount for one currency yields exactly one SYSTEM account")
    void concurrentFundingAccountCreation() throws Exception {
        String ccy = "GBP";
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Account>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> accountService.getOrCreateFundingAccount(ccy)));
        }

        List<UUID> returnedIds = new ArrayList<>();
        for (Future<Account> f : futures) {
            returnedIds.add(f.get(10, TimeUnit.SECONDS).getId());
        }
        executor.shutdown();

        assertThat(returnedIds).hasSize(threadCount);
        assertThat(returnedIds.stream().distinct().count()).isEqualTo(1L);

        Account fundingAccount = accountRepository.findById(returnedIds.getFirst()).orElseThrow();
        assertThat(fundingAccount.getKind()).isEqualTo(AccountKind.SYSTEM);
        assertThat(fundingAccount.getAccountNumber()).isEqualTo("SYSTEM-FUNDING-GBP");
    }
}

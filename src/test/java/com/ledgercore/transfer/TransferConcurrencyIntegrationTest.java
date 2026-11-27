package com.ledgercore.transfer;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountKind;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import com.ledgercore.ledger.LedgerEntry;
import com.ledgercore.ledger.LedgerEntryRepository;
import com.ledgercore.reconciliation.ReconciliationReport;
import com.ledgercore.reconciliation.ReconciliationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Concurrency proof integration test suite against real PostgreSQL 18 via Testcontainers.
 *
 * <p>Validates pessimistic locking ordering, idempotency races, thread-safe system funding,
 * double-entry consistency under load, and bounded lock waits.
 *
 * <p><b>NO @Transactional on test class</b> — transactions commit to live PostgreSQL.
 * <p><b>NO DELETE on ledger_entries</b> — trigger forbids deletes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "ledgercore.transfer.lock-timeout=3s"
})
class TransferConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TransferConcurrencyIntegrationTest.class);

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

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(32);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private String uniqueKey() {
        return "CONC-" + UUID.randomUUID();
    }

    // ── 5a. manyTransfersFromOneAccount ───────────────────────────────────────

    @RepeatedTest(3)
    @DisplayName("5a: 200 concurrent transfers from one account funded with 10_000: exactly 100 COMPLETED, 100 FAILED, balance 0")
    void manyTransfersFromOneAccount() throws Exception {
        Account source = accountService.openAccount("SRC-" + UUID.randomUUID(), "USD", 10_000L);
        int totalTransfers = 200;
        long transferAmount = 100L;

        List<Account> destinations = new ArrayList<>();
        for (int i = 0; i < totalTransfers; i++) {
            destinations.add(accountService.openAccount("DEST-" + i + "-" + UUID.randomUUID(), "USD", 0L));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalTransfers; i++) {
            Account dest = destinations.get(i);
            String key = uniqueKey();
            futures.add(executor.submit(() -> {
                startGate.await();
                return transferService.execute(new TransferCommand(key, source.getId(), dest.getId(), transferAmount, "USD"));
            }));
        }

        startGate.countDown();

        int completed = 0;
        int failed = 0;
        for (Future<TransferResult> f : futures) {
            try {
                TransferResult res = f.get(30, TimeUnit.SECONDS);
                if (res.status() == TransferStatus.COMPLETED) {
                    completed++;
                } else if (res.status() == TransferStatus.FAILED) {
                    failed++;
                    assertThat(res.failureReason()).contains("Insufficient funds");
                }
            } catch (ExecutionException e) {
                fail("Unexpected exception during transfer: " + e.getCause(), e.getCause());
            }
        }

        assertThat(completed).isEqualTo(100);
        assertThat(failed).isEqualTo(100);

        Account reloadedSource = accountRepository.findById(source.getId()).orElseThrow();
        assertThat(reloadedSource.getBalanceMinor()).isEqualTo(0L);

        long totalDestBalance = 0;
        for (Account d : destinations) {
            Account rd = accountRepository.findById(d.getId()).orElseThrow();
            totalDestBalance += rd.getBalanceMinor();
        }
        assertThat(totalDestBalance).isEqualTo(10_000L);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
    }

    // ── 5b. oppositeDirectionTransfers ────────────────────────────────────────

    @RepeatedTest(3)
    @DisplayName("5b: 100 concurrent A->B and 100 concurrent B->A: no deadlock, all COMPLETED, balances unchanged")
    void oppositeDirectionTransfers() throws Exception {
        Account a = accountService.openAccount("ACC-A-" + UUID.randomUUID(), "USD", 100_000L);
        Account b = accountService.openAccount("ACC-B-" + UUID.randomUUID(), "USD", 100_000L);
        int transfersPerDirection = 100;
        long amount = 100L;

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();

        for (int i = 0; i < transfersPerDirection; i++) {
            String keyAtoB = uniqueKey();
            futures.add(executor.submit(() -> {
                startGate.await();
                return transferService.execute(new TransferCommand(keyAtoB, a.getId(), b.getId(), amount, "USD"));
            }));

            String keyBtoA = uniqueKey();
            futures.add(executor.submit(() -> {
                startGate.await();
                return transferService.execute(new TransferCommand(keyBtoA, b.getId(), a.getId(), amount, "USD"));
            }));
        }

        startGate.countDown();

        int completed = 0;
        for (Future<TransferResult> f : futures) {
            try {
                TransferResult res = f.get(30, TimeUnit.SECONDS);
                assertThat(res.status()).isEqualTo(TransferStatus.COMPLETED);
                completed++;
            } catch (ExecutionException e) {
                fail("Deadlock or unexpected error in bidirectional transfer: " + e.getCause(), e.getCause());
            }
        }

        assertThat(completed).isEqualTo(200);

        Account reloadedA = accountRepository.findById(a.getId()).orElseThrow();
        Account reloadedB = accountRepository.findById(b.getId()).orElseThrow();
        assertThat(reloadedA.getBalanceMinor()).isEqualTo(100_000L);
        assertThat(reloadedB.getBalanceMinor()).isEqualTo(100_000L);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
    }

    // ── 5c. randomTransfersAcrossAccounts ─────────────────────────────────────

    @Test
    @DisplayName("5c: 500 concurrent transfers across 50 accounts: money conserved, log latency stats")
    void randomTransfersAcrossAccounts() throws Exception {
        long seed = 123456789L;
        Random rng = new Random(seed);
        log.info("Starting randomTransfersAcrossAccounts with fixed seed: {}", seed);

        int numAccounts = 50;
        int numTransfers = 500;
        long initialBalancePerAccount = 10_000L;
        long expectedTotalMoney = numAccounts * initialBalancePerAccount;

        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < numAccounts; i++) {
            accounts.add(accountService.openAccount("RND-ACC-" + i + "-" + UUID.randomUUID(), "USD", initialBalancePerAccount));
        }

        record PlannedTx(String key, UUID fromId, UUID toId, long amount) {}
        List<PlannedTx> planned = new ArrayList<>();
        for (int i = 0; i < numTransfers; i++) {
            int idxFrom = rng.nextInt(numAccounts);
            int idxTo = rng.nextInt(numAccounts);
            while (idxTo == idxFrom) {
                idxTo = rng.nextInt(numAccounts);
            }
            long amount = 1L + rng.nextInt(200);
            planned.add(new PlannedTx(uniqueKey(), accounts.get(idxFrom).getId(), accounts.get(idxTo).getId(), amount));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        record ExecRecord(TransferResult result, long latencyNanos, Throwable error) {}
        List<Future<ExecRecord>> futures = new ArrayList<>();

        long wallStartNanos = System.nanoTime();

        for (PlannedTx tx : planned) {
            futures.add(executor.submit(() -> {
                startGate.await();
                long t0 = System.nanoTime();
                try {
                    TransferResult res = transferService.execute(
                            new TransferCommand(tx.key(), tx.fromId(), tx.toId(), tx.amount(), "USD"));
                    return new ExecRecord(res, System.nanoTime() - t0, null);
                } catch (Throwable t) {
                    return new ExecRecord(null, System.nanoTime() - t0, t);
                }
            }));
        }

        startGate.countDown();

        int completed = 0;
        int failed = 0;
        List<Long> latenciesMillis = new ArrayList<>();

        for (Future<ExecRecord> f : futures) {
            ExecRecord rec = f.get(60, TimeUnit.SECONDS);
            if (rec.error() != null) {
                fail("Unexpected error in random transfer: " + rec.error(), rec.error());
            }
            latenciesMillis.add(TimeUnit.NANOSECONDS.toMillis(rec.latencyNanos()));
            if (rec.result().status() == TransferStatus.COMPLETED) {
                completed++;
            } else {
                failed++;
            }
        }

        long wallElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallStartNanos);
        Collections.sort(latenciesMillis);

        long p50 = latenciesMillis.get((int) (latenciesMillis.size() * 0.50));
        long p95 = latenciesMillis.get((int) (latenciesMillis.size() * 0.95));
        long max = latenciesMillis.getLast();
        double throughput = (numTransfers * 1000.0) / Math.max(1, wallElapsedMillis);

        log.info("=== Random Transfers Benchmark Summary ===");
        log.info("Total Transfers: {}, Completed: {}, Business Failures: {}", numTransfers, completed, failed);
        log.info("Wall Time: {} ms, Throughput: {} tx/sec", wallElapsedMillis, String.format("%.2f", throughput));
        log.info("Latency: p50={} ms, p95={} ms, max={} ms", p50, p95, max);

        long actualTotalMoney = 0;
        for (Account a : accounts) {
            Account ra = accountRepository.findById(a.getId()).orElseThrow();
            actualTotalMoney += ra.getBalanceMinor();
        }
        assertThat(actualTotalMoney).isEqualTo(expectedTotalMoney);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
    }

    // ── 5d. sameKeySameRequestRace ────────────────────────────────────────────

    @Test
    @DisplayName("5d: 20 threads with one idempotency key and identical payload: exactly 1 non-replayed, 19 replayed")
    void sameKeySameRequestRace() throws Exception {
        Account from = accountService.openAccount("SRC-IDM-" + UUID.randomUUID(), "USD", 5000L);
        Account to   = accountService.openAccount("DST-IDM-" + UUID.randomUUID(), "USD", 1000L);
        String key = uniqueKey();
        long amount = 500L;

        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return transferService.execute(new TransferCommand(key, from.getId(), to.getId(), amount, "USD"));
            }));
        }

        startGate.countDown();

        int nonReplayed = 0;
        int replayed = 0;
        UUID expectedTransferId = null;

        for (Future<TransferResult> f : futures) {
            TransferResult res = f.get(30, TimeUnit.SECONDS);
            assertThat(res.status()).isEqualTo(TransferStatus.COMPLETED);
            if (expectedTransferId == null) {
                expectedTransferId = res.transferId();
            } else {
                assertThat(res.transferId()).isEqualTo(expectedTransferId);
            }

            if (res.replayed()) {
                replayed++;
            } else {
                nonReplayed++;
            }
        }

        assertThat(nonReplayed).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);

        Account reFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account reTo   = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(reFrom.getBalanceMinor()).isEqualTo(4500L);
        assertThat(reTo.getBalanceMinor()).isEqualTo(1500L);

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(expectedTransferId);
        assertThat(entries).hasSize(2);
    }

    // ── 5e. sameKeyDifferentPayloadRace ───────────────────────────────────────

    @Test
    @DisplayName("5e: 10 threads with one key, half payload X and half payload Y: 1 transfer stored, losers get conflict, same-payload losers replay")
    void sameKeyDifferentPayloadRace() throws Exception {
        Account from = accountService.openAccount("SRC-DIFF-" + UUID.randomUUID(), "USD", 10_000L);
        Account toX  = accountService.openAccount("TOX-" + UUID.randomUUID(), "USD", 1000L);
        Account toY  = accountService.openAccount("TOY-" + UUID.randomUUID(), "USD", 1000L);
        String key = uniqueKey();

        int totalThreads = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        record RaceResult(TransferResult result, Throwable error, String payload) {}
        List<Future<RaceResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            final boolean isX = (i % 2 == 0);
            UUID destId = isX ? toX.getId() : toY.getId();
            long amt = isX ? 100L : 200L;
            String label = isX ? "X" : "Y";

            futures.add(executor.submit(() -> {
                startGate.await();
                try {
                    TransferResult res = transferService.execute(
                            new TransferCommand(key, from.getId(), destId, amt, "USD"));
                    return new RaceResult(res, null, label);
                } catch (Throwable t) {
                    return new RaceResult(null, t, label);
                }
            }));
        }

        startGate.countDown();

        int conflictCount = 0;
        int winnerCount = 0;
        int replayCount = 0;
        String winningPayload = null;

        for (Future<RaceResult> f : futures) {
            RaceResult r = f.get(30, TimeUnit.SECONDS);
            if (r.error() != null) {
                assertThat(r.error()).isInstanceOf(IdempotencyConflictException.class);
                conflictCount++;
            } else {
                assertThat(r.result().status()).isEqualTo(TransferStatus.COMPLETED);
                if (!r.result().replayed()) {
                    winnerCount++;
                    winningPayload = r.payload();
                } else {
                    replayCount++;
                }
            }
        }

        assertThat(winnerCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(5);
        assertThat(replayCount).isEqualTo(4);

        long winningAmount = "X".equals(winningPayload) ? 100L : 200L;
        Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account reloadedToX  = accountRepository.findById(toX.getId()).orElseThrow();
        Account reloadedToY  = accountRepository.findById(toY.getId()).orElseThrow();

        assertThat(reloadedFrom.getBalanceMinor()).isEqualTo(10_000L - winningAmount);
        if ("X".equals(winningPayload)) {
            assertThat(reloadedToX.getBalanceMinor()).isEqualTo(1000L + winningAmount);
            assertThat(reloadedToY.getBalanceMinor()).isEqualTo(1000L);
        } else {
            assertThat(reloadedToY.getBalanceMinor()).isEqualTo(1000L + winningAmount);
            assertThat(reloadedToX.getBalanceMinor()).isEqualTo(1000L);
        }

        // Exactly one transfer row for the key in DB
        Transfer stored = transferRepository.findByIdempotencyKey(key).orElseThrow();
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransferId(stored.getId());
        assertThat(entries).hasSize(2);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
    }

    // ── 5f. concurrentDepositsSameCurrency ────────────────────────────────────

    @Test
    @DisplayName("5f: 50 concurrent openAccount with deposit for one currency: all succeed, funding account = -total, consistent")
    void concurrentDepositsSameCurrency() throws Exception {
        String currency = "USD";
        int count = 50;
        long depositPerAccount = 1000L;

        Account initialFunding = accountService.getOrCreateFundingAccount(currency);
        long initialFundingBalance = initialFunding.getBalanceMinor();
        entityManager.clear();

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Account>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startGate.await();
                return accountService.openAccount("CONC-DEP-" + idx + "-" + UUID.randomUUID(), currency, depositPerAccount);
            }));
        }

        startGate.countDown();

        List<Account> created = new ArrayList<>();
        for (Future<Account> f : futures) {
            try {
                Account acc = f.get(30, TimeUnit.SECONDS);
                created.add(acc);
            } catch (ExecutionException e) {
                fail("Concurrent openAccount with deposit failed: " + e.getCause(), e.getCause());
            }
        }

        assertThat(created).hasSize(count);
        for (Account a : created) {
            Account ra = accountRepository.findById(a.getId()).orElseThrow();
            assertThat(ra.getBalanceMinor()).isEqualTo(depositPerAccount);
        }

        entityManager.clear();
        Account fundingAccount = accountRepository.findById(initialFunding.getId()).orElseThrow();
        // Funding balance must be negative and change must equal exactly -(count * depositPerAccount)
        assertThat(fundingAccount.getBalanceMinor()).isNegative();
        assertThat(fundingAccount.getBalanceMinor() - initialFundingBalance).isEqualTo(-(count * depositPerAccount));

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
    }

    // ── 5g. concurrentDepositsToOneAccount ────────────────────────────────────

    @Test
    @DisplayName("5g: 30 concurrent deposits to same account while sending transfers: no exceptions, final balance correct")
    void concurrentDepositsToOneAccount() throws Exception {
        Account target = accountService.openAccount("TARGET-" + UUID.randomUUID(), "USD", 5000L);
        Account sink   = accountService.openAccount("SINK-" + UUID.randomUUID(), "USD", 0L);

        int depositThreads = 30;
        long depositAmount = 200L; // 30 * 200 = 6000 deposited
        int transferThreads = 10;
        long transferAmount = 100L; // 10 * 100 = 1000 transferred out

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < depositThreads; i++) {
            String depKey = uniqueKey();
            futures.add(executor.submit(() -> {
                startGate.await();
                return accountService.deposit(target.getId(), depositAmount, depKey);
            }));
        }

        for (int i = 0; i < transferThreads; i++) {
            String txKey = uniqueKey();
            futures.add(executor.submit(() -> {
                startGate.await();
                return transferService.execute(new TransferCommand(txKey, target.getId(), sink.getId(), transferAmount, "USD"));
            }));
        }

        startGate.countDown();

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                fail("Unexpected failure during concurrent deposit/transfer: " + e.getCause(), e.getCause());
            }
        }

        Account reloadedTarget = accountRepository.findById(target.getId()).orElseThrow();
        // 5000 initial + 6000 deposits - 1000 transfers = 10_000 expected
        assertThat(reloadedTarget.getBalanceMinor()).isEqualTo(10_000L);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isTrue();
        assertThat(report.problems()).isEmpty();
    }

    // ── 5h. reconciliationUnderLoad ───────────────────────────────────────────

    @Test
    @DisplayName("5h: reconciliationUnderLoad: reconcile() called every 50ms during concurrent transfers remains consistent")
    void reconciliationUnderLoad() throws Exception {
        Account from = accountService.openAccount("REC-SRC-" + UUID.randomUUID(), "USD", 50_000L);
        int numDest = 100;
        List<Account> destinations = new ArrayList<>();
        for (int i = 0; i < numDest; i++) {
            destinations.add(accountService.openAccount("REC-DST-" + i + "-" + UUID.randomUUID(), "USD", 0L));
        }

        AtomicBoolean running = new AtomicBoolean(true);
        List<String> reconciliationFailures = new CopyOnWriteArrayList<>();

        // Background reconciliation worker checking every 50ms
        Future<?> reconcilerFuture = executor.submit(() -> {
            while (running.get()) {
                try {
                    ReconciliationReport rep = reconciliationService.reconcile();
                    if (!rep.consistent()) {
                        reconciliationFailures.addAll(rep.problems());
                    }
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TransferResult>> transferFutures = new ArrayList<>();

        for (int i = 0; i < numDest; i++) {
            Account dest = destinations.get(i);
            String key = uniqueKey();
            transferFutures.add(executor.submit(() -> {
                startGate.await();
                return transferService.execute(new TransferCommand(key, from.getId(), dest.getId(), 100L, "USD"));
            }));
        }

        startGate.countDown();

        for (Future<TransferResult> f : transferFutures) {
            f.get(30, TimeUnit.SECONDS);
        }

        running.set(false);
        reconcilerFuture.get(10, TimeUnit.SECONDS);

        assertThat(reconciliationFailures).isEmpty();

        ReconciliationReport finalReport = reconciliationService.reconcile();
        assertThat(finalReport.consistent()).isTrue();
        assertThat(finalReport.problems()).isEmpty();
    }
}

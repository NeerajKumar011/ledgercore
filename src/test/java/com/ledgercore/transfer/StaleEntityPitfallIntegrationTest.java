package com.ledgercore.transfer;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic integration test documenting WHY Account entities must never be loaded
 * into Hibernate's persistence context before executing pessimistic locking queries (findByIdForUpdate).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StaleEntityPitfallIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Pitfall: loading entity before locking causes ObjectOptimisticLockingFailureException on findByIdForUpdate")
    void whenAccountLoadedBeforeLocking_findByIdForUpdateThrowsOptimisticLockingFailure() throws Exception {
        Account initial = accountService.openAccount("PITFALL-" + UUID.randomUUID(), "USD", 1000L);
        UUID accountId = initial.getId();

        CountDownLatch thread1LoadedLatch = new CountDownLatch(1);
        CountDownLatch thread2CommittedLatch = new CountDownLatch(1);

        // Thread 2: waits for Thread 1 to load the entity, then updates the account in DB and commits
        Future<?> thread2Future = executor.submit(() -> {
            try {
                assertThat(thread1LoadedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                accountService.deposit(accountId, 4000L, "DEP-" + UUID.randomUUID());
                thread2CommittedLatch.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 1: loads entity into persistence context, waits for Thread 2 to commit, then calls findByIdForUpdate.
        // Observed Hibernate 7 behavior:
        // When findByIdForUpdate executes its SQL query, the row returned from PostgreSQL contains the newer @Version
        // committed by Thread 2. Because an entity with the same ID and older version is already held in the
        // persistence context (first-level cache), Spring/Hibernate detects the version conflict and immediately throws
        // ObjectOptimisticLockingFailureException: "Query result contains conflicting version of entity already held in persistence context".
        TransactionTemplate tt1 = new TransactionTemplate(transactionManager);
        tt1.execute(status -> {
            try {
                Account loaded1 = accountRepository.findById(accountId).orElseThrow();
                assertThat(loaded1.getBalanceMinor()).isEqualTo(1000L);

                thread1LoadedLatch.countDown();
                assertThat(thread2CommittedLatch.await(5, TimeUnit.SECONDS)).isTrue();
                thread2Future.get(5, TimeUnit.SECONDS);

                assertThatThrownBy(() -> accountRepository.findByIdForUpdate(accountId))
                        .isInstanceOf(ObjectOptimisticLockingFailureException.class)
                        .hasMessageContaining("Query result contains conflicting version of entity already held in persistence context");

                status.setRollbackOnly();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Test
    @DisplayName("Opposite case: when NO account was loaded first, findByIdForUpdate returns fresh committed state")
    void whenAccountNotLoadedBeforeLocking_findByIdForUpdateReturnsFreshCommittedState() throws Exception {
        Account initial = accountService.openAccount("FRESH-" + UUID.randomUUID(), "USD", 1000L);
        UUID accountId = initial.getId();

        CountDownLatch startGate = new CountDownLatch(1);

        // Thread 2: updates account in DB and commits
        Future<?> thread2Future = executor.submit(() -> {
            try {
                startGate.await(5, TimeUnit.SECONDS);
                accountService.deposit(accountId, 4000L, "DEP-" + UUID.randomUUID());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Trigger thread 2 and wait for its transaction to commit
        startGate.countDown();
        thread2Future.get(5, TimeUnit.SECONDS);

        // Transaction 1: did NOT load account before locking. Calling findByIdForUpdate returns the fresh state.
        TransactionTemplate tt1 = new TransactionTemplate(transactionManager);
        tt1.execute(status -> {
            Account lockedAccount = accountRepository.findByIdForUpdate(accountId).orElseThrow();
            assertThat(lockedAccount.getBalanceMinor()).isEqualTo(5000L);
            return null;
        });
    }
}

package com.ledgercore.transfer;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "ledgercore.transfer.lock-timeout=500ms"
})
class TransferLockTimeoutIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TransferLockTimeoutIntegrationTest.class);

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

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

    private String uniqueKey() {
        return "TIMEOUT-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("5i: lockTimeout: holding lock triggers TransferBusyException within bounded time (< 3s); succeeds after release")
    void lockTimeout() throws Exception {
        Account account = accountService.openAccount("LOCKED-" + UUID.randomUUID(), "USD", 10_000L);
        Account recipient = accountService.openAccount("RECP-" + UUID.randomUUID(), "USD", 0L);

        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);

        // Transaction 1: acquires and holds pessimistic lock on account
        Future<?> lockHolderFuture = executor.submit(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.execute(status -> {
                accountRepository.findByIdForUpdate(account.getId());
                lockAcquiredLatch.countDown();
                try {
                    releaseLockLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        // Wait until lock holder has acquired the lock
        assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Transaction 2: should attempt transfer, hit lock timeout (500ms), and throw TransferBusyException in < 3s
        String timeoutKey = uniqueKey();
        long t0 = System.currentTimeMillis();

        assertThatThrownBy(() -> {
            transferService.execute(new TransferCommand(timeoutKey, account.getId(), recipient.getId(), 500L, "USD"));
        })
                .isInstanceOf(TransferBusyException.class);

        long elapsed = System.currentTimeMillis() - t0;
        log.info("Lock timeout elapsed in {} ms (expected around 500ms, strictly < 3000ms)", elapsed);
        // Configured timeout is 500ms; must arrive in less than about 3 seconds
        assertThat(elapsed).isLessThan(3000L);

        // Verify nothing stored for the timed-out transfer
        assertThat(transferRepository.findByIdempotencyKey(timeoutKey)).isEmpty();
        Account reloadedBeforeRelease = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedBeforeRelease.getBalanceMinor()).isEqualTo(10_000L);

        // Release the lock
        releaseLockLatch.countDown();
        lockHolderFuture.get(5, TimeUnit.SECONDS);

        // After release, transfer with new key succeeds cleanly
        String successKey = uniqueKey();
        TransferResult successRes = transferService.execute(
                new TransferCommand(successKey, account.getId(), recipient.getId(), 500L, "USD"));
        assertThat(successRes.status()).isEqualTo(TransferStatus.COMPLETED);

        Account reloadedAfterSuccess = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedAfterSuccess.getBalanceMinor()).isEqualTo(9500L);
    }
}

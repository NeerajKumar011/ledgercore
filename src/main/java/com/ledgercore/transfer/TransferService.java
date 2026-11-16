package com.ledgercore.transfer;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountKind;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountStatus;
import com.ledgercore.ledger.EntryDirection;
import com.ledgercore.ledger.LedgerEntry;
import com.ledgercore.ledger.LedgerEntryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Core transfer engine: validates commands, enforces idempotency, acquires
 * pessimistic locks in a fixed order, executes double-entry bookkeeping, and
 * returns a {@link TransferResult}.
 *
 * <p>Supports customer-to-customer transfers via {@link #execute(TransferCommand)}
 * and initial system funding via {@link #executeFunding(TransferCommand)}.
 *
 * <p><b>Business failures</b> (insufficient funds, frozen account, currency
 * mismatch, system account in public transfer) are recorded as {@code FAILED}
 * transfers and returned as normal results — they do <em>not</em> throw exceptions
 * and do <em>not</em> roll back the transaction.
 */
@Service
public class TransferService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

    private final AccountRepository      accountRepository;
    private final TransferRepository     transferRepository;
    private final LedgerEntryRepository  ledgerEntryRepository;
    private final EntityManager          entityManager;
    private final String                 lockTimeout;

    private enum TransferMode {
        CUSTOMER_TO_CUSTOMER,
        FUNDING
    }

    public TransferService(AccountRepository accountRepository,
                           TransferRepository transferRepository,
                           LedgerEntryRepository ledgerEntryRepository) {
        this(accountRepository, transferRepository, ledgerEntryRepository, null, "3s");
    }

    @Autowired
    public TransferService(AccountRepository accountRepository,
                           TransferRepository transferRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           EntityManager entityManager,
                           @Value("${ledgercore.transfer.lock-timeout:3s}") String lockTimeout) {
        this.accountRepository     = accountRepository;
        this.transferRepository    = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.entityManager         = entityManager;
        this.lockTimeout           = (lockTimeout != null && !lockTimeout.isBlank()) ? lockTimeout : "3s";
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Executes a fund transfer between two CUSTOMER accounts.
     * If either account is a SYSTEM account, the transfer is recorded as FAILED.
     *
     * @throws InvalidTransferRequestException if input is structurally invalid
     * @throws AccountNotFoundException        if either account does not exist
     * @throws IdempotencyConflictException    if the key was reused with a different payload
     */
    @Transactional
    public TransferResult execute(TransferCommand command) {
        return executeInternal(command, TransferMode.CUSTOMER_TO_CUSTOMER);
    }

    /**
     * Executes a funding transfer from a SYSTEM account to a CUSTOMER account.
     * Requires the source account to be the SYSTEM funding account of that currency.
     *
     * @throws InvalidTransferRequestException if input is structurally invalid
     * @throws AccountNotFoundException        if either account does not exist
     * @throws IdempotencyConflictException    if the key was reused with a different payload
     */
    @Transactional
    public TransferResult executeFunding(TransferCommand command) {
        return executeInternal(command, TransferMode.FUNDING);
    }

    // =========================================================================
    // Internal Workflow
    // =========================================================================

    private TransferResult executeInternal(TransferCommand command, TransferMode mode) {
        // ── Step 1: Validate input ────────────────────────────────────────────
        validate(command);

        // ── Step 1b: Bounded lock timeout ────────────────────────────────────
        applyLockTimeout();

        // ── Step 2: Compute request hash ─────────────────────────────────────
        String requestHash = sha256Hex(
                "v1|" + command.fromAccountId()
                + "|" + command.toAccountId()
                + "|" + command.amountMinor()
                + "|" + command.currency());

        // ── Step 3: Idempotency check (pre-lock) ─────────────────────────────
        Optional<Transfer> existingOpt =
                transferRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existingOpt.isPresent()) {
            return replayOrConflict(existingOpt.get(), requestHash, command.idempotencyKey());
        }

        try {
            // ── Step 4: Lock both accounts in ascending UUID order ────────────────
            UUID firstId  = min(command.fromAccountId(), command.toAccountId());
            UUID secondId = max(command.fromAccountId(), command.toAccountId());

            Account first  = accountRepository.findByIdForUpdate(firstId)
                    .orElseThrow(() -> new AccountNotFoundException(firstId));
            Account second = accountRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> new AccountNotFoundException(secondId));

            // Map locked accounts back to their semantic roles
            Account from = command.fromAccountId().equals(firstId) ? first : second;
            Account to   = command.toAccountId().equals(firstId)   ? first : second;

            // ── Step 5: Claim idempotency key (atomic INSERT ... ON CONFLICT) ─────
            UUID    transferId = UUID.randomUUID();
            Instant now        = Instant.now();

            int inserted = transferRepository.insertPendingIfAbsent(
                    transferId,
                    command.idempotencyKey(),
                    requestHash,
                    command.fromAccountId(),
                    command.toAccountId(),
                    command.amountMinor(),
                    command.currency(),
                    now);

            if (inserted == 0) {
                // Another concurrent transaction won the race; re-read and replay/conflict
                Transfer concurrent = transferRepository
                        .findByIdempotencyKey(command.idempotencyKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Race: key inserted 0 rows but not found on re-read: "
                                        + command.idempotencyKey()));
                return replayOrConflict(concurrent, requestHash, command.idempotencyKey());
            }

            // Reload the transfer entity that was inserted via native SQL,
            // so that JPA can track and flush updates to it.
            Transfer transfer = transferRepository.findById(transferId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Transfer row vanished immediately after insert: " + transferId));

            // ── Step 6: Business rules ────────────────────────────────────────────
            String failureReason = checkBusinessRules(from, to, command, mode);
            if (failureReason != null) {
                transfer.markFailed(failureReason);
                return new TransferResult(transfer.getId(), TransferStatus.FAILED,
                        failureReason, false);
            }

            // ── Step 7: Debit source, credit destination, insert ledger entries ───
            from.debit(command.amountMinor());
            to.credit(command.amountMinor());

            LedgerEntry debit = LedgerEntry.create(
                    transfer.getId(), from.getId(),
                    EntryDirection.DEBIT, command.amountMinor(), command.currency());
            LedgerEntry credit = LedgerEntry.create(
                    transfer.getId(), to.getId(),
                    EntryDirection.CREDIT, command.amountMinor(), command.currency());

            ledgerEntryRepository.save(debit);
            ledgerEntryRepository.save(credit);

            transfer.markCompleted();

            return new TransferResult(transfer.getId(), TransferStatus.COMPLETED, null, false);
        } catch (Exception e) {
            if (isLockTimeout(e)) {
                throw new TransferBusyException("Lock wait timeout exceeded during transfer execution", e);
            }
            throw e;
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void validate(TransferCommand cmd) {
        if (cmd == null) {
            throw new InvalidTransferRequestException("TransferCommand must not be null");
        }
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new InvalidTransferRequestException("idempotencyKey must not be blank");
        }
        if (cmd.idempotencyKey().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidTransferRequestException(
                    "idempotencyKey exceeds maximum length of " + MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        if (cmd.fromAccountId() == null) {
            throw new InvalidTransferRequestException("fromAccountId must not be null");
        }
        if (cmd.toAccountId() == null) {
            throw new InvalidTransferRequestException("toAccountId must not be null");
        }
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw new InvalidTransferRequestException(
                    "fromAccountId and toAccountId must be different");
        }
        if (cmd.amountMinor() <= 0) {
            throw new InvalidTransferRequestException(
                    "amountMinor must be > 0, got: " + cmd.amountMinor());
        }
        if (cmd.currency() == null || !cmd.currency().matches(CURRENCY_PATTERN)) {
            throw new InvalidTransferRequestException(
                    "currency must match ^[A-Z]{3}$, got: " + cmd.currency());
        }
    }

    private TransferResult replayOrConflict(Transfer existing, String requestHash,
                                            String idempotencyKey) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return new TransferResult(
                existing.getId(),
                existing.getStatus(),
                existing.getFailureReason(),
                true);
    }

    private String checkBusinessRules(Account from, Account to, TransferCommand cmd, TransferMode mode) {
        if (mode == TransferMode.CUSTOMER_TO_CUSTOMER) {
            if (from.getKind() != AccountKind.CUSTOMER || to.getKind() != AccountKind.CUSTOMER) {
                return "Public transfers are for CUSTOMER accounts only (from="
                        + from.getKind() + ", to=" + to.getKind() + ")";
            }
        } else if (mode == TransferMode.FUNDING) {
            if (from.getKind() != AccountKind.SYSTEM) {
                return "Funding source must be a SYSTEM account (got " + from.getKind() + ")";
            }
            if (!("SYSTEM-FUNDING-" + cmd.currency()).equals(from.getAccountNumber())) {
                return "Funding source must be the system funding account for "
                        + cmd.currency() + " (got " + from.getAccountNumber() + ")";
            }
            if (to.getKind() != AccountKind.CUSTOMER) {
                return "Funding destination must be a CUSTOMER account (got " + to.getKind() + ")";
            }
        }

        if (from.getStatus() != AccountStatus.ACTIVE) {
            return "Source account " + from.getId() + " is not ACTIVE (status=" + from.getStatus() + ")";
        }
        if (to.getStatus() != AccountStatus.ACTIVE) {
            return "Destination account " + to.getId() + " is not ACTIVE (status=" + to.getStatus() + ")";
        }
        if (!from.getCurrency().trim().equalsIgnoreCase(cmd.currency())) {
            return "Source account currency " + from.getCurrency().trim()
                    + " does not match command currency " + cmd.currency();
        }
        if (!to.getCurrency().trim().equalsIgnoreCase(cmd.currency())) {
            return "Destination account currency " + to.getCurrency().trim()
                    + " does not match command currency " + cmd.currency();
        }
        if (from.getKind() == AccountKind.CUSTOMER && from.getBalanceMinor() < cmd.amountMinor()) {
            return "Insufficient funds: balance=" + from.getBalanceMinor()
                    + ", requested=" + cmd.amountMinor();
        }
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void applyLockTimeout() {
        if (entityManager != null && lockTimeout != null && !lockTimeout.isBlank()) {
            entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                    .setParameter("timeout", lockTimeout)
                    .getSingleResult();
        }
    }

    private boolean isLockTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlEx) {
                if ("55P03".equals(sqlEx.getSQLState())) {
                    return true;
                }
            }
            if (current.getMessage() != null && current.getMessage().toLowerCase().contains("lock timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static UUID min(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static UUID max(UUID a, UUID b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}

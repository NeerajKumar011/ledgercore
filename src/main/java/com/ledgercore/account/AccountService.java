package com.ledgercore.account;

import com.ledgercore.transfer.AccountNotFoundException;
import com.ledgercore.transfer.TransferCommand;
import com.ledgercore.transfer.TransferResult;
import com.ledgercore.transfer.TransferService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Service managing customer account lifecycle and system funding accounts.
 */
@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransferService   transferService;
    private final EntityManager     entityManager;

    public AccountService(AccountRepository accountRepository,
                          TransferService transferService,
                          EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.transferService   = transferService;
        this.entityManager     = entityManager;
    }

    /**
     * Retrieves or creates the unique SYSTEM funding account for the specified currency.
     * Race-safe across concurrent threads using atomic ON CONFLICT insert and re-read.
     */
    public Account getOrCreateFundingAccount(String currency) {
        UUID id = getOrCreateFundingAccountId(currency);
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to retrieve system funding account: " + currency));
    }

    /**
     * Retrieves or creates the unique SYSTEM funding account ID for the specified currency
     * without loading the entity into the persistence context.
     */
    public UUID getOrCreateFundingAccountId(String currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        String formattedCurrency = currency.trim().toUpperCase();
        String accountNumber = "SYSTEM-FUNDING-" + formattedCurrency;

        return accountRepository.findIdByAccountNumber(accountNumber)
                .orElseGet(() -> {
                    accountRepository.insertSystemFundingIfAbsent(
                            UUID.randomUUID(),
                            accountNumber,
                            formattedCurrency,
                            Instant.now());
                    return accountRepository.findIdByAccountNumber(accountNumber)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Failed to retrieve system funding account id after insertion: " + accountNumber));
                });
    }

    /**
     * Opens a new CUSTOMER account with a generated account number and optional initial deposit.
     */
    public Account openAccount(String ownerRef, String currency, long initialDepositMinor) {
        return openAccount(ownerRef, currency, initialDepositMinor, null);
    }

    public Account openAccount(String ownerRef, String currency, long initialDepositMinor, UUID ownerUserId) {
        String accountNumber = "ACC-" + UUID.randomUUID();
        return openAccount(accountNumber, ownerRef, currency, initialDepositMinor, ownerUserId);
    }

    /**
     * Opens a new CUSTOMER account with a specific account number and optional initial deposit.
     */
    public Account openAccount(String accountNumber, String ownerRef, String currency, long initialDepositMinor) {
        return openAccount(accountNumber, ownerRef, currency, initialDepositMinor, null);
    }

    public Account openAccount(String accountNumber, String ownerRef, String currency, long initialDepositMinor, UUID ownerUserId) {
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (initialDepositMinor < 0) {
            throw new IllegalArgumentException("initialDepositMinor cannot be negative: " + initialDepositMinor);
        }

        Account account = Account.create(accountNumber, ownerRef, currency, ownerUserId);
        Account saved = accountRepository.saveAndFlush(account);

        if (initialDepositMinor > 0) {
            String depositKey = "OPEN-DEP-" + saved.getId();
            UUID savedId = saved.getId();
            // Detach to ensure no Account entity is held in persistence context prior to TransferService locking
            entityManager.detach(saved);
            deposit(savedId, initialDepositMinor, depositKey);
            return accountRepository.findById(savedId).orElseThrow();
        }

        return saved;
    }

    /**
     * Deposits funds into a customer account via a funding transfer from the system account.
     * Uses projection queries to ensure zero Account entities are loaded prior to
     * TransferService pessimistic row locking.
     *
     * @param accountId target customer account
     * @param amountMinor amount to deposit in minor units (must be > 0)
     * @param idempotencyKey deduplication key for this deposit
     * @return TransferResult of the funding transfer
     */
    public TransferResult deposit(UUID accountId, long amountMinor, String idempotencyKey) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        String currency = accountRepository.findCurrencyById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        UUID fundingAccountId = getOrCreateFundingAccountId(currency);

        TransferCommand command = new TransferCommand(
                idempotencyKey,
                fundingAccountId,
                accountId,
                amountMinor,
                currency);

        return transferService.executeFunding(command);
    }
}

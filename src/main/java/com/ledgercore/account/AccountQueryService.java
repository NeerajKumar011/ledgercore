package com.ledgercore.account;

import com.ledgercore.api.dto.AccountListResponse;
import com.ledgercore.api.dto.AccountResponse;
import com.ledgercore.api.dto.StatementEntryResponse;
import com.ledgercore.api.dto.StatementResponse;
import com.ledgercore.ledger.LedgerEntry;
import com.ledgercore.ledger.LedgerEntryRepository;
import com.ledgercore.security.AccountOwnershipPolicy;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.transfer.AccountNotFoundException;
import com.ledgercore.transfer.InvalidTransferRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only query service for customer accounts and ledger statements.
 * Executes within read-only transactions; conceals SYSTEM accounts behind 404.
 */
@Service
@Transactional(readOnly = true)
public class AccountQueryService {

    private static final int MAX_STATEMENT_SIZE = 100;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountOwnershipPolicy ownershipPolicy;

    public AccountQueryService(AccountRepository accountRepository,
                               LedgerEntryRepository ledgerEntryRepository,
                               AccountOwnershipPolicy ownershipPolicy) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ownershipPolicy = ownershipPolicy;
    }

    /**
     * Retrieves account details for a CUSTOMER account.
     */
    public AccountResponse getAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .filter(a -> a.getKind() == AccountKind.CUSTOMER)
                .orElseThrow(() -> new AccountNotFoundException(id));

        return toResponse(account);
    }

    /**
     * Retrieves account details for a caller, enforcing ownership policy.
     */
    public AccountResponse getAccount(UUID id, AuthenticatedUser caller) {
        Account account = ownershipPolicy.verifyAccountAccess(id, caller);
        return toResponse(account);
    }

    /**
     * Retrieves paginated accounts owned by the caller (Slice-based, size cap 100).
     */
    public AccountListResponse getCallerAccounts(UUID ownerUserId, int page, int size) {
        if (size > MAX_STATEMENT_SIZE) {
            throw new InvalidTransferRequestException("size exceeds maximum allowed limit of " + MAX_STATEMENT_SIZE);
        }
        if (size < 1) {
            throw new InvalidTransferRequestException("size must be at least 1");
        }
        if (page < 0) {
            throw new InvalidTransferRequestException("page must be >= 0");
        }

        Slice<Account> slice = accountRepository.findByOwnerUserId(ownerUserId, PageRequest.of(page, size));
        List<AccountResponse> items = slice.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new AccountListResponse(items, page, size, slice.hasNext());
    }

    /**
     * Retrieves paginated ledger statement entries for a CUSTOMER account, newest first.
     */
    public StatementResponse getStatement(UUID id, int page, int size) {
        if (size > MAX_STATEMENT_SIZE) {
            throw new InvalidTransferRequestException("size exceeds maximum allowed limit of " + MAX_STATEMENT_SIZE);
        }
        if (size < 1) {
            throw new InvalidTransferRequestException("size must be at least 1");
        }
        if (page < 0) {
            throw new InvalidTransferRequestException("page must be >= 0");
        }

        // Verify account exists and is not a SYSTEM account
        boolean existsCustomer = accountRepository.findById(id)
                .filter(a -> a.getKind() == AccountKind.CUSTOMER)
                .isPresent();
        if (!existsCustomer) {
            throw new AccountNotFoundException(id);
        }

        Slice<LedgerEntry> slice = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDescIdDesc(
                id, PageRequest.of(page, size));

        List<StatementEntryResponse> items = slice.getContent().stream()
                .map(this::toEntryResponse)
                .toList();

        return new StatementResponse(items, page, size, slice.hasNext());
    }

    /**
     * Retrieves paginated ledger statement entries with caller ownership verification.
     */
    public StatementResponse getStatement(UUID id, int page, int size, AuthenticatedUser caller) {
        ownershipPolicy.verifyAccountAccess(id, caller);
        return getStatement(id, page, size);
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(
                a.getId(),
                a.getAccountNumber(),
                a.getOwnerRef(),
                a.getCurrency(),
                a.getStatus(),
                a.getBalanceMinor(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }

    private StatementEntryResponse toEntryResponse(LedgerEntry e) {
        return new StatementEntryResponse(
                e.getId(),
                e.getTransferId(),
                e.getDirection(),
                e.getAmountMinor(),
                e.getCurrency(),
                e.getCreatedAt()
        );
    }
}

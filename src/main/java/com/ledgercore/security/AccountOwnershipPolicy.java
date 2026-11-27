package com.ledgercore.security;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountKind;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.transfer.AccountNotFoundException;
import com.ledgercore.transfer.Transfer;
import com.ledgercore.transfer.TransferNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Reusable service-layer security policy enforcing account and transfer ownership.
 *
 * <p>Rules:
 * <ul>
 *   <li>Customers can only read and use their own accounts.</li>
 *   <li>Non-owners receive 404 (AccountNotFoundException), identical to missing accounts (no existence leak).</li>
 *   <li>ADMIN may read any non-SYSTEM account.</li>
 *   <li>SYSTEM accounts are completely concealed behind 404 from all callers (including ADMIN).</li>
 *   <li>Transfer source must be owned by the caller. Destination may be any non-SYSTEM account.</li>
 *   <li>Transfer details (GET /transfers/{id}) can only be read if the caller owns source or destination, or is ADMIN.</li>
 * </ul>
 */
@Component
public class AccountOwnershipPolicy {

    private final AccountRepository accountRepository;

    public AccountOwnershipPolicy(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Verifies that the caller has permission to view an account.
     */
    public Account verifyAccountAccess(UUID accountId, AuthenticatedUser caller) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (account.getKind() == AccountKind.SYSTEM) {
            throw new AccountNotFoundException(accountId);
        }

        if (caller.isAdmin()) {
            return account;
        }

        if (!Objects.equals(account.getOwnerUserId(), caller.userId())) {
            throw new AccountNotFoundException(accountId);
        }

        return account;
    }

    /**
     * Verifies that the caller owns the source account for a transfer.
     */
    public Account verifyTransferSourceAccess(UUID sourceAccountId, AuthenticatedUser caller) {
        Account account = accountRepository.findById(sourceAccountId)
                .orElseThrow(() -> new AccountNotFoundException(sourceAccountId));

        if (account.getKind() == AccountKind.SYSTEM) {
            throw new AccountNotFoundException(sourceAccountId);
        }

        if (!Objects.equals(account.getOwnerUserId(), caller.userId())) {
            throw new AccountNotFoundException(sourceAccountId);
        }

        return account;
    }

    /**
     * Verifies that the destination account is a valid non-SYSTEM account.
     */
    public Account verifyTransferDestination(UUID destinationAccountId) {
        Account account = accountRepository.findById(destinationAccountId)
                .orElseThrow(() -> new AccountNotFoundException(destinationAccountId));

        if (account.getKind() == AccountKind.SYSTEM) {
            throw new AccountNotFoundException(destinationAccountId);
        }

        return account;
    }

    /**
     * Verifies access to transfer details.
     */
    public void verifyTransferAccess(Transfer transfer, AuthenticatedUser caller) {
        if (caller.isAdmin()) {
            return;
        }

        UUID callerId = caller.userId();
        UUID fromOwner = accountRepository.findOwnerUserIdById(transfer.getFromAccountId()).orElse(null);
        UUID toOwner = accountRepository.findOwnerUserIdById(transfer.getToAccountId()).orElse(null);

        if (!Objects.equals(callerId, fromOwner) && !Objects.equals(callerId, toOwner)) {
            throw new TransferNotFoundException(transfer.getId());
        }
    }

    /**
     * Verifies permission to deposit into an account.
     */
    public void verifyDepositAccess(Account account, AuthenticatedUser caller, boolean selfServiceEnabled) {
        if (account.getKind() == AccountKind.SYSTEM) {
            throw new AccountNotFoundException(account.getId());
        }

        if (caller.isAdmin()) {
            return;
        }

        if (!selfServiceEnabled) {
            throw new AccessDeniedException("Deposits require ADMIN role unless self-service deposits are enabled");
        }

        if (!Objects.equals(account.getOwnerUserId(), caller.userId())) {
            throw new AccountNotFoundException(account.getId());
        }
    }
}

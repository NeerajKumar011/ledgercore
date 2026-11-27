package com.ledgercore.transfer;

import com.ledgercore.account.AccountKind;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.api.dto.TransferResponse;
import com.ledgercore.security.AccountOwnershipPolicy;
import com.ledgercore.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only query service for transfers.
 */
@Service
@Transactional(readOnly = true)
public class TransferQueryService {

    private final TransferRepository transferRepository;
    private final AccountOwnershipPolicy ownershipPolicy;
    private final AccountRepository accountRepository;

    public TransferQueryService(TransferRepository transferRepository,
                                AccountOwnershipPolicy ownershipPolicy,
                                AccountRepository accountRepository) {
        this.transferRepository = transferRepository;
        this.ownershipPolicy = ownershipPolicy;
        this.accountRepository = accountRepository;
    }

    /**
     * Retrieves transfer details by ID.
     */
    public TransferResponse getTransfer(UUID id) {
        Transfer t = transferRepository.findById(id)
                .orElseThrow(() -> new TransferNotFoundException(id));

        return toResponse(t);
    }

    /**
     * Retrieves transfer details with caller ownership verification.
     */
    public TransferResponse getTransfer(UUID id, AuthenticatedUser caller) {
        Transfer t = transferRepository.findById(id)
                .orElseThrow(() -> new TransferNotFoundException(id));
        ownershipPolicy.verifyTransferAccess(t, caller);
        return toResponse(t);
    }

    public TransferResponse toResponse(Transfer t) {
        UUID fromId = t.getFromAccountId();
        if (fromId != null && accountRepository.findKindById(fromId).orElse(null) == AccountKind.SYSTEM) {
            fromId = null;
        }
        UUID toId = t.getToAccountId();
        if (toId != null && accountRepository.findKindById(toId).orElse(null) == AccountKind.SYSTEM) {
            toId = null;
        }

        return new TransferResponse(
                t.getId(),
                t.getStatus(),
                t.getFailureReason(),
                fromId,
                toId,
                t.getAmountMinor(),
                t.getCurrency(),
                t.getCreatedAt()
        );
    }
}

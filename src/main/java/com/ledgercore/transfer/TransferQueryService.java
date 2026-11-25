package com.ledgercore.transfer;

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

    public TransferQueryService(TransferRepository transferRepository,
                                AccountOwnershipPolicy ownershipPolicy) {
        this.transferRepository = transferRepository;
        this.ownershipPolicy = ownershipPolicy;
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
        return new TransferResponse(
                t.getId(),
                t.getStatus(),
                t.getFailureReason(),
                t.getFromAccountId(),
                t.getToAccountId(),
                t.getAmountMinor(),
                t.getCurrency(),
                t.getCreatedAt()
        );
    }
}

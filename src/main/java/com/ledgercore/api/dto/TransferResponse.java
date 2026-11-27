package com.ledgercore.api.dto;

import com.ledgercore.transfer.TransferStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        TransferStatus status,
        String failureReason,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        Instant createdAt
) {}

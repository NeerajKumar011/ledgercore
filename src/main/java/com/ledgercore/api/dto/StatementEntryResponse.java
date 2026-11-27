package com.ledgercore.api.dto;

import com.ledgercore.ledger.EntryDirection;

import java.time.Instant;
import java.util.UUID;

public record StatementEntryResponse(
        UUID entryId,
        UUID transferId,
        EntryDirection direction,
        long amountMinor,
        String currency,
        Instant createdAt
) {}

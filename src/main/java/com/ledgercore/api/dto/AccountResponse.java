package com.ledgercore.api.dto;

import com.ledgercore.account.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        String ownerRef,
        String currency,
        AccountStatus status,
        long balanceMinor,
        Instant createdAt,
        Instant updatedAt
) {}

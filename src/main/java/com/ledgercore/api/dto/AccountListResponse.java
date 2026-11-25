package com.ledgercore.api.dto;

import java.util.List;

public record AccountListResponse(
        List<AccountResponse> items,
        int page,
        int size,
        boolean hasNext
) {}

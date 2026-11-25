package com.ledgercore.api.dto;

import java.util.List;

public record StatementResponse(
        List<StatementEntryResponse> items,
        int page,
        int size,
        boolean hasNext
) {}

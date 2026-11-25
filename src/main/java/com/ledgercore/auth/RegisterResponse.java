package com.ledgercore.auth;

import java.util.UUID;

public record RegisterResponse(
        UUID userId,
        String email,
        String role
) {}

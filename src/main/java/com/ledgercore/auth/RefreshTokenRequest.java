package com.ledgercore.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken must not be blank")
        @Size(max = 256, message = "refreshToken must not exceed 256 characters")
        String refreshToken
) {}

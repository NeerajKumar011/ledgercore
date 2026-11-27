package com.ledgercore.auth;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        long refreshExpiresInSeconds
) {
    public static AuthResponse of(String accessToken, long expiresInSeconds, String refreshToken, long refreshExpiresInSeconds) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, refreshToken, refreshExpiresInSeconds);
    }

    public static AuthResponse of(String accessToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds, null, 0L);
    }
}

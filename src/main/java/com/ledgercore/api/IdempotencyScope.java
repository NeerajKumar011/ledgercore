package com.ledgercore.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Scopes client-provided idempotency keys to individual authenticated users.
 * Produces a deterministic 64-character lowercase SHA-256 hex digest of (userId + "|" + clientKey).
 * The raw client key is never stored in the database.
 */
public final class IdempotencyScope {

    private IdempotencyScope() {}

    public static String scope(UUID callerUserId, String clientKey) {
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");
        Objects.requireNonNull(clientKey, "clientKey must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((callerUserId + "|" + clientKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}

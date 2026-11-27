package com.ledgercore.security;

import com.ledgercore.auth.InvalidPasswordException;

import java.nio.charset.StandardCharsets;

/**
 * Enforces password requirements.
 *
 * <p><b>BCrypt 72-Byte Truncation Limit:</b>
 * The BCrypt hashing algorithm natively truncates passwords at 72 bytes. Any characters
 * beyond the 72-byte boundary are silently ignored by standard BCrypt implementations, which
 * could allow passwords that differ only after the 72nd byte to authenticate interchangeably.
 * We strictly reject any password exceeding 72 UTF-8 bytes to prevent silent truncation.
 *
 * <p><b>Minimum Length:</b>
 * Passwords must be at least 12 characters to provide baseline entropy against offline brute-force.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new InvalidPasswordException(
                    "Password must be at least " + MIN_LENGTH + " characters long");
        }
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_UTF8_BYTES) {
            throw new InvalidPasswordException(
                    "Password must not exceed " + MAX_UTF8_BYTES + " UTF-8 bytes (BCrypt limit)");
        }
    }
}

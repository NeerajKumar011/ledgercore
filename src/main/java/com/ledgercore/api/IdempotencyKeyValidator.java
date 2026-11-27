package com.ledgercore.api;

import com.ledgercore.transfer.InvalidTransferRequestException;

import java.util.regex.Pattern;

/**
 * Validates Idempotency-Key headers per RFC 7230 / ASCII printable subset:
 * 1-128 characters matching ^[\x21-\x7E]+$ (no whitespace or control characters).
 */
public final class IdempotencyKeyValidator {

    private static final Pattern PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,128}$");

    private IdempotencyKeyValidator() {}

    public static void validate(String key) {
        if (key == null || !PATTERN.matcher(key).matches()) {
            throw new InvalidTransferRequestException(
                    "Idempotency-Key header is missing or invalid: must be 1-128 ASCII printable characters without whitespace");
        }
    }
}

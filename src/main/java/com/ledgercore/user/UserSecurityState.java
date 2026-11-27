package com.ledgercore.user;

/**
 * Lightweight projection of user security state for per-request JWT validation.
 */
public record UserSecurityState(boolean enabled, Role role, int tokenVersion) {}

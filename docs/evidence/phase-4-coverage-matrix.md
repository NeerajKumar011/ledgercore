# Phase 4 Test Coverage Matrix

This matrix maps every behavior specified in the Phase 4B and 4C requirements to the exact test method proving it.

| # | Behavior | Exact Test Method | Status |
|---|----------|-------------------|--------|
| 1 | **Refresh Rotation** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#refreshToken_rotationWorks` | PROVEN |
| 2 | **Reuse Kills the Family** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#refreshToken_reuseKillsFamily` | PROVEN |
| 3 | **10 Threads One Success** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#refreshToken_concurrencyRaceSingleSuccess` | PROVEN |
| 4 | **Expired Refresh** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#refreshToken_expiredReturns401` | PROVEN |
| 5 | **Disabled User's Refresh** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#refreshToken_disabledUserReturns401` | PROVEN |
| 6 | **Logout** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#logout_revokesFamily` | PROVEN |
| 7 | **Logout-All Kills Access Tokens** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#logoutAll_killsExistingAccessTokensImmediately` | PROVEN |
| 8 | **Disable Kills Access Tokens** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#disableUser_killsAccessTokenImmediately` | PROVEN |
| 9 | **Role Change Without New Token** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#roleChangeInDatabaseAppliesWithoutNewToken` | PROVEN |
| 10 | **Lockout Sequence** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#lockout_progressiveDelayAndTimingParity` | PROVEN |
| 11 | **Reset After a Success** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#lockout_progressiveDelayAndTimingParity` | PROVEN |
| 12 | **Delay Doubling** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#lockout_delayDoublingPerFailure` | PROVEN |
| 13 | **Lock State Invisible** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#lockout_progressiveDelayAndTimingParity` | PROVEN |
| 14 | **Per-IP 429 and Retry-After** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#rateLimiting_perIp429WithRetryAfter` | PROVEN |
| 15 | **X-Forwarded-For Ignored** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#rateLimiting_perIp429WithRetryAfter` | PROVEN |
| 16 | **Limiter Memory Bound** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#rateLimiting_memoryBound` | PROVEN |
| 17 | **Oversized Body 413** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#requestSizeLimit_bodyExceeding16KBReturns413` | PROVEN |
| 18 | **Security Headers** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#securityHeaders_presentOnResponses` | PROVEN |
| 19 | **Only Hashes Stored in Database** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#database_containsOnlyHashes` | PROVEN |
| 20 | **No Token or Secret in Logs** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#logs_containNoTokensOrSecrets` | PROVEN |
| 21 | **Chunked Body Over 16 KB Streams to 413** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#requestSizeLimit_chunkedStreamOver16KBReturns413` | PROVEN |
| 22 | **Separate Rate Limiting Buckets per Remote IP** | `com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest#rateLimiting_separateBucketsForDifferentRemoteAddresses` | PROVEN |
| 23 | **Refresh Token Purge / Cleanup** | `com.ledgercore.auth.RefreshTokenCleanupIntegrationTest#purgeExpiredAndRevokedTokens` | PROVEN |

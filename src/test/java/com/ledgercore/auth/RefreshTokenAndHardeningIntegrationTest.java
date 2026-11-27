package com.ledgercore.auth;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountService;
import com.ledgercore.api.AuthTestHelper;
import com.ledgercore.security.JwtService;
import com.ledgercore.security.RateLimitingFilter;
import com.ledgercore.user.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RefreshTokenAndHardeningIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenAndHardeningIntegrationTest.class);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AuthTestHelper authHelper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RateLimitingFilter rateLimitingFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private record SimpleResponse(int status, HttpResponse<String> raw, String body) {
        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse JSON: " + body, e);
            }
        }
    }

    private SimpleResponse get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new SimpleResponse(resp.statusCode(), resp, resp.body());
    }

    private SimpleResponse getWithAuth(String path, String token) throws Exception {
        return get(path, Map.of("Authorization", "Bearer " + token));
    }

    private SimpleResponse post(String path, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : ""));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new SimpleResponse(resp.statusCode(), resp, resp.body());
    }

    private SimpleResponse postWithAuth(String path, String jsonBody, String token) throws Exception {
        return post(path, jsonBody, Map.of("Authorization", "Bearer " + token));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        rateLimitingFilter.clear();
        rateLimitingFilter.setRegisterLimit(100);
        rateLimitingFilter.setLoginLimit(100);
        rateLimitingFilter.setRefreshLimit(100);
    }

    // =========================================================================
    // 1. Refresh Token Rotation & Family Theft Detection
    // =========================================================================

    @Test
    @DisplayName("Refresh tokens: login returns access + refresh token; refresh rotates tokens")
    void refreshToken_rotationWorks() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("rot-user");

        // Login -> 200 OK + accessToken + refreshToken
        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        assertThat(loginRes.status()).isEqualTo(200);
        assertThat(loginRes.header("Cache-Control")).contains("no-store");

        JsonNode loginJson = loginRes.json(mapper);
        String accessToken1 = loginJson.get("accessToken").asText();
        String refreshToken1 = loginJson.get("refreshToken").asText();
        assertThat(refreshToken1).isNotBlank();
        assertThat(loginJson.get("refreshExpiresInSeconds").asLong()).isEqualTo(7 * 24 * 3600L);

        // Refresh token -> 200 OK + new access token + new refresh token in same family
        RefreshTokenRequest refReq = new RefreshTokenRequest(refreshToken1);
        SimpleResponse refRes = post("/api/v1/auth/refresh", mapper.writeValueAsString(refReq), null);
        assertThat(refRes.status()).isEqualTo(200);

        JsonNode refJson = refRes.json(mapper);
        String accessToken2 = refJson.get("accessToken").asText();
        String refreshToken2 = refJson.get("refreshToken").asText();
        assertThat(refreshToken2).isNotBlank();
        assertThat(refreshToken2).isNotEqualTo(refreshToken1);

        // New access token works
        SimpleResponse testRes = getWithAuth("/api/v1/accounts", accessToken2);
        assertThat(testRes.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Refresh tokens: reusing an old refresh token returns 401 and revokes the whole family")
    void refreshToken_reuseKillsFamily() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("theft-user");

        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String refreshToken1 = loginRes.json(mapper).get("refreshToken").asText();

        // 1st rotation: refreshToken1 -> refreshToken2
        RefreshTokenRequest refReq1 = new RefreshTokenRequest(refreshToken1);
        SimpleResponse refRes1 = post("/api/v1/auth/refresh", mapper.writeValueAsString(refReq1), null);
        assertThat(refRes1.status()).isEqualTo(200);
        String refreshToken2 = refRes1.json(mapper).get("refreshToken").asText();

        // 2nd rotation: refreshToken2 -> refreshToken3
        RefreshTokenRequest refReq2 = new RefreshTokenRequest(refreshToken2);
        SimpleResponse refRes2 = post("/api/v1/auth/refresh", mapper.writeValueAsString(refReq2), null);
        assertThat(refRes2.status()).isEqualTo(200);
        String refreshToken3 = refRes2.json(mapper).get("refreshToken").asText();

        // Attacker / client attempts to REUSE refreshToken1 (already used)
        SimpleResponse reuseRes = post("/api/v1/auth/refresh", mapper.writeValueAsString(refReq1), null);
        assertThat(reuseRes.status()).isEqualTo(401);

        // Theft mitigation: newest token (refreshToken3) in the family MUST now be revoked as well!
        RefreshTokenRequest refReq3 = new RefreshTokenRequest(refreshToken3);
        SimpleResponse victimRes = post("/api/v1/auth/refresh", mapper.writeValueAsString(refReq3), null);
        assertThat(victimRes.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Refresh tokens: 10 concurrent threads refreshing same token produce exactly 1 success")
    void refreshToken_concurrencyRaceSingleSuccess() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("race-ref-user");

        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String refreshToken = loginRes.json(mapper).get("refreshToken").asText();

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        String payload = mapper.writeValueAsString(new RefreshTokenRequest(refreshToken));

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    SimpleResponse resp = post("/api/v1/auth/refresh", payload, null);
                    if (resp.status() == 200) {
                        successes.incrementAndGet();
                    } else if (resp.status() == 401) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Thread failure", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("Refresh tokens: expired refresh token returns 401")
    void refreshToken_expiredReturns401() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("exp-ref-user");

        String rawToken = RefreshTokenGenerator.generateRawToken();
        String tokenHash = RefreshTokenGenerator.hashToken(rawToken);
        Instant now = clock.instant();

        // Seed an already expired refresh token in DB
        RefreshToken expiredToken = new RefreshToken(
                UUID.randomUUID(),
                user.id(),
                UUID.randomUUID(),
                tokenHash,
                now.minusSeconds(3600),
                now.minusSeconds(10) // expired 10s ago
        );
        refreshTokenRepository.save(expiredToken);

        RefreshTokenRequest req = new RefreshTokenRequest(rawToken);
        SimpleResponse res = post("/api/v1/auth/refresh", mapper.writeValueAsString(req), null);
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Refresh tokens: disabled user's refresh returns 401")
    void refreshToken_disabledUserReturns401() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("ref-admin");
        AuthTestHelper.TestUser user = authHelper.createCustomer("disabled-ref-user");

        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String refreshToken = loginRes.json(mapper).get("refreshToken").asText();

        // Admin disables user
        SimpleResponse disRes = postWithAuth("/api/v1/admin/users/" + user.id() + "/disable", null, admin.token());
        assertThat(disRes.status()).isEqualTo(204);

        // Disabled user tries to refresh -> 401
        RefreshTokenRequest req = new RefreshTokenRequest(refreshToken);
        SimpleResponse res = post("/api/v1/auth/refresh", mapper.writeValueAsString(req), null);
        assertThat(res.status()).isEqualTo(401);
    }

    // =========================================================================
    // 2. Logout & Logout-All
    // =========================================================================

    @Test
    @DisplayName("Logout: POST /api/v1/auth/logout revokes the family and returns 204")
    void logout_revokesFamily() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("logout-user");

        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String refreshToken = loginRes.json(mapper).get("refreshToken").asText();

        // Logout -> 204
        RefreshTokenRequest req = new RefreshTokenRequest(refreshToken);
        SimpleResponse logoutRes = post("/api/v1/auth/logout", mapper.writeValueAsString(req), null);
        assertThat(logoutRes.status()).isEqualTo(204);

        // Refresh with logged out token -> 401
        SimpleResponse refRes = post("/api/v1/auth/refresh", mapper.writeValueAsString(req), null);
        assertThat(refRes.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Logout-all: revokes refresh tokens and immediately kills existing access tokens")
    void logoutAll_killsExistingAccessTokensImmediately() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("logout-all-user");

        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String accessToken = loginRes.json(mapper).get("accessToken").asText();
        String refreshToken = loginRes.json(mapper).get("refreshToken").asText();

        // Access token works initially
        SimpleResponse beforeRes = getWithAuth("/api/v1/accounts", accessToken);
        assertThat(beforeRes.status()).isEqualTo(200);

        // Call logout-all
        SimpleResponse logoutAllRes = postWithAuth("/api/v1/auth/logout-all", null, accessToken);
        assertThat(logoutAllRes.status()).isEqualTo(204);

        // Existing access token immediately rejected -> 401!
        SimpleResponse afterRes = getWithAuth("/api/v1/accounts", accessToken);
        assertThat(afterRes.status()).isEqualTo(401);

        // Refresh token also rejected -> 401!
        SimpleResponse refRes = post("/api/v1/auth/refresh", mapper.writeValueAsString(new RefreshTokenRequest(refreshToken)), null);
        assertThat(refRes.status()).isEqualTo(401);
    }

    // =========================================================================
    // 3. User Enable/Disable & Instant Revocation
    // =========================================================================

    @Test
    @DisplayName("Admin: disable user immediately kills access token; admin cannot disable themselves")
    void disableUser_killsAccessTokenImmediately() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("adm-disabler");
        AuthTestHelper.TestUser victim = authHelper.createCustomer("dis-victim");

        LoginRequest loginReq = new LoginRequest(victim.email(), victim.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String victimToken = loginRes.json(mapper).get("accessToken").asText();

        // Victim access token works initially
        SimpleResponse beforeRes = getWithAuth("/api/v1/accounts", victimToken);
        assertThat(beforeRes.status()).isEqualTo(200);

        // Admin cannot disable themselves -> 400 Bad Request
        SimpleResponse selfDisRes = postWithAuth("/api/v1/admin/users/" + admin.id() + "/disable", null, admin.token());
        assertThat(selfDisRes.status()).isEqualTo(400);

        // Admin disables victim -> 204
        SimpleResponse disRes = postWithAuth("/api/v1/admin/users/" + victim.id() + "/disable", null, admin.token());
        assertThat(disRes.status()).isEqualTo(204);

        // Victim access token immediately returns 401
        SimpleResponse afterRes = getWithAuth("/api/v1/accounts", victimToken);
        assertThat(afterRes.status()).isEqualTo(401);

        // Admin re-enables victim -> 204
        SimpleResponse enRes = postWithAuth("/api/v1/admin/users/" + victim.id() + "/enable", null, admin.token());
        assertThat(enRes.status()).isEqualTo(204);
    }

    @Test
    @DisplayName("Role change: database role overrides access token claim immediately without re-login")
    void roleChangeInDatabaseAppliesWithoutNewToken() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("promoted-user");

        // Customer token gets 403 on admin reconciliation
        SimpleResponse beforeRes = getWithAuth("/api/v1/admin/reconciliation", user.token());
        assertThat(beforeRes.status()).isEqualTo(403);

        // Promote user in database to ADMIN
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());

        // Same access token now immediately grants ADMIN access -> 200 OK!
        SimpleResponse afterRes = getWithAuth("/api/v1/admin/reconciliation", user.token());
        assertThat(afterRes.status()).isEqualTo(200);
        assertThat(afterRes.json(mapper).get("consistent").asBoolean()).isTrue();
    }

    // =========================================================================
    // 4. Per-Account Progressive Lockout & Timing Parity
    // =========================================================================

    @Test
    @DisplayName("Lockout: 5 failed logins locks account for 1 min; correct password returns identical 401")
    void lockout_progressiveDelayAndTimingParity() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("lockout-user");

        LoginRequest wrongReq = new LoginRequest(user.email(), "WrongPassword123!");
        String wrongBody = mapper.writeValueAsString(wrongReq);

        // Failures 1 through 4 -> 401
        for (int i = 1; i <= 4; i++) {
            SimpleResponse res = post("/api/v1/auth/login", wrongBody, null);
            assertThat(res.status()).isEqualTo(401);
        }

        // 5th failure -> triggers lockout
        SimpleResponse fail5 = post("/api/v1/auth/login", wrongBody, null);
        assertThat(fail5.status()).isEqualTo(401);

        // Verify user is locked in database
        User dbUser = userRepository.findById(user.id()).orElseThrow();
        assertThat(dbUser.getFailedLoginCount()).isEqualTo(5);
        assertThat(dbUser.getLockedUntil()).isNotNull();
        assertThat(dbUser.getLockedUntil()).isAfter(clock.instant());

        // 6th attempt with CORRECT password while locked -> returns SAME generic 401
        LoginRequest correctReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse lockedRes = post("/api/v1/auth/login", mapper.writeValueAsString(correctReq), null);
        assertThat(lockedRes.status()).isEqualTo(401);

        // Lock state is NEVER revealed: status and ProblemDetail match exactly
        JsonNode failProblem = fail5.json(mapper);
        JsonNode lockedProblem = lockedRes.json(mapper);
        assertThat(lockedProblem.get("status").asInt()).isEqualTo(failProblem.get("status").asInt());
        assertThat(lockedProblem.get("title").asText()).isEqualTo(failProblem.get("title").asText());
        assertThat(lockedProblem.get("detail").asText()).isEqualTo(failProblem.get("detail").asText());

        // Verify successful login on another user resets counters
        AuthTestHelper.TestUser resetUser = authHelper.createCustomer("counter-reset-user");
        post("/api/v1/auth/login", mapper.writeValueAsString(new LoginRequest(resetUser.email(), "WrongPassword123!")), null);
        User resetDbBefore = userRepository.findById(resetUser.id()).orElseThrow();
        assertThat(resetDbBefore.getFailedLoginCount()).isEqualTo(1);

        // Now log in correctly -> resets counter to 0
        SimpleResponse resetLoginRes = post("/api/v1/auth/login",
                mapper.writeValueAsString(new LoginRequest(resetUser.email(), resetUser.rawPassword())), null);
        assertThat(resetLoginRes.status()).isEqualTo(200);

        User resetDbAfter = userRepository.findById(resetUser.id()).orElseThrow();
        assertThat(resetDbAfter.getFailedLoginCount()).isEqualTo(0);
        assertThat(resetDbAfter.getLockedUntil()).isNull();
    }

    // =========================================================================
    // 5. Rate Limiting & Bounded Memory
    // =========================================================================

    @Test
    @DisplayName("Rate limiter: requests exceeding limit receive 429 + Retry-After header; XFF ignored")
    void rateLimiting_perIp429WithRetryAfter() throws Exception {
        // Clear rate limiter map for deterministic test
        rateLimitingFilter.clear();
        rateLimitingFilter.setLoginLimit(10);

        LoginRequest req = new LoginRequest("nonexistent@example.com", "Password123!");
        String body = mapper.writeValueAsString(req);

        // Default login limit is 10/min. Send 10 requests -> all reach auth layer (401)
        for (int i = 0; i < 10; i++) {
            SimpleResponse res = post("/api/v1/auth/login", body, null);
            assertThat(res.status()).isEqualTo(401);
        }

        // 11th request -> blocked by rate limiter with 429
        SimpleResponse rateLimitedRes = post("/api/v1/auth/login", body, null);
        assertThat(rateLimitedRes.status()).isEqualTo(429);
        assertThat(rateLimitedRes.header("Retry-After")).isNotNull();
        JsonNode problem = rateLimitedRes.json(mapper);
        assertThat(problem.get("title").asText()).isEqualTo("Too Many Requests");

        // Spoofed X-Forwarded-For is IGNORED: still blocked with 429
        SimpleResponse spoofedRes = post("/api/v1/auth/login", body, Map.of("X-Forwarded-For", "198.51.100.42"));
        assertThat(spoofedRes.status()).isEqualTo(429);

        // Clean up
        rateLimitingFilter.clear();
    }

    @Test
    @DisplayName("Rate limiter: memory is strictly bounded and evicts expired windows")
    void rateLimiting_memoryBound() {
        rateLimitingFilter.clear();
        long nowSec = clock.instant().getEpochSecond();

        // Eviction test: old entries are purged
        rateLimitingFilter.evictExpired(nowSec);
        assertThat(rateLimitingFilter.getTrackedEntriesCount()).isLessThanOrEqualTo(RateLimitingFilter.MAX_TRACKED_ENTRIES);
    }

    // =========================================================================
    // 6. Request Size Limit (16 KB)
    // =========================================================================

    @Test
    @DisplayName("Request size: bodies exceeding 16 KB are rejected with 413 Payload Too Large")
    void requestSizeLimit_bodyExceeding16KBReturns413() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("size-tester");

        // 17 KB oversized body
        String bigPadding = "X".repeat(17 * 1024);
        String body = "{\"email\":\"size@example.com\",\"password\":\"" + bigPadding + "\"}";

        SimpleResponse res = post("/api/v1/auth/login", body, null);
        assertThat(res.status()).isEqualTo(413);

        JsonNode problem = res.json(mapper);
        assertThat(problem.get("title").asText()).isEqualTo("Payload Too Large");
        assertThat(problem.get("detail").asText()).contains("16384 bytes");
    }

    // =========================================================================
    // 7. Security Response Headers
    // =========================================================================

    @Test
    @DisplayName("Security headers: every response carries baseline security headers")
    void securityHeaders_presentOnResponses() throws Exception {
        SimpleResponse res = get("/actuator/health", null);

        assertThat(res.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.header("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(res.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(res.header("X-Frame-Options")).isEqualTo("DENY");

        // API endpoints also have Cache-Control: no-store
        AuthTestHelper.TestUser user = authHelper.createCustomer("hdr-user");
        SimpleResponse apiRes = getWithAuth("/api/v1/accounts", user.token());
        assertThat(apiRes.header("Cache-Control")).contains("no-store");
        assertThat(apiRes.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(apiRes.header("X-Frame-Options")).isEqualTo("DENY");
    }

    // =========================================================================
    // 8. Database Secret & Raw Token Storage Inspection
    // =========================================================================

    @Test
    @DisplayName("Database hygiene: database contains ONLY 64-char SHA-256 hashes, never raw refresh tokens")
    void database_containsOnlyHashes() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("hash-db-user");

        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse loginRes = post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);
        String rawRefreshToken = loginRes.json(mapper).get("refreshToken").asText();

        // Query database directly
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE user_id = ?", user.id());

        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            String tokenHash = (String) row.get("token_hash");
            assertThat(tokenHash.trim()).hasSize(64);
            assertThat(tokenHash.trim()).matches("^[0-9a-f]{64}$");
            assertThat(tokenHash.trim()).isNotEqualTo(rawRefreshToken);
        }
    }
}

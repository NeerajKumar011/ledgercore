package com.ledgercore.auth;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import com.ledgercore.api.AuthTestHelper;
import com.ledgercore.api.dto.AccountResponse;
import com.ledgercore.api.dto.CreateAccountRequest;
import com.ledgercore.api.dto.DepositRequest;
import com.ledgercore.api.dto.TransferRequest;
import com.ledgercore.security.JwtService;
import com.ledgercore.user.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthSecurityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurityIntegrationTest.class);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTestHelper authHelper;

    @Autowired
    private JwtService jwtService;

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

    private SimpleResponse postWithAuth(String path, String jsonBody, String token, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> headers = new java.util.HashMap<>();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        headers.put("Authorization", "Bearer " + token);
        return post(path, jsonBody, headers);
    }

    private static String uniqueKey() {
        return "KEY-" + UUID.randomUUID();
    }

    // =========================================================================
    // 1. User Registration
    // =========================================================================

    @Test
    @DisplayName("Registration: success -> 201 + Location + response body without password hash")
    void register_success() throws Exception {
        String email = "reg-test-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "P@ssword123456");

        SimpleResponse res = post("/api/v1/auth/register", mapper.writeValueAsString(req), null);

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.header("Location")).isNotNull();
        assertThat(res.header("Location")).contains("/api/v1/users/");

        JsonNode json = res.json(mapper);
        assertThat(json.has("userId")).isTrue();
        assertThat(json.get("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(json.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(json.has("password")).isFalse();
        assertThat(json.has("passwordHash")).isFalse();

        // Check cache control
        assertThat(res.header("Cache-Control")).contains("no-store");

        // Verify in DB that password is BCrypt hashed
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(user.getPasswordHash()).doesNotContain("P@ssword123456");
    }

    @Test
    @DisplayName("Registration: duplicate email -> 409 Conflict")
    void register_duplicateEmail() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "P@ssword123456");

        SimpleResponse res1 = post("/api/v1/auth/register", mapper.writeValueAsString(req), null);
        assertThat(res1.status()).isEqualTo(201);

        SimpleResponse res2 = post("/api/v1/auth/register", mapper.writeValueAsString(req), null);
        assertThat(res2.status()).isEqualTo(409);
        JsonNode problem = res2.json(mapper);
        assertThat(problem.get("title").asText()).isEqualTo("Conflict");
        assertThat(problem.get("status").asInt()).isEqualTo(409);
    }

    @Test
    @DisplayName("Registration: case-insensitive duplicate -> 409 Conflict")
    void register_caseInsensitiveDuplicate() throws Exception {
        String base = "case-" + UUID.randomUUID();
        RegisterRequest req1 = new RegisterRequest(base.toUpperCase() + "@Example.COM", "P@ssword123456");
        RegisterRequest req2 = new RegisterRequest(base.toLowerCase() + "@example.com", "P@ssword123456");

        SimpleResponse res1 = post("/api/v1/auth/register", mapper.writeValueAsString(req1), null);
        assertThat(res1.status()).isEqualTo(201);

        SimpleResponse res2 = post("/api/v1/auth/register", mapper.writeValueAsString(req2), null);
        assertThat(res2.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("Registration: password < 12 characters -> 400 Bad Request")
    void register_passwordTooShort() throws Exception {
        String email = "short-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "short123");

        SimpleResponse res = post("/api/v1/auth/register", mapper.writeValueAsString(req), null);
        assertThat(res.status()).isEqualTo(400);
        JsonNode problem = res.json(mapper);
        assertThat(problem.get("title").asText()).contains("Invalid Password");
    }

    @Test
    @DisplayName("Registration: password > 72 UTF-8 bytes -> 400 Bad Request (BCrypt boundary)")
    void register_passwordExceeds72Bytes() throws Exception {
        String email = "longpass-" + UUID.randomUUID() + "@example.com";
        // 73 ASCII characters = 73 bytes
        String longPass = "A".repeat(73);
        RegisterRequest req = new RegisterRequest(email, longPass);

        SimpleResponse res = post("/api/v1/auth/register", mapper.writeValueAsString(req), null);
        assertThat(res.status()).isEqualTo(400);
        JsonNode problem = res.json(mapper);
        assertThat(problem.get("detail").asText()).contains("72 UTF-8 bytes");
    }

    @Test
    @DisplayName("Registration: bad email format -> 400 Bad Request")
    void register_invalidEmailFormat() throws Exception {
        RegisterRequest req = new RegisterRequest("not-an-email", "P@ssword123456");

        SimpleResponse res = post("/api/v1/auth/register", mapper.writeValueAsString(req), null);
        assertThat(res.status()).isEqualTo(400);
        JsonNode problem = res.json(mapper);
        assertThat(problem.get("title").asText()).contains("Validation Failed");
    }

    @Test
    @DisplayName("Registration race: concurrent registration of identical email -> exactly 1 success, others 409")
    void register_concurrentRace() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "P@ssword123456");
        String payload = mapper.writeValueAsString(req);

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<SimpleResponse>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return post("/api/v1/auth/register", payload, null);
            }));
        }

        startGate.countDown();

        int successes = 0;
        int conflicts = 0;

        for (Future<SimpleResponse> f : futures) {
            SimpleResponse res = f.get(30, TimeUnit.SECONDS);
            if (res.status() == 201) {
                successes++;
            } else if (res.status() == 409) {
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(concurrency - 1);
    }

    // =========================================================================
    // 2. Login
    // =========================================================================

    @Test
    @DisplayName("Login: valid credentials -> 200 + Bearer token + Cache-Control: no-store")
    void login_success() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("login-cust");
        LoginRequest req = new LoginRequest(user.email(), user.rawPassword());

        SimpleResponse res = post("/api/v1/auth/login", mapper.writeValueAsString(req), null);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.header("Cache-Control")).contains("no-store");

        JsonNode json = res.json(mapper);
        assertThat(json.has("accessToken")).isTrue();
        assertThat(json.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(json.get("expiresInSeconds").asLong()).isEqualTo(900L);
    }

    @Test
    @DisplayName("Login: wrong password and unknown email return identical 401 status and body structure")
    void login_badCredentialsTimingProtection() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("login-badpass");

        // Wrong password for existing email
        LoginRequest wrongPassReq = new LoginRequest(user.email(), "WrongPassword123!");
        SimpleResponse wrongPassRes = post("/api/v1/auth/login", mapper.writeValueAsString(wrongPassReq), null);

        // Unknown email
        LoginRequest unknownEmailReq = new LoginRequest("unknown-" + UUID.randomUUID() + "@example.com", "WrongPassword123!");
        SimpleResponse unknownEmailRes = post("/api/v1/auth/login", mapper.writeValueAsString(unknownEmailReq), null);

        assertThat(wrongPassRes.status()).isEqualTo(401);
        assertThat(unknownEmailRes.status()).isEqualTo(401);

        JsonNode json1 = wrongPassRes.json(mapper);
        JsonNode json2 = unknownEmailRes.json(mapper);

        assertThat(json1.get("title").asText()).isEqualTo(json2.get("title").asText());
        assertThat(json1.get("detail").asText()).isEqualTo(json2.get("detail").asText());
    }

    @Test
    @DisplayName("Login: disabled user is rejected with 401")
    void login_disabledUser() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("disabled-cust");
        // Disable user
        User dbUser = userRepository.findById(user.id()).orElseThrow();
        dbUser.disable();
        userRepository.saveAndFlush(dbUser);

        LoginRequest req = new LoginRequest(user.email(), user.rawPassword());
        SimpleResponse res = post("/api/v1/auth/login", mapper.writeValueAsString(req), null);

        assertThat(res.status()).isEqualTo(401);
    }

    // =========================================================================
    // 3. JWT Verification & Hardening
    // =========================================================================

    @Test
    @DisplayName("JWT: expired token -> 401 ProblemDetail + WWW-Authenticate: Bearer")
    void jwt_expiredToken() throws Exception {
        // Generate expired token
        byte[] secret = jwtService.getSecretBytes();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .issuer("ledgercore")
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "CUSTOMER")
                        .issueTime(new Date(System.currentTimeMillis() - 7200_000))
                        .expirationTime(new Date(System.currentTimeMillis() - 3600_000))
                        .build()
        );
        jwt.sign(new MACSigner(secret));
        String expiredToken = jwt.serialize();

        SimpleResponse res = getWithAuth("/api/v1/accounts", expiredToken);
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.header("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    @DisplayName("JWT: wrong signature -> 401")
    void jwt_wrongSignature() throws Exception {
        byte[] wrongSecret = "wrong-secret-key-that-is-at-least-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .issuer("ledgercore")
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "CUSTOMER")
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 900_000))
                        .build()
        );
        jwt.sign(new MACSigner(wrongSecret));
        String tamperedToken = jwt.serialize();

        SimpleResponse res = getWithAuth("/api/v1/accounts", tamperedToken);
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("JWT: wrong issuer -> 401")
    void jwt_wrongIssuer() throws Exception {
        byte[] secret = jwtService.getSecretBytes();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .issuer("evil-issuer")
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "CUSTOMER")
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 900_000))
                        .build()
        );
        jwt.sign(new MACSigner(secret));
        String badIssuerToken = jwt.serialize();

        SimpleResponse res = getWithAuth("/api/v1/accounts", badIssuerToken);
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("JWT: alg none token -> 401")
    void jwt_algNoneRejected() throws Exception {
        PlainJWT plainJwt = new PlainJWT(
                new JWTClaimsSet.Builder()
                        .issuer("ledgercore")
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "ADMIN")
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 900_000))
                        .build()
        );
        String noneToken = plainJwt.serialize();

        SimpleResponse res = getWithAuth("/api/v1/admin/reconciliation", noneToken);
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("JWT: missing or malformed Authorization header -> 401")
    void jwt_missingOrMalformedHeader() throws Exception {
        // Missing Authorization header
        SimpleResponse resNoAuth = get("/api/v1/accounts", null);
        assertThat(resNoAuth.status()).isEqualTo(401);
        assertThat(resNoAuth.header("WWW-Authenticate")).contains("Bearer");

        // Malformed Authorization header
        SimpleResponse resMalformed = get("/api/v1/accounts", Map.of("Authorization", "Bearer invalid-jwt-payload"));
        assertThat(resMalformed.status()).isEqualTo(401);
        assertThat(resMalformed.header("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    @DisplayName("JWT: tokens in query param or cookies are rejected -> 401")
    void jwt_notAcceptedFromQueryOrCookie() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("cookie-test");

        // Query param ?access_token=...
        SimpleResponse resQuery = get("/api/v1/accounts?access_token=" + user.token(), null);
        assertThat(resQuery.status()).isEqualTo(401);

        // Cookie: access_token=...
        SimpleResponse resCookie = get("/api/v1/accounts", Map.of("Cookie", "access_token=" + user.token()));
        assertThat(resCookie.status()).isEqualTo(401);
    }

    // =========================================================================
    // 4. Deny-by-Default and RBAC
    // =========================================================================

    @Test
    @DisplayName("Deny-by-default: unauthenticated calls to non-public endpoints return 401")
    void denyByDefault_unauthenticatedCalls() throws Exception {
        Set<String> endpoints = Set.of(
                "/api/v1/accounts",
                "/api/v1/transfers",
                "/api/v1/admin/reconciliation"
        );

        for (String endpoint : endpoints) {
            SimpleResponse res = get(endpoint, null);
            assertThat(res.status())
                    .withFailMessage("Expected 401 for unauthenticated request to " + endpoint + " but got " + res.status())
                    .isEqualTo(401);
        }

        // Public endpoint returns 200
        SimpleResponse healthRes = get("/actuator/health", null);
        assertThat(healthRes.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Role RBAC: Customer calling /api/v1/admin/reconciliation -> 403 Forbidden")
    void rbac_customerDeniedAdminEndpoint() throws Exception {
        AuthTestHelper.TestUser customer = authHelper.createCustomer("cust-rbac");
        SimpleResponse res = getWithAuth("/api/v1/admin/reconciliation", customer.token());

        assertThat(res.status()).isEqualTo(403);
        JsonNode problem = res.json(mapper);
        assertThat(problem.get("title").asText()).isEqualTo("Forbidden");
        assertThat(problem.get("status").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("Role RBAC: Admin calling /api/v1/admin/reconciliation -> 200 OK")
    void rbac_adminAllowedAdminEndpoint() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("admin-rbac");
        SimpleResponse res = getWithAuth("/api/v1/admin/reconciliation", admin.token());

        assertThat(res.status()).isEqualTo(200);
        JsonNode json = res.json(mapper);
        assertThat(json.get("consistent").asBoolean()).isTrue();
    }

    // =========================================================================
    // 5. Ownership Policy & Concealment
    // =========================================================================

    @Test
    @DisplayName("Ownership: Customer reading another customer's account returns 404 (concealed)")
    void ownership_concealsOtherCustomerAccount() throws Exception {
        AuthTestHelper.TestUser userA = authHelper.createCustomer("owner-a");
        AuthTestHelper.TestUser userB = authHelper.createCustomer("owner-b");

        Account accB = accountService.openAccount("UserB", "USD", 1000L, userB.id());

        // User A attempts to read User B's account
        SimpleResponse res = getWithAuth("/api/v1/accounts/" + accB.getId(), userA.token());
        assertThat(res.status()).isEqualTo(404);
        JsonNode problem = res.json(mapper);
        assertThat(problem.get("title").asText()).isEqualTo("Account Not Found");

        // Non-existent ID also returns 404 with identical title
        SimpleResponse notFoundRes = getWithAuth("/api/v1/accounts/" + UUID.randomUUID(), userA.token());
        assertThat(notFoundRes.status()).isEqualTo(404);
        JsonNode notFoundProblem = notFoundRes.json(mapper);
        assertThat(notFoundProblem.get("title").asText()).isEqualTo(problem.get("title").asText());
    }

    @Test
    @DisplayName("Ownership: Customer reading another customer's statement returns 404")
    void ownership_concealsOtherCustomerStatement() throws Exception {
        AuthTestHelper.TestUser userA = authHelper.createCustomer("owner-stmt-a");
        AuthTestHelper.TestUser userB = authHelper.createCustomer("owner-stmt-b");

        Account accB = accountService.openAccount("UserB", "USD", 1000L, userB.id());

        SimpleResponse res = getWithAuth("/api/v1/accounts/" + accB.getId() + "/entries", userA.token());
        assertThat(res.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Ownership: Customer reading another customer's transfer returns 404")
    void ownership_concealsOtherCustomerTransfer() throws Exception {
        AuthTestHelper.TestUser userA = authHelper.createCustomer("tx-other-a");
        AuthTestHelper.TestUser userB = authHelper.createCustomer("tx-other-b");
        AuthTestHelper.TestUser userC = authHelper.createCustomer("tx-other-c");

        Account accB = accountService.openAccount("UserB", "USD", 1000L, userB.id());
        Account accC = accountService.openAccount("UserC", "USD", 0L, userC.id());

        TransferRequest req = new TransferRequest(accB.getId(), accC.getId(), 100L, "USD");
        SimpleResponse postRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(req), userB.token(), Map.of("Idempotency-Key", uniqueKey()));
        String txId = postRes.json(mapper).get("transferId").asText();

        // User A (unrelated party) reads transfer -> 404
        SimpleResponse res = getWithAuth("/api/v1/transfers/" + txId, userA.token());
        assertThat(res.status()).isEqualTo(404);

        // User B (sender) reads transfer -> 200
        SimpleResponse senderRes = getWithAuth("/api/v1/transfers/" + txId, userB.token());
        assertThat(senderRes.status()).isEqualTo(200);

        // User C (recipient) reads transfer -> 200
        SimpleResponse recipientRes = getWithAuth("/api/v1/transfers/" + txId, userC.token());
        assertThat(recipientRes.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Ownership: Customer cannot transfer FROM another customer's account -> 404")
    void ownership_cannotTransferFromOtherAccount() throws Exception {
        AuthTestHelper.TestUser userA = authHelper.createCustomer("hack-a");
        AuthTestHelper.TestUser userB = authHelper.createCustomer("victim-b");

        Account accA = accountService.openAccount("Attacker", "USD", 0L, userA.id());
        Account accB = accountService.openAccount("Victim", "USD", 1000L, userB.id());

        TransferRequest req = new TransferRequest(accB.getId(), accA.getId(), 500L, "USD");
        SimpleResponse res = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(req), userA.token(), Map.of("Idempotency-Key", uniqueKey()));

        assertThat(res.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Ownership: SYSTEM account ID returns 404 even for Admin")
    void ownership_systemAccountConcealedFromAdmin() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("system-check-admin");
        Account funding = accountService.getOrCreateFundingAccount("USD");

        SimpleResponse res = getWithAuth("/api/v1/accounts/" + funding.getId(), admin.token());
        assertThat(res.status()).isEqualTo(404);
    }

    // =========================================================================
    // 6. Deposit Authorization
    // =========================================================================

    @Test
    @DisplayName("Deposits: customer deposit denied (403) by default, admin deposit succeeds (201)")
    void deposits_roleRestrictions() throws Exception {
        AuthTestHelper.TestUser customer = authHelper.createCustomer("dep-cust");
        AuthTestHelper.TestUser admin = authHelper.createAdmin("dep-admin-test");

        Account acc = accountService.openAccount("CustAcc", "USD", 0L, customer.id());
        DepositRequest req = new DepositRequest(1000L);

        // Customer deposit attempt -> 403 Forbidden
        SimpleResponse custRes = postWithAuth("/api/v1/accounts/" + acc.getId() + "/deposits",
                mapper.writeValueAsString(req), customer.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(custRes.status()).isEqualTo(403);

        // Admin deposit attempt -> 201 Created
        SimpleResponse adminRes = postWithAuth("/api/v1/accounts/" + acc.getId() + "/deposits",
                mapper.writeValueAsString(req), admin.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(adminRes.status()).isEqualTo(201);
    }

    // =========================================================================
    // 7. CORS Protection
    // =========================================================================

    @Test
    @DisplayName("CORS: preflight OPTIONS with Origin receives no Access-Control-Allow-* headers")
    void cors_preflightReceivesNoAccessControlHeaders() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/accounts"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://malicious-site.example.com")
                .header("Access-Control-Request-Method", "POST")
                .build();

        HttpResponse<String> resp = httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(resp.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }
}

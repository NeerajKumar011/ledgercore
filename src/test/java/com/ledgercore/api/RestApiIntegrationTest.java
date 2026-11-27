package com.ledgercore.api;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import com.ledgercore.api.dto.AccountResponse;
import com.ledgercore.api.dto.CreateAccountRequest;
import com.ledgercore.api.dto.DepositRequest;
import com.ledgercore.api.dto.StatementResponse;
import com.ledgercore.api.dto.TransferRequest;
import com.ledgercore.api.dto.TransferResponse;
import com.ledgercore.ledger.EntryDirection;
import com.ledgercore.reconciliation.ReconciliationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP integration tests for the REST API running on embedded Tomcat.
 * Uses Java 25 java.net.http.HttpClient executing real network calls against RANDOM_PORT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RestApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RestApiIntegrationTest.class);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AuthTestHelper authHelper;

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

    private SimpleResponse getWithAuth(String path, String token, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> headers = new java.util.HashMap<>();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        headers.put("Authorization", "Bearer " + token);
        return get(path, headers);
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
    // 1. Account Creation
    // =========================================================================

    @Test
    @DisplayName("Create account: 201 + Location + body")
    void createAccount_success() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("alice");
        CreateAccountRequest req = new CreateAccountRequest("USD", 0L);
        SimpleResponse res = postWithAuth("/api/v1/accounts", mapper.writeValueAsString(req), user.token());

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.header("Location")).isNotNull();
        assertThat(res.header("Location")).contains("/api/v1/accounts/");

        JsonNode json = res.json(mapper);
        assertThat(json.get("ownerRef").asText()).isEqualTo(user.id().toString());
        assertThat(json.get("currency").asText()).isEqualTo("USD");
        assertThat(json.get("balanceMinor").asLong()).isEqualTo(0L);
        assertThat(json.get("accountNumber").asText()).startsWith("ACC-");
    }

    @Test
    @DisplayName("Create account with initial deposit: 201 + balance reflected")
    void createAccount_withInitialDeposit() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("bob-admin");
        CreateAccountRequest req = new CreateAccountRequest("USD", 5000L);
        SimpleResponse res = postWithAuth("/api/v1/accounts", mapper.writeValueAsString(req), admin.token());

        assertThat(res.status()).isEqualTo(201);
        JsonNode json = res.json(mapper);
        assertThat(json.get("balanceMinor").asLong()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Create account: invalid currency produces 400 naming the field")
    void createAccount_invalidCurrency() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("charlie");
        CreateAccountRequest req = new CreateAccountRequest("US", 0L);
        SimpleResponse res = postWithAuth("/api/v1/accounts", mapper.writeValueAsString(req), user.token());

        assertThat(res.status()).isEqualTo(400);
        JsonNode json = res.json(mapper);
        assertThat(json.get("title").asText()).isEqualTo("Validation Failed");
        assertThat(json.has("errors")).isTrue();
        assertThat(json.get("errors").has("currency")).isTrue();
    }

    @Test
    @DisplayName("Create account: negative deposit produces 400")
    void createAccount_negativeDeposit() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("dave");
        String jsonStr = "{\"ownerRef\":\"Dave\",\"currency\":\"USD\",\"initialDepositMinor\":-100}";
        SimpleResponse res = postWithAuth("/api/v1/accounts", jsonStr, user.token());

        assertThat(res.status()).isEqualTo(400);
        JsonNode json = res.json(mapper);
        assertThat(json.get("title").asText()).isEqualTo("Validation Failed");
    }

    // =========================================================================
    // 2. GET Account
    // =========================================================================

    @Test
    @DisplayName("GET account: 200, unknown -> 404, SYSTEM account -> 404")
    void getAccount_scenarios() throws Exception {
        AuthTestHelper.TestUser eve = authHelper.createCustomer("eve");
        Account acc = accountService.openAccount("Eve", "USD", 1000L, eve.id());

        // 200 OK
        SimpleResponse res = getWithAuth("/api/v1/accounts/" + acc.getId(), eve.token());
        assertThat(res.status()).isEqualTo(200);
        JsonNode json = res.json(mapper);
        assertThat(json.get("ownerRef").asText()).isEqualTo("Eve");
        assertThat(json.get("balanceMinor").asLong()).isEqualTo(1000L);

        // Unknown -> 404
        SimpleResponse unknownRes = getWithAuth("/api/v1/accounts/" + UUID.randomUUID(), eve.token());
        assertThat(unknownRes.status()).isEqualTo(404);

        // SYSTEM account -> 404 (hidden)
        Account funding = accountService.getOrCreateFundingAccount("USD");
        SimpleResponse systemRes = getWithAuth("/api/v1/accounts/" + funding.getId(), eve.token());
        assertThat(systemRes.status()).isEqualTo(404);
    }

    // =========================================================================
    // 3. Deposits
    // =========================================================================

    @Test
    @DisplayName("Deposits: first 201, same key -> 201 + Idempotent-Replayed, conflicting key -> 409, missing key -> 400")
    void deposit_scenarios() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("dep-admin");
        Account target = accountService.openAccount("DepUser", "USD", 0L, admin.id());
        String key = uniqueKey();
        DepositRequest req = new DepositRequest(1500L);

        // First deposit -> 201 Created
        SimpleResponse res1 = postWithAuth("/api/v1/accounts/" + target.getId() + "/deposits",
                mapper.writeValueAsString(req), admin.token(), Map.of("Idempotency-Key", key));
        assertThat(res1.status()).isEqualTo(201);
        assertThat(res1.header("Idempotent-Replayed")).isNull();
        assertThat(res1.json(mapper).get("fromAccountId").isNull()).isTrue();
        String transferId = res1.json(mapper).get("transferId").asText();

        // Replay with same key and body -> 201 + Idempotent-Replayed: true + same transferId
        SimpleResponse res2 = postWithAuth("/api/v1/accounts/" + target.getId() + "/deposits",
                mapper.writeValueAsString(req), admin.token(), Map.of("Idempotency-Key", key));
        assertThat(res2.status()).isEqualTo(201);
        assertThat(res2.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(res2.json(mapper).get("transferId").asText()).isEqualTo(transferId);

        // Conflicting payload with same key -> 409 Conflict
        DepositRequest conflictReq = new DepositRequest(9999L);
        SimpleResponse conflictRes = postWithAuth("/api/v1/accounts/" + target.getId() + "/deposits",
                mapper.writeValueAsString(conflictReq), admin.token(), Map.of("Idempotency-Key", key));
        assertThat(conflictRes.status()).isEqualTo(409);

        // Missing Idempotency-Key header -> 400 Bad Request
        SimpleResponse missingKeyRes = postWithAuth("/api/v1/accounts/" + target.getId() + "/deposits",
                mapper.writeValueAsString(req), admin.token());
        assertThat(missingKeyRes.status()).isEqualTo(400);

        // Target balance should have increased only once
        Account reloaded = accountRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getBalanceMinor()).isEqualTo(1500L);
    }

    // =========================================================================
    // 4. Transfers
    // =========================================================================

    @Test
    @DisplayName("Transfers: success 201, insufficient funds 422 + replay 422, validations 400/415")
    void transfer_scenarios() throws Exception {
        AuthTestHelper.TestUser srcUser = authHelper.createCustomer("src-user");
        AuthTestHelper.TestUser dstUser = authHelper.createCustomer("dst-user");
        AuthTestHelper.TestUser eurUser = authHelper.createCustomer("eur-user");

        Account src = accountService.openAccount("SrcUser", "USD", 5000L, srcUser.id());
        Account dst = accountService.openAccount("DstUser", "USD", 0L, dstUser.id());
        Account eurAcc = accountService.openAccount("EurUser", "EUR", 5000L, eurUser.id());

        // Success transfer -> 201 Created + Location
        String key1 = uniqueKey();
        TransferRequest req1 = new TransferRequest(src.getId(), dst.getId(), 2000L, "USD");
        SimpleResponse successRes = postWithAuth("/api/v1/transfers",
                mapper.writeValueAsString(req1), srcUser.token(), Map.of("Idempotency-Key", key1));
        assertThat(successRes.status()).isEqualTo(201);
        assertThat(successRes.header("Location")).isNotNull();
        assertThat(successRes.header("Location")).contains("/api/v1/transfers/");
        String successTxId = successRes.json(mapper).get("transferId").asText();

        // Insufficient funds -> 422 ProblemDetail
        String failKey = uniqueKey();
        TransferRequest failReq = new TransferRequest(src.getId(), dst.getId(), 100_000L, "USD");
        SimpleResponse failRes = postWithAuth("/api/v1/transfers",
                mapper.writeValueAsString(failReq), srcUser.token(), Map.of("Idempotency-Key", failKey));
        assertThat(failRes.status()).isEqualTo(422);
        JsonNode failProblem = failRes.json(mapper);
        assertThat(failProblem.get("status").asInt()).isEqualTo(422);
        assertThat(failProblem.get("failureReason").asText()).contains("Insufficient funds");
        String failedTxId = failProblem.get("transferId").asText();

        // Replay of failed transfer -> 422 + Idempotent-Replayed: true + same transferId
        SimpleResponse replayFailRes = postWithAuth("/api/v1/transfers",
                mapper.writeValueAsString(failReq), srcUser.token(), Map.of("Idempotency-Key", failKey));
        assertThat(replayFailRes.status()).isEqualTo(422);
        assertThat(replayFailRes.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replayFailRes.json(mapper).get("transferId").asText()).isEqualTo(failedTxId);

        // Missing Idempotency-Key -> 400
        SimpleResponse noKeyRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(req1), srcUser.token());
        assertThat(noKeyRes.status()).isEqualTo(400);

        // Blank Idempotency-Key -> 400
        SimpleResponse blankKeyRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(req1), srcUser.token(), Map.of("Idempotency-Key", "   "));
        assertThat(blankKeyRes.status()).isEqualTo(400);

        // Over-long Idempotency-Key (>128 chars) -> 400
        SimpleResponse longKeyRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(req1), srcUser.token(), Map.of("Idempotency-Key", "K".repeat(129)));
        assertThat(longKeyRes.status()).isEqualTo(400);

        // from == to -> 400
        TransferRequest sameAccReq = new TransferRequest(src.getId(), src.getId(), 100L, "USD");
        SimpleResponse sameAccRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(sameAccReq), srcUser.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(sameAccRes.status()).isEqualTo(400);

        // Unknown account -> 404
        TransferRequest unknownReq = new TransferRequest(UUID.randomUUID(), dst.getId(), 100L, "USD");
        SimpleResponse unknownRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(unknownReq), srcUser.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(unknownRes.status()).isEqualTo(404);

        // Currency mismatch -> 422 business failure
        TransferRequest mismatchReq = new TransferRequest(src.getId(), eurAcc.getId(), 100L, "USD");
        SimpleResponse mismatchRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(mismatchReq), srcUser.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(mismatchRes.status()).isEqualTo(422);

        // Amount <= 0 -> 400
        String zeroAmountJson = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amountMinor\":0,\"currency\":\"USD\"}",
                src.getId(), dst.getId());
        SimpleResponse zeroRes = postWithAuth("/api/v1/transfers", zeroAmountJson, srcUser.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(zeroRes.status()).isEqualTo(400);

        // Amount above JavaScript safe integer (9007199254740992) -> 400
        String excessiveAmountJson = String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amountMinor\":9007199254740992,\"currency\":\"USD\"}",
                src.getId(), dst.getId());
        SimpleResponse maxRes = postWithAuth("/api/v1/transfers", excessiveAmountJson, srcUser.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(maxRes.status()).isEqualTo(400);

        // Malformed JSON -> 400
        SimpleResponse malformedRes = postWithAuth("/api/v1/transfers", "{not json}", srcUser.token(), Map.of("Idempotency-Key", uniqueKey()));
        assertThat(malformedRes.status()).isEqualTo(400);

        // Wrong content type -> 415
        HttpRequest textReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/transfers"))
                .header("Content-Type", "text/plain")
                .header("Authorization", srcUser.authHeader())
                .header("Idempotency-Key", uniqueKey())
                .POST(HttpRequest.BodyPublishers.ofString("raw text"))
                .build();
        HttpResponse<String> textResp = httpClient.send(textReq, HttpResponse.BodyHandlers.ofString());
        assertThat(textResp.statusCode()).isEqualTo(415);
    }

    // =========================================================================
    // 5. GET Transfer by ID
    // =========================================================================

    @Test
    @DisplayName("GET transfer: 200 for existing, 404 for unknown")
    void getTransferById_scenarios() throws Exception {
        AuthTestHelper.TestUser userA = authHelper.createCustomer("tx-query-a");
        AuthTestHelper.TestUser userB = authHelper.createCustomer("tx-query-b");

        Account a = accountService.openAccount("TxQueryA", "USD", 5000L, userA.id());
        Account b = accountService.openAccount("TxQueryB", "USD", 0L, userB.id());

        TransferRequest req = new TransferRequest(a.getId(), b.getId(), 1000L, "USD");
        SimpleResponse postRes = postWithAuth("/api/v1/transfers", mapper.writeValueAsString(req), userA.token(), Map.of("Idempotency-Key", uniqueKey()));
        String txId = postRes.json(mapper).get("transferId").asText();

        // 200 OK
        SimpleResponse getRes = getWithAuth("/api/v1/transfers/" + txId, userA.token());
        assertThat(getRes.status()).isEqualTo(200);
        assertThat(getRes.json(mapper).get("transferId").asText()).isEqualTo(txId);
        assertThat(getRes.json(mapper).get("amountMinor").asLong()).isEqualTo(1000L);

        // 404 Not Found
        SimpleResponse notFoundRes = getWithAuth("/api/v1/transfers/" + UUID.randomUUID(), userA.token());
        assertThat(notFoundRes.status()).isEqualTo(404);
    }

    // =========================================================================
    // 6. Statement & Balance Conservation
    // =========================================================================

    @Test
    @DisplayName("Statement: ordering newest-first, paging with hasNext, size cap > 100 -> 400, credits minus debits = balance")
    void statement_scenarios() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("stmt-user");
        AuthTestHelper.TestUser peer = authHelper.createCustomer("stmt-peer");

        Account userAcc = accountService.openAccount("StmtUser", "USD", 10_000L, user.id());
        Account peerAcc = accountService.openAccount("PeerUser", "USD", 10_000L, peer.id());

        // Tx1: peer -> user 500
        postWithAuth("/api/v1/transfers", mapper.writeValueAsString(new TransferRequest(peerAcc.getId(), userAcc.getId(), 500L, "USD")), peer.token(), Map.of("Idempotency-Key", uniqueKey()));
        // Tx2: user -> peer 200
        postWithAuth("/api/v1/transfers", mapper.writeValueAsString(new TransferRequest(userAcc.getId(), peerAcc.getId(), 200L, "USD")), user.token(), Map.of("Idempotency-Key", uniqueKey()));
        // Tx3: peer -> user 300
        postWithAuth("/api/v1/transfers", mapper.writeValueAsString(new TransferRequest(peerAcc.getId(), userAcc.getId(), 300L, "USD")), peer.token(), Map.of("Idempotency-Key", uniqueKey()));
        // Tx4: user -> peer 100
        postWithAuth("/api/v1/transfers", mapper.writeValueAsString(new TransferRequest(userAcc.getId(), peerAcc.getId(), 100L, "USD")), user.token(), Map.of("Idempotency-Key", uniqueKey()));

        // Paging: page=0, size=2 -> hasNext=true
        SimpleResponse page0 = getWithAuth("/api/v1/accounts/" + userAcc.getId() + "/entries?page=0&size=2", user.token());
        assertThat(page0.status()).isEqualTo(200);
        JsonNode json0 = page0.json(mapper);
        assertThat(json0.get("items").size()).isEqualTo(2);
        assertThat(json0.get("hasNext").asBoolean()).isTrue();

        // Newest entry must be the last transfer (Tx4: DEBIT of 100)
        assertThat(json0.get("items").get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(json0.get("items").get(0).get("amountMinor").asLong()).isEqualTo(100L);

        // Paging: page=2, size=2 -> hasNext=false (5 entries total: 2 + 2 + 1)
        SimpleResponse page2 = getWithAuth("/api/v1/accounts/" + userAcc.getId() + "/entries?page=2&size=2", user.token());
        assertThat(page2.status()).isEqualTo(200);
        JsonNode json2 = page2.json(mapper);
        assertThat(json2.get("items").size()).isEqualTo(1);
        assertThat(json2.get("hasNext").asBoolean()).isFalse();

        // Size cap: size=101 -> 400 Bad Request
        SimpleResponse capRes = getWithAuth("/api/v1/accounts/" + userAcc.getId() + "/entries?page=0&size=101", user.token());
        assertThat(capRes.status()).isEqualTo(400);

        // Fetch all entries and verify double-entry balance equality
        SimpleResponse allRes = getWithAuth("/api/v1/accounts/" + userAcc.getId() + "/entries?page=0&size=100", user.token());
        JsonNode allItems = allRes.json(mapper).get("items");
        long computed = 0;
        for (JsonNode item : allItems) {
            long amt = item.get("amountMinor").asLong();
            if ("CREDIT".equals(item.get("direction").asText())) {
                computed += amt;
            } else {
                computed -= amt;
            }
        }
        Account reloaded = accountRepository.findById(userAcc.getId()).orElseThrow();
        assertThat(computed).isEqualTo(reloaded.getBalanceMinor());
        assertThat(computed).isEqualTo(10_500L);
    }

    // =========================================================================
    // 7. Reconciliation Endpoint
    // =========================================================================

    @Test
    @DisplayName("Admin reconciliation endpoint returns consistent=true")
    void reconciliation_endpoint() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("recon-admin");
        SimpleResponse res = getWithAuth("/api/v1/admin/reconciliation", admin.token());
        assertThat(res.status()).isEqualTo(200);
        JsonNode json = res.json(mapper);
        assertThat(json.get("consistent").asBoolean()).isTrue();
        assertThat(json.get("problems").size()).isEqualTo(0);
    }

    // =========================================================================
    // 8. Request ID Header & MDC Logging
    // =========================================================================

    @Test
    @DisplayName("Request ID: always present, valid incoming echoed, invalid replaced")
    void requestId_scenarios() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("reqid-admin");

        // 1. Default request without header gets valid generated UUID
        SimpleResponse res1 = getWithAuth("/api/v1/admin/reconciliation", admin.token());
        String reqId1 = res1.header("X-Request-Id");
        assertThat(reqId1).isNotNull().isNotBlank();
        assertThat(UUID.fromString(reqId1)).isNotNull();

        // 2. Valid incoming header is echoed
        SimpleResponse res2 = getWithAuth("/api/v1/admin/reconciliation", admin.token(), Map.of("X-Request-Id", "client-request-id.123_456"));
        assertThat(res2.header("X-Request-Id")).isEqualTo("client-request-id.123_456");

        // 3. Invalid incoming header (containing spaces — violates ^[A-Za-z0-9._-]{1,64}$) is safely replaced.
        SimpleResponse res3 = getWithAuth("/api/v1/admin/reconciliation", admin.token(), Map.of("X-Request-Id", "bad id with spaces"));
        String reqId3 = res3.header("X-Request-Id");
        assertThat(reqId3).isNotEqualTo("bad id with spaces");
        assertThat(UUID.fromString(reqId3)).isNotNull();
    }

    // =========================================================================
    // 9. Error Leak Protection
    // =========================================================================

    @Test
    @DisplayName("Unexpected server error returns generic 500 without leaking stack trace, classes, or SQL")
    void unexpectedError_leakProtection() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("err-user");
        SimpleResponse res = getWithAuth("/api/v1/test-error/unexpected", user.token());

        assertThat(res.status()).isEqualTo(500);
        JsonNode problem = res.json(mapper);
        assertThat(problem.get("title").asText()).isEqualTo("Internal Server Error");
        assertThat(problem.get("detail").asText()).contains("Reference ID:");

        // Verify no leak of classes, traces, or SQL
        String rawBody = res.body();
        assertThat(rawBody).doesNotContain("RuntimeException");
        assertThat(rawBody).doesNotContain("PSQLException");
        assertThat(rawBody).doesNotContain("SELECT * FROM");
        assertThat(rawBody).doesNotContain("accounts");
    }

    // =========================================================================
    // 10. HTTP-Level Race Condition
    // =========================================================================

    @Test
    @DisplayName("HTTP race: 20 concurrent POST /transfers with same key and body -> exactly 1 non-replayed 201, 19 replayed 201s, money transferred once")
    void httpLevelRace_concurrentSameKey() throws Exception {
        AuthTestHelper.TestUser fromUser = authHelper.createCustomer("race-from");
        AuthTestHelper.TestUser toUser = authHelper.createCustomer("race-to");

        Account from = accountService.openAccount("RaceFrom", "USD", 10_000L, fromUser.id());
        Account to = accountService.openAccount("RaceTo", "USD", 0L, toUser.id());

        String raceKey = uniqueKey();
        TransferRequest req = new TransferRequest(from.getId(), to.getId(), 500L, "USD");
        String jsonPayload = mapper.writeValueAsString(req);

        int concurrency = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<SimpleResponse>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return postWithAuth("/api/v1/transfers", jsonPayload, fromUser.token(), Map.of("Idempotency-Key", raceKey));
            }));
        }

        startGate.countDown();

        int firstTime201 = 0;
        int replayed201 = 0;
        String commonTransferId = null;

        for (Future<SimpleResponse> f : futures) {
            SimpleResponse res = f.get(30, TimeUnit.SECONDS);
            assertThat(res.status()).isEqualTo(201);

            JsonNode body = res.json(mapper);
            String txId = body.get("transferId").asText();
            if (commonTransferId == null) {
                commonTransferId = txId;
            } else {
                assertThat(txId).isEqualTo(commonTransferId);
            }

            String replayedHeader = res.header("Idempotent-Replayed");
            if ("true".equalsIgnoreCase(replayedHeader)) {
                replayed201++;
            } else {
                firstTime201++;
            }
        }

        executor.shutdown();

        log.info("HTTP Race results: firstTime201={}, replayed201={}", firstTime201, replayed201);
        assertThat(firstTime201).isEqualTo(1);
        assertThat(replayed201).isEqualTo(19);

        Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account reloadedTo = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(reloadedFrom.getBalanceMinor()).isEqualTo(9500L);
        assertThat(reloadedTo.getBalanceMinor()).isEqualTo(500L);
    }
}

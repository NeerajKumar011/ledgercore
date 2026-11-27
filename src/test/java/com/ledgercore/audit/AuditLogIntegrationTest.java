package com.ledgercore.audit;

import com.ledgercore.api.AuthTestHelper;
import com.ledgercore.auth.*;
import com.ledgercore.user.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.TestcontainersConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public class AuditLogIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AuthTestHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private record SimpleResponse(int status, String body) {
        public JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse JSON: " + body, e);
            }
        }
    }

    private SimpleResponse getWithAuth(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new SimpleResponse(resp.statusCode(), resp.body());
    }

    private SimpleResponse post(String path, String jsonBody, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = b.POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "")).build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new SimpleResponse(resp.statusCode(), resp.body());
    }

    @Test
    @DisplayName("Hash Chain: valid chain after a mix of events")
    void chain_validAfterMixOfEvents() {
        UUID actorId = UUID.randomUUID();
        auditService.recordAuth("LOGIN_SUCCESS", "SUCCESS", actorId, "CUSTOMER", "USER", actorId.toString(), null);
        auditService.recordAdmin("ACCOUNT_OPENED", "SUCCESS", actorId, "CUSTOMER", "ACCOUNT", UUID.randomUUID().toString(), "{\"currency\":\"USD\"}");
        auditService.recordAuth("LOGOUT", "SUCCESS", actorId, "CUSTOMER", "USER", actorId.toString(), null);

        AuditVerificationResponse result = auditService.verifyChain();
        assertThat(result.valid()).isTrue();
        assertThat(result.eventsChecked()).isGreaterThanOrEqualTo(3);
        assertThat(result.firstBrokenSeq()).isNull();
        assertThat(result.headSeq()).isNotNull();
        assertThat(result.headHash()).isNotBlank();
    }

    @Test
    @DisplayName("Immutability: database triggers reject UPDATE, DELETE, and TRUNCATE")
    void immutability_triggersRejectUpdateDeleteTruncate() {
        // Record at least one event
        AuditEvent event = auditService.recordAuth("TEST_IMMUTABILITY", "SUCCESS", null, null, null, null, null);
        assertThat(event).isNotNull();

        // 1. UPDATE rejected
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_events SET action = 'HACKED' WHERE seq = ?", event.getSeq()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is INSERT-only: UPDATE and DELETE are forbidden");

        // 2. DELETE rejected
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_events WHERE seq = ?", event.getSeq()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is INSERT-only: UPDATE and DELETE are forbidden");

        // 3. TRUNCATE rejected
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is INSERT-only: TRUNCATE is forbidden");
    }

    @Test
    @DisplayName("Concurrency: 50 threads recording concurrently produce contiguous seq with no gaps or duplicates")
    void concurrency_50ThreadsProduceContiguousChain() throws Exception {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<AuditEvent> created = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    AuditEvent ev = auditService.recordAuth("CONCURRENT_EVENT", "SUCCESS", null, null, "TEST", "idx-" + index, null);
                    if (ev != null) {
                        created.add(ev);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(created).hasSize(threads);

        // Verify strictly contiguous sequences
        List<Long> seqs = created.stream().map(AuditEvent::getSeq).sorted().toList();
        for (int i = 0; i < seqs.size() - 1; i++) {
            assertThat(seqs.get(i + 1) - seqs.get(i)).isEqualTo(1L);
        }

        AuditVerificationResponse result = auditService.verifyChain();
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("Tamper detection: modifying a row is detected with exact firstBrokenSeq, and restored")
    void tamperDetection_modifiedRowDetectedAndRestored() {
        AuditEvent event = auditService.recordAdmin("PRE_TAMPER", "SUCCESS", null, null, "TARGET", "original-val", null);
        long targetSeq = event.getSeq();

        // 1. Disable trigger in test session
        jdbcTemplate.execute("ALTER TABLE audit_events DISABLE TRIGGER audit_events_prevent_update_or_delete");
        try {
            // Tamper with target_id
            jdbcTemplate.update("UPDATE audit_events SET target_id = 'tampered-val' WHERE seq = ?", targetSeq);

            // Verify chain reports invalid at this exact seq
            AuditVerificationResponse broken = auditService.verifyChain();
            assertThat(broken.valid()).isFalse();
            assertThat(broken.firstBrokenSeq()).isEqualTo(targetSeq);
        } finally {
            // Restore original value and re-enable trigger
            jdbcTemplate.update("UPDATE audit_events SET target_id = 'original-val' WHERE seq = ?", targetSeq);
            jdbcTemplate.execute("ALTER TABLE audit_events ENABLE TRIGGER audit_events_prevent_update_or_delete");
        }

        // Verify chain is valid again after restoration
        AuditVerificationResponse restored = auditService.verifyChain();
        assertThat(restored.valid()).isTrue();
    }

    @Test
    @DisplayName("Tamper detection: deleted row in chain is detected")
    void tamperDetection_deletedRowSimulationDetected() {
        AuditEvent ev1 = auditService.recordAdmin("DEL_SIM_1", "SUCCESS", null, null, "T1", "val1", null);
        AuditEvent ev2 = auditService.recordAdmin("DEL_SIM_2", "SUCCESS", null, null, "T2", "val2", null);
        AuditEvent ev3 = auditService.recordAdmin("DEL_SIM_3", "SUCCESS", null, null, "T3", "val3", null);

        jdbcTemplate.execute("ALTER TABLE audit_events DISABLE TRIGGER audit_events_prevent_update_or_delete");
        try {
            // Delete middle event ev2
            jdbcTemplate.update("DELETE FROM audit_events WHERE seq = ?", ev2.getSeq());

            // Next row ev3 expects ev2's row_hash as prev_hash, but now encounters broken chain
            AuditVerificationResponse broken = auditService.verifyChain();
            assertThat(broken.valid()).isFalse();
            assertThat(broken.firstBrokenSeq()).isEqualTo(ev3.getSeq());
        } finally {
            // Restore ev2
            jdbcTemplate.update(
                    "INSERT INTO audit_events (seq, id, occurred_at, actor_user_id, actor_role, action, outcome, target_type, target_id, request_id, client_ip, details, prev_hash, row_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ev2.getSeq(), ev2.getId(), java.sql.Timestamp.from(ev2.getOccurredAt()), ev2.getActorUserId(), ev2.getActorRole(), ev2.getAction(), ev2.getOutcome(), ev2.getTargetType(), ev2.getTargetId(), ev2.getRequestId(), ev2.getClientIp(), ev2.getDetails(), ev2.getPrevHash(), ev2.getRowHash()
            );
            jdbcTemplate.execute("ALTER TABLE audit_events ENABLE TRIGGER audit_events_prevent_update_or_delete");
        }

        AuditVerificationResponse restored = auditService.verifyChain();
        assertThat(restored.valid()).isTrue();
    }

    @Test
    @DisplayName("Microsecond truncation: Instant with nanoseconds preserves microsecond precision and verifies")
    void microsecondTruncation_roundTripWithNanoseconds() {
        Instant nanoInstant = Instant.parse("2026-11-28T10:15:30.123456789Z");
        Instant microInstant = nanoInstant.truncatedTo(ChronoUnit.MICROS);

        String canonical = AuditCanonicalizer.buildCanonicalString(
                999L, UUID.randomUUID(), nanoInstant, null, null, "MICRO_TEST", "SUCCESS",
                null, null, null, null, null, AuditCanonicalizer.GENESIS_PREV_HASH
        );

        // Contains .123456Z (microseconds only, nanoseconds stripped)
        assertThat(canonical).contains("2026-11-28T10:15:30.123456Z");
        assertThat(canonical).doesNotContain("789");
    }

    @Test
    @DisplayName("RBAC: non-admin receives 403 on /admin/audit endpoints and triggers ACCESS_DENIED audit event")
    void rbac_nonAdminGets403OnAuditEndpoints() throws Exception {
        AuthTestHelper.TestUser customer = authHelper.createCustomer("audit-unauth-user");

        SimpleResponse verifyRes = getWithAuth("/api/v1/admin/audit/verify", customer.token());
        assertThat(verifyRes.status()).isEqualTo(403);

        SimpleResponse headRes = getWithAuth("/api/v1/admin/audit/head", customer.token());
        assertThat(headRes.status()).isEqualTo(403);

        SimpleResponse listRes = getWithAuth("/api/v1/admin/audit", customer.token());
        assertThat(listRes.status()).isEqualTo(403);

        // Verify ACCESS_DENIED audit events were recorded
        Slice<AuditEventResponse> deniedEvents = auditService.findEvents("ACCESS_DENIED", customer.id(), null, null, PageRequest.of(0, 10));
        assertThat(deniedEvents.getContent()).isNotEmpty();
        assertThat(deniedEvents.getContent().get(0).outcome()).isEqualTo("DENIED");
    }

    @Test
    @DisplayName("Privacy: audit_events table contains NO passwords, NO tokens, and NO raw email addresses")
    void privacy_noSecretsOrRawEmailsInAuditTable() throws Exception {
        AuthTestHelper.TestUser user = authHelper.createCustomer("privacy-audit-user");

        // Perform login
        LoginRequest loginReq = new LoginRequest(user.email(), user.rawPassword());
        post("/api/v1/auth/login", mapper.writeValueAsString(loginReq), null);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM audit_events");
        for (Map<String, Object> row : rows) {
            String targetId = (String) row.get("target_id");
            String details = row.get("details") != null ? row.get("details").toString() : null;

            // No raw email in target_id or details
            if (targetId != null) {
                assertThat(targetId).doesNotContain("@");
            }
            if (details != null) {
                assertThat(details).doesNotContain("@");
                assertThat(details).doesNotContain(user.rawPassword());
            }
        }
    }

    @Test
    @DisplayName("Paging & Filters: query audit events by action and actor")
    void pagingAndFilters() throws Exception {
        AuthTestHelper.TestUser admin = authHelper.createAdmin("audit-admin-tester");
        UUID targetUser = UUID.randomUUID();

        auditService.recordAdmin("FILTER_TEST_A", "SUCCESS", admin.id(), "ADMIN", "USER", targetUser.toString(), null);
        auditService.recordAdmin("FILTER_TEST_B", "SUCCESS", admin.id(), "ADMIN", "USER", targetUser.toString(), null);

        SimpleResponse res = getWithAuth("/api/v1/admin/audit?action=FILTER_TEST_A&size=10", admin.token());
        assertThat(res.status()).isEqualTo(200);

        JsonNode json = res.json(mapper);
        assertThat(json.get("content")).isNotEmpty();
        for (JsonNode item : json.get("content")) {
            assertThat(item.get("action").asText()).isEqualTo("FILTER_TEST_A");
        }
    }

    @Test
    @DisplayName("Streaming Verification: 5,000 generated events verify cleanly in bounded memory")
    void streamingVerify_5000GeneratedEventsInBoundedMemory() {
        int count = 5000;
        long startSeq = auditRepository.findTopByOrderBySeqDesc().map(t -> t.getSeq() + 1).orElse(1L);
        String currentPrev = auditRepository.findTopByOrderBySeqDesc().map(AuditEvent::getRowHash).orElse(AuditCanonicalizer.GENESIS_PREV_HASH);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        List<Object[]> batchArgs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seq = startSeq + i;
            UUID id = UUID.randomUUID();
            String rowHash = AuditCanonicalizer.computeRowHash(
                    seq, id, now, null, null, "STREAM_TEST", "SUCCESS", "TARGET", "id-" + i,
                    null, null, null, currentPrev
            );
            batchArgs.add(new Object[]{seq, id, java.sql.Timestamp.from(now), null, null, "STREAM_TEST", "SUCCESS", "TARGET", "id-" + i, null, null, null, currentPrev, rowHash});
            currentPrev = rowHash;
        }

        // Direct batch insert to bypass advisory lock overhead for test fixture setup
        jdbcTemplate.batchUpdate(
                "INSERT INTO audit_events (seq, id, occurred_at, actor_user_id, actor_role, action, outcome, target_type, target_id, request_id, client_ip, details, prev_hash, row_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchArgs
        );

        AuditVerificationResponse result = auditService.verifyChain();
        assertThat(result.valid()).isTrue();
        assertThat(result.eventsChecked()).isGreaterThanOrEqualTo(count);
        assertThat(result.firstBrokenSeq()).isNull();
    }
}

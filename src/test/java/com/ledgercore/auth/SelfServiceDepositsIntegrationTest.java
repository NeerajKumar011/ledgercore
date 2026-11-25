package com.ledgercore.auth;

import com.ledgercore.TestcontainersConfiguration;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import com.ledgercore.api.AuthTestHelper;
import com.ledgercore.api.dto.DepositRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "ledgercore.demo.self-service-deposits=true")
class SelfServiceDepositsIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuthTestHelper authHelper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private record SimpleResponse(int status, HttpResponse<String> raw, String body) {}

    private SimpleResponse postWithAuth(String path, String jsonBody, String token, Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : ""));
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new SimpleResponse(resp.statusCode(), resp, resp.body());
    }

    @Test
    @DisplayName("Self-service deposits enabled: customer can deposit to their OWN account, but NOT to another's account")
    void selfServiceDeposits_allowedOnlyForOwnAccount() throws Exception {
        AuthTestHelper.TestUser userA = authHelper.createCustomer("self-a");
        AuthTestHelper.TestUser userB = authHelper.createCustomer("self-b");

        Account accA = accountService.openAccount("SelfA", "USD", 0L, userA.id());
        Account accB = accountService.openAccount("SelfB", "USD", 0L, userB.id());

        DepositRequest req = new DepositRequest(2500L);
        String payload = mapper.writeValueAsString(req);

        // 1. User A deposits to own account -> 201 Created
        SimpleResponse ownRes = postWithAuth(
                "/api/v1/accounts/" + accA.getId() + "/deposits",
                payload,
                userA.token(),
                Map.of("Idempotency-Key", "KEY-" + UUID.randomUUID())
        );
        assertThat(ownRes.status()).isEqualTo(201);

        Account reloadedA = accountRepository.findById(accA.getId()).orElseThrow();
        assertThat(reloadedA.getBalanceMinor()).isEqualTo(2500L);

        // 2. User A attempts to deposit to User B's account -> 404 (concealed non-owned account)
        SimpleResponse otherRes = postWithAuth(
                "/api/v1/accounts/" + accB.getId() + "/deposits",
                payload,
                userA.token(),
                Map.of("Idempotency-Key", "KEY-" + UUID.randomUUID())
        );
        assertThat(otherRes.status()).isEqualTo(404);
    }
}

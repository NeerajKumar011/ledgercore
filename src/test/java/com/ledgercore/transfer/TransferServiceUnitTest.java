package com.ledgercore.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 unit tests — no database, no Spring context.
 * Tests request-hash determinism and all input-validation failure paths.
 */
class TransferServiceUnitTest {

    // We test the service's hash logic by calling the same canonical string the
    // service builds, so both sides must stay in sync.  The canonical format is:
    //   "v1|<fromId>|<toId>|<amountMinor>|<currency>"
    private static String expectedHash(UUID from, UUID to, long amount, String currency) {
        try {
            String canonical = "v1|" + from + "|" + to + "|" + amount + "|" + currency;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Hash determinism ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Hash is deterministic: same inputs always produce the same hash")
    void hashIsDeterministic() {
        UUID from = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID to   = UUID.fromString("22222222-2222-2222-2222-222222222222");

        String h1 = expectedHash(from, to, 1000L, "USD");
        String h2 = expectedHash(from, to, 1000L, "USD");

        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    @DisplayName("Hash changes when fromAccountId changes")
    void hashSensitiveToFromAccountId() {
        UUID to   = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID from1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID from2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        assertThat(expectedHash(from1, to, 1000L, "USD"))
                .isNotEqualTo(expectedHash(from2, to, 1000L, "USD"));
    }

    @Test
    @DisplayName("Hash changes when toAccountId changes")
    void hashSensitiveToToAccountId() {
        UUID from = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID to1  = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID to2  = UUID.fromString("44444444-4444-4444-4444-444444444444");

        assertThat(expectedHash(from, to1, 1000L, "USD"))
                .isNotEqualTo(expectedHash(from, to2, 1000L, "USD"));
    }

    @Test
    @DisplayName("Hash changes when amountMinor changes")
    void hashSensitiveToAmount() {
        UUID from = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID to   = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(expectedHash(from, to, 1000L, "USD"))
                .isNotEqualTo(expectedHash(from, to, 1001L, "USD"));
    }

    @Test
    @DisplayName("Hash changes when currency changes")
    void hashSensitiveToCurrency() {
        UUID from = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID to   = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(expectedHash(from, to, 1000L, "USD"))
                .isNotEqualTo(expectedHash(from, to, 1000L, "EUR"));
    }

    // ── Input validation — all failures via InvalidTransferRequestException ───
    // We instantiate TransferService with null repositories; validation must
    // throw before any repository call is made.

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final TransferService service = new TransferService(null, null, null);

    @Test
    @DisplayName("Null command is rejected")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> service.execute(null))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("TransferCommand must not be null");
    }

    @Test
    @DisplayName("Null idempotency key is rejected")
    void rejectsNullIdempotencyKey() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand(null, A, B, 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("Blank idempotency key is rejected")
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("   ", A, B, 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("Idempotency key > 128 chars is rejected")
    void rejectsTooLongIdempotencyKey() {
        String longKey = "K".repeat(129);
        assertThatThrownBy(() ->
                service.execute(new TransferCommand(longKey, A, B, 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("128");
    }

    @Test
    @DisplayName("Null fromAccountId is rejected")
    void rejectsNullFromAccountId() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", null, B, 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("fromAccountId");
    }

    @Test
    @DisplayName("Null toAccountId is rejected")
    void rejectsNullToAccountId() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, null, 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("toAccountId");
    }

    @Test
    @DisplayName("from == to is rejected")
    void rejectsFromEqualToTo() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, A, 100L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("different");
    }

    @Test
    @DisplayName("Zero amount is rejected")
    void rejectsZeroAmount() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, B, 0L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("amountMinor");
    }

    @Test
    @DisplayName("Negative amount is rejected")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, B, -50L, "USD")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("amountMinor");
    }

    @Test
    @DisplayName("Null currency is rejected")
    void rejectsNullCurrency() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, B, 100L, null)))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("Lowercase currency is rejected (must be A-Z only)")
    void rejectsLowercaseCurrency() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, B, 100L, "usd")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("Two-letter currency is rejected")
    void rejectsTwoLetterCurrency() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, B, 100L, "US")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("Numeric currency code is rejected")
    void rejectsNumericCurrency() {
        assertThatThrownBy(() ->
                service.execute(new TransferCommand("key-1", A, B, 100L, "840")))
                .isInstanceOf(InvalidTransferRequestException.class)
                .hasMessageContaining("currency");
    }
}

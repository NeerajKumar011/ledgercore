package com.ledgercore.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AuditCanonicalizerTest {

    @Test
    @DisplayName("Canonicalizer: object keys in details JSON are deterministically sorted")
    void canonicalizeJson_sortsKeysDeterministically() {
        String json1 = "{\"b\": 1, \"a\": 2}";
        String json2 = "{\"a\": 2, \"b\": 1}";

        String canon1 = AuditCanonicalizer.canonicalizeJson(json1);
        String canon2 = AuditCanonicalizer.canonicalizeJson(json2);

        assertThat(canon1).isEqualTo("{\"a\":2,\"b\":1}");
        assertThat(canon1).isEqualTo(canon2);
    }

    @Test
    @DisplayName("Canonicalizer: nested JSON keys are recursively sorted")
    void canonicalizeJson_sortsNestedKeys() {
        String nestedJson = "{\"z\": {\"k2\": \"v2\", \"k1\": \"v1\"}, \"a\": 10}";
        String canon = AuditCanonicalizer.canonicalizeJson(nestedJson);

        assertThat(canon).isEqualTo("{\"a\":10,\"z\":{\"k1\":\"v1\",\"k2\":\"v2\"}}");
    }

    @Test
    @DisplayName("Canonicalizer: row hash matches expected SHA-256")
    void computeRowHash_deterministic() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant occurredAt = Instant.parse("2026-11-28T12:00:00.123456Z");

        String hash1 = AuditCanonicalizer.computeRowHash(
                1L, id, occurredAt, null, null, "LOGIN", "SUCCESS",
                "USER", "usr-1", "req-1", "127.0.0.1", "{\"k\": \"v\"}", AuditCanonicalizer.GENESIS_PREV_HASH
        );

        String hash2 = AuditCanonicalizer.computeRowHash(
                1L, id, occurredAt, null, null, "LOGIN", "SUCCESS",
                "USER", "usr-1", "req-1", "127.0.0.1", "{\"k\":\"v\"}", AuditCanonicalizer.GENESIS_PREV_HASH
        );

        assertThat(hash1).hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }
}

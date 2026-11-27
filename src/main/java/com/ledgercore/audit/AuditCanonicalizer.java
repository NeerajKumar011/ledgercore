package com.ledgercore.audit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Canonical formatting and SHA-256 hash calculation for tamper-evident audit events.
 * Used identically by both writer and verifier to ensure byte-for-byte consistency.
 */
public final class AuditCanonicalizer {

    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private static final DateTimeFormatter UTC_MICROS_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    private AuditCanonicalizer() {
        // utility class
    }

    /**
     * Formats an Instant truncated to microseconds into canonical ISO-8601 UTC string.
     */
    public static String formatOccurredAt(Instant occurredAt) {
        if (occurredAt == null) {
            return "";
        }
        Instant truncated = occurredAt.truncatedTo(ChronoUnit.MICROS);
        return UTC_MICROS_FORMATTER.format(truncated);
    }

    /**
     * Canonicalizes a JSON string by recursively sorting object keys and removing extraneous whitespace.
     * Returns empty string if json is null, empty, or unparseable.
     */
    public static String canonicalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode tree = CANONICAL_MAPPER.readTree(json);
            JsonNode sorted = sortJsonNode(tree);
            return CANONICAL_MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            return json.trim();
        }
    }

    private static JsonNode sortJsonNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Map<String, JsonNode> sortedMap = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sortedMap.put(field.getKey(), sortJsonNode(field.getValue()));
            }
            ObjectNode result = CANONICAL_MAPPER.createObjectNode();
            sortedMap.forEach(result::set);
            return result;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode result = CANONICAL_MAPPER.createArrayNode();
            for (JsonNode elem : arrayNode) {
                result.add(sortJsonNode(elem));
            }
            return result;
        }
        return node;
    }

    /**
     * Builds the deterministic canonical pipe-delimited string representing the audit event.
     * Format:
     * seq|id|occurred_at_micros|actor_user_id|actor_role|action|outcome|target_type|target_id|request_id|client_ip|canonical_details|prev_hash
     */
    public static String buildCanonicalString(
            long seq,
            UUID id,
            Instant occurredAt,
            UUID actorUserId,
            String actorRole,
            String action,
            String outcome,
            String targetType,
            String targetId,
            String requestId,
            String clientIp,
            String detailsJson,
            String prevHash) {

        String idStr = (id != null) ? id.toString() : "";
        String occurredStr = formatOccurredAt(occurredAt);
        String actorStr = (actorUserId != null) ? actorUserId.toString() : "";
        String roleStr = (actorRole != null) ? actorRole : "";
        String actionStr = (action != null) ? action : "";
        String outcomeStr = (outcome != null) ? outcome : "";
        String targetTypeStr = (targetType != null) ? targetType : "";
        String targetIdStr = (targetId != null) ? targetId : "";
        String reqIdStr = (requestId != null) ? requestId : "";
        String ipStr = (clientIp != null) ? clientIp : "";
        String canonicalDetails = canonicalizeJson(detailsJson);
        String prevStr = (prevHash != null && !prevHash.isBlank()) ? prevHash : GENESIS_PREV_HASH;

        return seq + "|" +
                idStr + "|" +
                occurredStr + "|" +
                actorStr + "|" +
                roleStr + "|" +
                actionStr + "|" +
                outcomeStr + "|" +
                targetTypeStr + "|" +
                targetIdStr + "|" +
                reqIdStr + "|" +
                ipStr + "|" +
                canonicalDetails + "|" +
                prevStr;
    }

    /**
     * Computes the SHA-256 row hash (64 lowercase hex chars) over the canonical string.
     */
    public static String computeRowHash(
            long seq,
            UUID id,
            Instant occurredAt,
            UUID actorUserId,
            String actorRole,
            String action,
            String outcome,
            String targetType,
            String targetId,
            String requestId,
            String clientIp,
            String detailsJson,
            String prevHash) {

        String canonical = buildCanonicalString(
                seq, id, occurredAt, actorUserId, actorRole, action, outcome,
                targetType, targetId, requestId, clientIp, detailsJson, prevHash);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Hashes an email or sensitive string to first 16 hex characters of SHA-256 for privacy.
     */
    public static String hashIdentifier16(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
